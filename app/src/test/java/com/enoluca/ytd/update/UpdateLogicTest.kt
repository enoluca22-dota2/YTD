package com.enoluca.ytd.update

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateLogicTest {

    private fun v(s: String) = AppVersion.parse(s)!!

    @Test
    fun `versions compare numerically, never as strings`() {
        assertTrue(v("1.0.9") < v("1.0.10"))
        assertTrue(v("1.1.0") > v("1.0.99"))
        assertTrue(v("v2.0.0") > v("1.99.99"))
        assertEquals(0, v("v1.2.0").compareTo(v("1.2")))
        assertTrue(v("1.2.0-beta.1") < v("1.2.0"))
        assertTrue(v("1.2.0-beta.2") < v("1.2.0-beta.10"))
        assertTrue(v("1.2.0-alpha") < v("1.2.0-beta"))
        assertNull(AppVersion.parse("latest"))
        assertNull(AppVersion.parse("1.x"))
        assertNull(AppVersion.parse(""))
    }

    @Test
    fun `version codes grow with every release`() {
        assertEquals(1_002_003, AppVersion.versionCodeFor("v1.2.3"))
        assertTrue(AppVersion.versionCodeFor("1.0.10") > AppVersion.versionCodeFor("1.0.9"))
        assertTrue(AppVersion.versionCodeFor("1.1.0") > AppVersion.versionCodeFor("1.0.99"))
        assertTrue(AppVersion.versionCodeFor("0.1.0") > 1) // above the old hard-coded versionCode = 1
    }

    private val mapper = ObjectMapper()

    private fun release(
        tag: String = "v1.2.0",
        draft: Boolean = false,
        prerelease: Boolean = false,
        assets: String = """
            {"name":"YTD-v1.2.0.apk","size":152000000,"browser_download_url":"https://github.com/o/r/releases/download/v1.2.0/YTD-v1.2.0.apk"},
            {"name":"YTD-v1.2.0-arm64-v8a.apk","size":55000000,"browser_download_url":"https://github.com/o/r/releases/download/v1.2.0/YTD-v1.2.0-arm64-v8a.apk"},
            {"name":"YTD-v1.2.0-x86_64.apk","size":58000000,"browser_download_url":"https://github.com/o/r/releases/download/v1.2.0/YTD-v1.2.0-x86_64.apk"},
            {"name":"SHA256SUMS.txt","size":300,"browser_download_url":"https://github.com/o/r/releases/download/v1.2.0/SHA256SUMS.txt"}
        """,
    ) = mapper.readTree(
        """{"tag_name":"$tag","name":"YTD $tag","draft":$draft,"prerelease":$prerelease,
            "body":"- Real download progress\n- In-app updates","html_url":"https://github.com/o/r/releases/tag/$tag",
            "assets":[$assets]}"""
    )

    @Test
    fun `reads tag, notes and picks the APK for the device ABI`() {
        val r = (ReleaseParser.parse(release(), listOf("arm64-v8a", "armeabi-v7a")) as ReleaseParser.Result.Ok).release
        assertEquals("v1.2.0", r.tag)
        assertEquals(v("1.2.0"), r.version)
        assertEquals("- Real download progress\n- In-app updates", r.notes)
        assertEquals("YTD-v1.2.0-arm64-v8a.apk", r.apk.name)
        assertEquals(55_000_000L, r.apk.sizeBytes)
        assertEquals("SHA256SUMS.txt", r.checksums?.name)
    }

    @Test
    fun `falls back to the universal APK`() {
        val r = (ReleaseParser.parse(release(), listOf("armeabi-v7a")) as ReleaseParser.Result.Ok).release
        assertEquals("YTD-v1.2.0.apk", r.apk.name)
    }

    @Test
    fun `drafts, pre-releases, odd tags and releases without our APK are ignored`() {
        assertTrue(ReleaseParser.parse(release(draft = true), listOf("x86_64")) is ReleaseParser.Result.Unusable)
        assertTrue(ReleaseParser.parse(release(prerelease = true), listOf("x86_64")) is ReleaseParser.Result.Unusable)
        assertTrue(ReleaseParser.parse(release(tag = "nightly"), listOf("x86_64")) is ReleaseParser.Result.Unusable)
        // An APK whose file name carries a different version than the tag is not trusted.
        val stale = """{"name":"YTD-v1.1.0.apk","browser_download_url":"https://github.com/o/r/a/YTD-v1.1.0.apk"}"""
        assertTrue(ReleaseParser.parse(release(assets = stale), listOf("x86_64")) is ReleaseParser.Result.Unusable)
        val other = """{"name":"Other-v1.2.0.apk","browser_download_url":"https://github.com/o/r/a/Other-v1.2.0.apk"}"""
        assertTrue(ReleaseParser.parse(release(assets = other), listOf("x86_64")) is ReleaseParser.Result.Unusable)
        val http = """{"name":"YTD-v1.2.0.apk","browser_download_url":"http://insecure.example/YTD-v1.2.0.apk"}"""
        assertTrue(ReleaseParser.parse(release(assets = http), listOf("x86_64")) is ReleaseParser.Result.Unusable)
    }

    @Test
    fun `checksums are read from sha256sum output`() {
        val hash = "a".repeat(64)
        val sums = "$hash  YTD-v1.2.0.apk\n${"b".repeat(64)} *YTD-v1.2.0-arm64-v8a.apk\n"
        assertEquals(hash, ReleaseParser.checksumFor(sums, "YTD-v1.2.0.apk"))
        assertEquals("b".repeat(64), ReleaseParser.checksumFor(sums, "YTD-v1.2.0-arm64-v8a.apk"))
        assertNull(ReleaseParser.checksumFor(sums, "YTD-v1.2.0-x86_64.apk"))
        assertNull(ReleaseParser.checksumFor("nothex  YTD-v1.2.0.apk", "YTD-v1.2.0.apk"))
    }

    @Test
    fun `automatic check runs at most once a day and only online`() {
        val day = UpdateConfig.AUTO_CHECK_INTERVAL_MS
        val now = 100 * day
        assertTrue(UpdateManager.isAutoCheckDue(now, 0, online = true))
        assertFalse(UpdateManager.isAutoCheckDue(now, now - 60_000, online = true))
        assertTrue(UpdateManager.isAutoCheckDue(now, now - day, online = true))
        assertFalse(UpdateManager.isAutoCheckDue(now, 0, online = false))
    }
}
