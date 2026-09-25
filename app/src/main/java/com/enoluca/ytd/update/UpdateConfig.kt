package com.enoluca.ytd.update

import com.enoluca.ytd.BuildConfig

/**
 * The one place that knows where releases live. Values come from the build
 * (gradle.properties `ytd.github.owner` / `ytd.github.repo`, or the CI workflow, which passes
 * the repository it runs in), so no URL is hard-coded anywhere else.
 */
object UpdateConfig {
    val owner: String = BuildConfig.GITHUB_OWNER
    val repo: String = BuildConfig.GITHUB_REPO

    /** False in builds that weren't told which repository publishes the APK. */
    val isConfigured: Boolean get() = owner.isNotBlank() && repo.isNotBlank()

    /** Public, unauthenticated endpoint; never returns drafts or pre-releases. */
    val latestReleaseApi: String get() = "https://api.github.com/repos/$owner/$repo/releases/latest"
    val releasesPage: String get() = "https://github.com/$owner/$repo/releases"

    /** Product name used in release asset names and the User-Agent — the app's `app_name`. */
    const val APP_NAME = "ENAGELYUCA"

    /** Asset prefixes accepted by the updater: the current brand, and the pre-rename "YTD". */
    private const val ASSET_PREFIXES = "ENAGELYUCA|YTD"

    /**
     * Release assets, as produced by .github/workflows/release.yml:
     *   ENAGELYUCA-v1.2.0.apk               universal (every device)
     *   ENAGELYUCA-v1.2.0-arm64-v8a.apk     per-ABI, smaller (also armeabi-v7a, x86_64)
     *   SHA256SUMS.txt               "<sha256>  <file name>" per asset
     */
    val ASSET_PATTERN = Regex("""^(?:$ASSET_PREFIXES)-v(\d+(?:\.\d+)*(?:-(?!arm64|armeabi|x86)[0-9A-Za-z.]+)?)(?:-(arm64-v8a|armeabi-v7a|x86_64|x86))?\.apk$""")
    const val CHECKSUM_ASSET = "SHA256SUMS.txt"

    /** Automatic checks run at most this often (manual checks are always allowed). */
    const val AUTO_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000
}
