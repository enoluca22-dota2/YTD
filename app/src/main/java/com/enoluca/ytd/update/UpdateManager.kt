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
        data class Failed(val message: String, val release: ReleaseInfo?) : State
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
            val newer = release != null && (current == null || release.version > current)
            _state.value = when {
                !newer -> if (userInitiated) State.UpToDate(currentVersion) else State.Idle
                !userInitiated && prefs.getString(KEY_DISMISSED, null) == release!!.tag -> State.Idle
                else -> State.Available(release!!, currentVersion)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Update check failed", e)
            if (userInitiated) {
                _state.value = State.Failed(
                    when (e) {
                        is RateLimited -> "GitHub is limiting requests right now. Try again in a little while."
                        is IOException -> ProviderException.CONNECTION_FAILED
                        else -> "Couldn't check for updates. Try again later."
                    },
                    null,
                )
            }
        }
    }

    /** The latest usable release, or null if the repository has none (404) or none for this device. */
    private suspend fun fetchLatest(): ReleaseInfo? = withContext(Dispatchers.IO) {
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
                            Log.i(TAG, "Latest release not usable: ${parsed.reason}")
                            null
                        }
                    }
                }
                404 -> null // no published (non-draft, non-prerelease) release yet
                403, 429 -> throw RateLimited()
                else -> throw IOException("GitHub API returned HTTP $code")
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
        val release = when (val s = state.value) {
            is State.Available -> s.release
            is State.Failed -> s.release ?: return
            else -> return
        }
        job?.cancel()
        job = scope.launch {
            val target = File(updatesDir(), release.apk.name)
            try {
                downloadApk(release, target)
                _state.value = State.Verifying(release)
                verify(release, target)
                _state.value = State.ReadyToInstall(release, target, needsPermission = !canInstall())
                if (canInstall()) install(target, release)
            } catch (e: CancellationException) {
                File(target.path + ".part").delete()
                _state.value = State.Available(release, currentVersion)
                throw e
            } catch (e: VerificationFailed) {
                target.delete()
                Log.w(TAG, "Downloaded update rejected: ${e.message}")
                _state.value = State.Failed("The downloaded update didn't pass verification and was deleted.", release)
            } catch (e: Exception) {
                Log.w(TAG, "Update download failed", e)
                _state.value = State.Failed(
                    if (e is IOException) ProviderException.CONNECTION_FAILED else "The update couldn't be downloaded. Try again.",
                    release,
                )
            }
        }
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
            if (code !in 200..299) throw IOException("HTTP $code for ${release.apk.name}")
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
            if (total != null && downloaded != total) throw IOException("Incomplete download: $downloaded of $total bytes")
            _state.value = State.Downloading(release, downloaded, total ?: downloaded)
            if (!part.renameTo(target)) throw IOException("Couldn't store the update")
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun verify(release: ReleaseInfo, apk: File) = withContext(Dispatchers.IO) {
        // 1. It's a ZIP (every APK is).
        val magic = apk.inputStream().use { s -> ByteArray(4).also { s.read(it) } }
        if (!(magic[0] == 0x50.toByte() && magic[1] == 0x4B.toByte() && magic[2] == 0x03.toByte() && magic[3] == 0x04.toByte())) {
            throw VerificationFailed("not a ZIP/APK file")
        }
        // 2. SHA-256 from the release's SHA256SUMS.txt, when published.
        release.checksums?.let { sumsAsset ->
            val sums = URL(sumsAsset.downloadUrl).openStream().use { it.readBytes().decodeToString() }
            val expected = ReleaseParser.checksumFor(sums, release.apk.name)
                ?: throw VerificationFailed("no checksum listed for ${release.apk.name}")
            val actual = sha256(apk)
            if (!expected.equals(actual, ignoreCase = true)) throw VerificationFailed("SHA-256 mismatch")
        }
        // 3. Android can parse it, it is this app, it is newer, and it is signed with our key.
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(apk.path, flags) ?: throw VerificationFailed("not a valid APK")
        if (archive.packageName != context.packageName) throw VerificationFailed("APK is for ${archive.packageName}")
        val installed = pm.getPackageInfo(context.packageName, flags)
        if (archive.longVersionCodeCompat() <= installed.longVersionCodeCompat()) throw VerificationFailed("APK is not newer than the installed app")
        if (signatures(archive) != signatures(installed)) throw VerificationFailed("APK is signed with a different key")
    }

    // --- Install --------------------------------------------------------------------------------

    fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** Opens "Install unknown apps" for YTD; the user comes back and taps Install. */
    fun openInstallPermissionSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
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
            Log.w(TAG, "Couldn't start the installer", e)
            _state.value = State.Failed("Android couldn't start the installation. Try again.", release)
        }
    }

    /** Reported back by [UpdateInstallReceiver] when the installer ends without installing. */
    internal fun onInstallFailed(message: String?) {
        val release = (state.value as? State.ReadyToInstall)?.release
        _state.value = State.Failed(message ?: "The update wasn't installed.", release)
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
    private class VerificationFailed(message: String) : Exception(message)

    companion object {
        private const val TAG = "UpdateManager"
        private const val KEY_LAST_CHECK = "last_check"
        private const val KEY_DISMISSED = "dismissed_tag"
        private const val KEY_AUTO = "auto_check"

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
