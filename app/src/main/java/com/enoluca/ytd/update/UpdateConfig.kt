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

    /** App name used in asset names — the project's own `app_name` ("YTD"). */
    const val APP_NAME = "YTD"

    /**
     * Release assets, as produced by .github/workflows/release.yml:
     *   YTD-v1.2.0.apk               universal (every device)
     *   YTD-v1.2.0-arm64-v8a.apk     per-ABI, smaller (also armeabi-v7a, x86_64)
     *   SHA256SUMS.txt               "<sha256>  <file name>" per asset
     */
    val ASSET_PATTERN = Regex("""^${APP_NAME}-v(\d+(?:\.\d+)*(?:-(?!arm64|armeabi|x86)[0-9A-Za-z.]+)?)(?:-(arm64-v8a|armeabi-v7a|x86_64|x86))?\.apk$""")
    const val CHECKSUM_ASSET = "SHA256SUMS.txt"

    /** Automatic checks run at most this often (manual checks are always allowed). */
    const val AUTO_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000
}
