package com.enoluca.ytd.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.enoluca.ytd.BuildConfig
import com.enoluca.ytd.core.NetworkMonitor
import com.enoluca.ytd.data.provider.ProviderException
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlin.coroutines.coroutineContext

/**
 * Check → download → verify → hand to the system installer, for APKs published as GitHub
 * Release assets. App-scoped so a download keeps going if the user leaves Settings; every screen
 * observes [state]. The APK is never installed silently: Android always shows its own
 * confirmation.
 */
class UpdateManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val networkMonitor: NetworkMonitor,
) {
    sealed interface State {
        data object Idle : State
        data object NotConfigured : State
        data object Checking : State
        data class UpToDate(val current: String) : State
        data class Available(val release: ReleaseInfo, val current: String) : State
        /** [downloadedBytes]/[totalBytes] are real byte counts; [fraction] is null while the size is unknown. */
        data class Downloading(val release: ReleaseInfo, val downloadedBytes: Long, val totalBytes: Long?) : State {
            val fraction: Float? get() = totalBytes?.takeIf { it > 0 }?.let { (downloadedBytes.toDouble() / it).toFloat().coerceIn(0f, 1f) }
        }
        data class Verifying(val release: ReleaseInfo) : State
        /** Waiting for the system installer (or for "Install unknown apps" to be allowed). */
        data class ReadyToInstall(val release: ReleaseInfo, val apk: File, val needsPermission: Boolean) : State
        /** [apk] is set when a verified download exists and only the install step failed. */
        data class Failed(val message: String, val release: ReleaseInfo?, val apk: File? = null) : State
    }

    private val _state = MutableStateFlow<State>(if (UpdateConfig.isConfigured) State.Idle else State.NotConfigured)
    val state: StateFlow<State> = _state.asStateFlow()

    private val prefs = context.getSharedPreferences("app_updates", Context.MODE_PRIVATE)
    private var job: Job? = null

    val currentVersion: String get() = BuildConfig.VERSION_NAME

    var autoCheckEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO, value).apply()

    // --- Check --------------------------------------------------------------------------------

    /** Settings → "Check for Updates". Always allowed. */
    fun checkNow() {
        if (!UpdateConfig.isConfigured) {
            _state.value = State.NotConfigured
            return
        }
        if (job?.isActive == true) return
        job = scope.launch { check(userInitiated = true) }
    }

    /**
     * Launch-time check: at most once per 24 h, only if enabled and online, and a version the
     * user already answered "Later" to is not offered again automatically.
     */
    fun autoCheckIfDue(now: Long = System.currentTimeMillis()) {
        if (!UpdateConfig.isConfigured || !autoCheckEnabled || job?.isActive == true) return
        if (!isAutoCheckDue(now, prefs.getLong(KEY_LAST_CHECK, 0L), networkMonitor.isOnline())) return
        job = scope.launch { check(userInitiated = false) }
    }

    private suspend fun check(userInitiated: Boolean) {
        if (userInitiated) _state.value = State.Checking
        if (!networkMonitor.isOnline()) {
            if (userInitiated) _state.value = State.Failed(ProviderException.CONNECTION_FAILED, null)
            return
        }
        try {
            val release = fetchLatest()
            prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
            val current = AppVersion.parse(currentVersion)
            val newer = current == null || release.version > current
            _state.value = when {
                !newer -> if (userInitiated) State.UpToDate(currentVersion) else State.Idle
                !userInitiated && prefs.getString(KEY_DISMISSED, null) == release.tag -> State.Idle
                else -> State.Available(release, currentVersion)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Update check failed", e)
            if (userInitiated) {
                _state.value = State.Failed(
                    when (e) {
                        is UpdateProblem -> e.message!!
                        is RateLimited -> "GitHub is limiting requests right now. Try again in a little while."
                        is IOException -> "Couldn't reach GitHub. Check your internet connection and try again."
                        else -> "Couldn't check for updates (${e.javaClass.simpleName}). Try again later."
                    },
                    null,
                )
            }
        }
    }

    /**
     * The latest release. Throws [UpdateProblem] (shown to the user as is) when the repository has
     * no release, or its latest release can't be installed on this device — never reported as
     * "up to date".
     */
    private suspend fun fetchLatest(): ReleaseInfo = withContext(Dispatchers.IO) {
        val connection = (URL(UpdateConfig.latestReleaseApi).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "${UpdateConfig.APP_NAME}/$currentVersion (Android)")
        }
        try {
            when (val code = connection.responseCode) {
                200 -> {
                    val root = connection.inputStream.use { YoutubeDL.objectMapper.readTree(it) }
                    when (val parsed = ReleaseParser.parse(root, Build.SUPPORTED_ABIS.toList())) {
                        is ReleaseParser.Result.Ok -> parsed.release
                        is ReleaseParser.Result.Unusable -> {
                            Log.w(TAG, "Latest release not usable: ${parsed.reason}")
                            throw UpdateProblem("The latest release on GitHub can't be installed: ${parsed.reason}.")
                        }
                    }
                }
                // No published (non-draft, non-prerelease) release, or the repository isn't public.
                404 -> throw UpdateProblem("No release is published at ${UpdateConfig.releasesPage}.")
                403, 429 -> throw RateLimited()
                in 500..599 -> throw UpdateProblem("GitHub is unavailable right now (HTTP $code). Try again later.")
                else -> throw UpdateProblem("GitHub answered with HTTP $code. Try again later.")
            }
        } finally {
            connection.disconnect()
        }
    }

    /** "Later": close the dialog and don't offer this version again automatically. */
    fun later() {
        (state.value as? State.Available)?.let { prefs.edit().putString(KEY_DISMISSED, it.release.tag).apply() }
        _state.value = State.Idle
    }

    fun dismiss() {
        if (state.value is State.Downloading || state.value is State.Verifying) return
        _state.value = if (UpdateConfig.isConfigured) State.Idle else State.NotConfigured
    }

    // --- Download + verify ---------------------------------------------------------------------

    /** "Update Now". */
    fun download() {
        val failed = state.value as? State.Failed
        val release = when (val s = state.value) {
            is State.Available -> s.release
            is State.Failed -> s.release ?: return
            else -> return
        }
        // Release APKs are always the release package signed with the release key. A debug or
        // otherwise differently built install can never be replaced by them, so say so up front
        // instead of downloading ~50–150 MB that Android would refuse.
        if (context.packageName != BuildConfig.RELEASE_APPLICATION_ID) {
            Log.w(TAG, "Update blocked at 'Package name mismatch': installed ${context.packageName}, releases ${BuildConfig.RELEASE_APPLICATION_ID}")
            _state.value = State.Failed(
                "Package name mismatch: this installed copy is a ${if (BuildConfig.DEBUG) "debug" else "test"} build (${context.packageName}); " +
                    "GitHub releases are ${BuildConfig.RELEASE_APPLICATION_ID} and can't update it. Install " +
                    "${release.apk.name} from ${UpdateConfig.releasesPage} once; that copy updates itself from then on.",
                release,
            )
            return
        }
        job?.cancel()
        // The installer step failed after a successful download + verification: don't download again.
        failed?.apk?.takeIf { it.exists() }?.let { apk ->
            job = scope.launch { startInstall(apk, release) }
            return
        }
        job = scope.launch {
            val target = File(updatesDir(), release.apk.name)
            try {
                downloadApk(release, target)
                _state.value = State.Verifying(release)
                verify(release, target)
                startInstall(target, release)
            } catch (e: CancellationException) {
                File(target.path + ".part").delete()
                _state.value = State.Available(release, currentVersion)
                throw e
            } catch (e: VerificationFailed) {
                target.delete()
                Log.w(TAG, "Update verification FAILED at '${e.check}': ${e.technical}")
                _state.value = State.Failed("${e.check}: ${e.message}", release)
            } catch (e: Exception) {
                Log.w(TAG, "Update download failed", e)
                _state.value = State.Failed(
                    when (e) {
                        is UpdateProblem -> e.message!!
                        is IOException -> "Download failed: ${e.message ?: "connection lost"}. Check your connection and try again."
                        else -> "Download failed (${e.javaClass.simpleName}). Try again."
                    },
                    release,
                )
            }
        }
    }

    /** Hands a verified APK to the installer, or asks for "Install unknown apps" first. */
    private suspend fun startInstall(apk: File, release: ReleaseInfo) {
        val allowed = canInstall()
        _state.value = State.ReadyToInstall(release, apk, needsPermission = !allowed)
        if (allowed) install(apk, release)
    }

    fun cancelDownload() {
        job?.cancel()
    }

    private suspend fun downloadApk(release: ReleaseInfo, target: File) = withContext(Dispatchers.IO) {
        val part = File(target.path + ".part")
        part.delete()
        target.delete()
        var connection = URL(release.apk.downloadUrl).openConnection() as HttpURLConnection
        try {
            // GitHub serves assets through a redirect to its CDN (HTTPS → HTTPS is followed automatically).
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Accept", "application/octet-stream")
            connection.setRequestProperty("User-Agent", "${UpdateConfig.APP_NAME}/$currentVersion (Android)")
            val code = connection.responseCode
            when {
                code in 200..299 -> Unit
                code == 404 -> throw UpdateProblem("Download failed: ${release.apk.name} is no longer on GitHub (HTTP 404). Check for updates again.")
                else -> throw UpdateProblem("Download failed: GitHub answered with HTTP $code for ${release.apk.name}. Try again.")
            }
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: release.apk.sizeBytes
            var downloaded = 0L
            var lastEmit = 0L
            _state.value = State.Downloading(release, 0, total)
            connection.inputStream.use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        val now = System.currentTimeMillis()
                        // ~5 UI updates per second, from the real byte count.
                        if (now - lastEmit >= 200) {
                            lastEmit = now
                            _state.value = State.Downloading(release, downloaded, total)
                        }
                    }
                }
            }
            if (total != null && downloaded != total) {
                Log.w(TAG, "Download incomplete: $downloaded of $total bytes")
                throw UpdateProblem("Download incomplete: received $downloaded of $total bytes. Try again.")
            }
            Log.i(TAG, "Download completed: $downloaded bytes from ${connection.url.host}")
            _state.value = State.Downloading(release, downloaded, total ?: downloaded)
            if (!part.renameTo(target)) throw IOException("Couldn't store the update")
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Every check an update must pass before it reaches the installer. Each failure names the
     * check (UI and Logcat) and keeps the technical detail (hashes, codes, certificate digests —
     * all public values) in Logcat only.
     */
    private suspend fun verify(release: ReleaseInfo, apk: File) = withContext(Dispatchers.IO) {
        Log.i(TAG, "Verifying ${release.apk.name} (${apk.length()} bytes) for ${release.tag}")
        // 1. File integrity: it's a ZIP (every APK is), not an HTML error page.
        val magic = apk.inputStream().use { s -> ByteArray(4).also { s.read(it) } }
        if (!(magic[0] == 0x50.toByte() && magic[1] == 0x4B.toByte() && magic[2] == 0x03.toByte() && magic[3] == 0x04.toByte())) {
            throw VerificationFailed(
                "Invalid APK", "the downloaded file isn't an APK (GitHub may have sent an error page). Try again.",
                "first bytes ${magic.joinToString(" ") { "%02x".format(it) }}",
            )
        }
        // 2. SHA-256 against the release's SHA256SUMS.txt, when published.
        val actualSha = sha256(apk)
        release.checksums?.let { sumsAsset ->
            val sums = (URL(sumsAsset.downloadUrl).openConnection() as HttpURLConnection).run {
                connectTimeout = 15_000
                readTimeout = 20_000
                try {
                    if (responseCode !in 200..299) throw IOException("HTTP $responseCode for ${sumsAsset.name}")
                    inputStream.use { it.readBytes().decodeToString() }
                } finally {
                    disconnect()
                }
            }
            val expected = ReleaseParser.checksumFor(sums, release.apk.name)
                ?: throw VerificationFailed(
                    "Checksum missing", "${sumsAsset.name} on GitHub doesn't list ${release.apk.name}.",
                    "no line for ${release.apk.name} in ${sumsAsset.name}",
                )
            if (!expected.equals(actualSha, ignoreCase = true)) {
                throw VerificationFailed(
                    "Checksum mismatch", "the downloaded APK doesn't match the published SHA-256 (corrupted download). Try again.",
                    "expected $expected, got $actualSha",
                )
            }
        }
        Log.i(TAG, "Checksum: PASS (sha256 $actualSha${if (release.checksums == null) ", none published" else ""})")
        // 3. Android can parse it.
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(apk.path, flags)
            ?: throw VerificationFailed("APK could not be parsed", "Android can't read the downloaded APK. Try again.", "getPackageArchiveInfo returned null")
        val installed = pm.getPackageInfo(context.packageName, flags)
        Log.i(
            TAG,
            "APK parsing: PASS (${archive.packageName} ${archive.versionName}/${archive.longVersionCodeCompat()}; " +
                "installed ${installed.packageName} ${installed.versionName}/${installed.longVersionCodeCompat()})",
        )
        // 4. It is this app.
        if (archive.packageName != context.packageName) {
            throw VerificationFailed(
                "Package name mismatch", "the APK is ${archive.packageName}, but this app is ${context.packageName}.",
                "archive ${archive.packageName} != installed ${context.packageName}",
            )
        }
        // 5. It is newer (versionCode is what Android compares, not versionName).
        if (archive.longVersionCodeCompat() <= installed.longVersionCodeCompat()) {
            throw VerificationFailed(
                "VersionCode is not newer",
                "the APK's versionCode (${archive.longVersionCodeCompat()}) isn't higher than the installed app's " +
                    "(${installed.longVersionCodeCompat()}), so Android would refuse it.",
                "archive ${archive.longVersionCodeCompat()} <= installed ${installed.longVersionCodeCompat()}",
            )
        }
        // 6. Its native code runs on this device.
        val apkAbis = ZipFile(apk).use { zip ->
            zip.entries().asSequence().mapNotNull { e -> e.name.takeIf { it.startsWith("lib/") }?.split('/')?.getOrNull(1) }.toSet()
        }
        if (apkAbis.isNotEmpty() && apkAbis.none { it in Build.SUPPORTED_ABIS }) {
            throw VerificationFailed(
                "Unsupported ABI", "the APK is built for ${apkAbis.joinToString()}, which this device can't run.",
                "apk $apkAbis, device ${Build.SUPPORTED_ABIS.toList()}",
            )
        }
        Log.i(TAG, "ABI: PASS (apk $apkAbis, device ${Build.SUPPORTED_ABIS.toList()})")
        // 7. It is signed with the same certificate as the installed app (Android requires this for an update).
        val archiveCerts = signatures(archive)
        val installedCerts = signatures(installed)
        if (archiveCerts.isEmpty() || archiveCerts != installedCerts) {
            throw VerificationFailed(
                "Signing certificate mismatch",
                "the APK isn't signed with the same certificate as the installed app, so Android can't install it " +
                    "over it. Uninstall this copy, then install ${release.apk.name} from ${UpdateConfig.releasesPage} " +
                    "once (uninstalling removes this copy's settings and history).",
                "apk cert sha256 $archiveCerts, installed cert sha256 $installedCerts",
            )
        }
        Log.i(TAG, "Signature: PASS (cert sha256 $archiveCerts)")
    }

    // --- Install --------------------------------------------------------------------------------

    fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** Opens "Install unknown apps" for this app; the user comes back and taps Install. */
    fun openInstallPermissionSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't open the install-permission settings", e)
            val ready = state.value as? State.ReadyToInstall
            _state.value = State.Failed(
                "Installation permission missing, and Android couldn't open its setting. Allow it in " +
                    "Settings → Apps → ENAGELYUCA → Install unknown apps, then tap Try again.",
                ready?.release,
                ready?.apk,
            )
        }
    }

    fun refreshInstallPermission() {
        val ready = state.value as? State.ReadyToInstall ?: return
        if (ready.needsPermission && canInstall()) _state.value = ready.copy(needsPermission = false)
    }

    /** Called from the dialog's "Install" button (after the permission was granted). */
    fun installReady() {
        val ready = state.value as? State.ReadyToInstall ?: return
        if (!canInstall()) {
            _state.value = ready.copy(needsPermission = true)
            return
        }
        scope.launch { install(ready.apk, ready.release) }
    }

    /**
     * PackageInstaller session: Android shows its own confirmation screen (never silent), then
     * replaces the app in place — same package, same signing key, higher versionCode.
     */
    private suspend fun install(apk: File, release: ReleaseInfo) = withContext(Dispatchers.IO) {
        try {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
            }
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val callback = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    Intent(context, UpdateInstallReceiver::class.java).setPackage(context.packageName),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(callback.intentSender)
            }
            _state.value = State.ReadyToInstall(release, apk, needsPermission = false)
        } catch (e: Exception) {
            // Some ROMs refuse installer sessions; the classic installer screen via a content://
            // URI from our FileProvider still works there.
            Log.w(TAG, "PackageInstaller session failed; falling back to the installer screen", e)
            try {
                withContext(Dispatchers.Main) { openInstallerScreen(apk) }
                _state.value = State.ReadyToInstall(release, apk, needsPermission = false)
            } catch (e2: Exception) {
                Log.w(TAG, "Couldn't open the installer", e2)
                _state.value = State.Failed(
                    "Android's installer could not be opened (${e.message ?: e.javaClass.simpleName}). Tap Try again.",
                    release,
                    apk,
                )
            }
        }
    }

    private fun openInstallerScreen(apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME_TYPE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /**
     * Reported back by [UpdateInstallReceiver] when the installer ends without installing or its
     * confirmation screen can't be shown. The verified APK is kept so "Try again" doesn't
     * download it again.
     */
    internal fun onInstallFailed(message: String?) {
        val ready = state.value as? State.ReadyToInstall
        _state.value = State.Failed(message ?: "The update wasn't installed.", ready?.release, ready?.apk)
    }

    // --- helpers --------------------------------------------------------------------------------

    private fun updatesDir() = File(context.cacheDir, "updates").apply { mkdirs() }

    /** Removes downloaded update files (called at start-up: after an update they're useless). */
    fun cleanUpOldDownloads() {
        scope.launch(Dispatchers.IO) { File(context.cacheDir, "updates").deleteRecursively() }
    }

    private fun PackageInfo.longVersionCodeCompat(): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode else @Suppress("DEPRECATION") versionCode.toLong()

    private fun signatures(info: PackageInfo): Set<String> {
        val raw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.let { if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory }
        } else {
            @Suppress("DEPRECATION") info.signatures
        }
        return raw.orEmpty().map { sig -> MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
    }

    private class RateLimited : IOException("GitHub API rate limit")
    /** A failure whose message is already written for the user. */
    private class UpdateProblem(message: String) : Exception(message)
    /** [check] names the failed check; [message] is for the user, [technical] for Logcat. */
    private class VerificationFailed(val check: String, message: String, val technical: String) : Exception(message)

    companion object {
        private const val TAG = "UpdateManager"
        private const val KEY_LAST_CHECK = "last_check"
        private const val KEY_DISMISSED = "dismissed_tag"
        private const val KEY_AUTO = "auto_check"
        private const val APK_MIME_TYPE = "application/vnd.android.package-archive"

        fun isAutoCheckDue(now: Long, lastCheck: Long, online: Boolean): Boolean =
            online && (lastCheck <= 0L || now < lastCheck || now - lastCheck >= UpdateConfig.AUTO_CHECK_INTERVAL_MS)

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
