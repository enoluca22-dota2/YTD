package com.enoluca.ytd.radio

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RadioLogicTest {

    private val mapper = ObjectMapper()

    // --- The bundled catalog (the real file shipped in the APK) ---------------------------------

    private val catalogFile = File("src/main/assets/radio/stations.json")
    private val catalog by lazy { RadioCatalogParser.parse(mapper.readTree(catalogFile)) }

    @Test
    fun `bundled catalog parses completely and covers Albania, UK and Italy`() {
        val raw = mapper.readTree(catalogFile).path("stations").size()
        assertEquals("every entry is valid", raw, catalog.size)
        for (country in RadioCountries.all) {
            val stations = catalog.filter { it.countryCode == country.code }
            assertTrue("${country.name} has stations", stations.size >= 10)
            assertTrue("${country.name} has news", stations.any { it.category == RadioCategory.NEWS })
            assertTrue("${country.name} has popular stations", stations.any { it.category == RadioCategory.POPULAR })
        }
    }

    @Test
    fun `bundled catalog has unique ids, playable URLs and only HTTPS logos`() {
        assertEquals(catalog.size, catalog.map { it.id }.toSet().size)
        assertTrue(catalog.all { RadioUrls.isPlayable(it.streamUrl) })
        assertTrue(catalog.all { it.logoUrl == null || it.logoUrl!!.startsWith("https://") })
        assertTrue(catalog.all { it.name.isNotBlank() && it.genre != null })
        // HLS stations are marked so the player uses the HLS extractor.
        assertTrue(catalog.filter { it.streamUrl.contains(".m3u8") }.all { it.isHls })
    }

    // --- Radio Browser directory ----------------------------------------------------------------

    private fun directory(vararg stations: String) = mapper.readTree("[${stations.joinToString(",")}]")

    private fun rb(
        uuid: String,
        name: String = "Station $uuid",
        url: String = "https://stream.example/$uuid",
        codec: String = "MP3",
        ok: Int = 1,
        tags: String = "pop",
        favicon: String = "https://logo.example/$uuid.png",
    ) = """{"stationuuid":"$uuid","name":"$name","url":"$url","url_resolved":"$url","codec":"$codec","bitrate":128,
        "lastcheckok":$ok,"tags":"$tags","countrycode":"it","state":"Roma","favicon":"$favicon","homepage":"https://x","hls":0}"""

    @Test
    fun `directory keeps only working audio stations, once`() {
        val stations = RadioBrowserParser.stations(
            directory(
                rb("a", name = "  Radio   Uno "),
                rb("b", ok = 0), // last check failed
                rb("c", codec = "AAC,H.264"), // TV stream
                rb("a"), // duplicate id
                rb("d", url = "https://stream.example/a"), // same stream as "a"
                rb("e", url = "ftp://nope"),
                rb("f", favicon = "http://insecure/logo.png"),
            ),
        )
        assertEquals(listOf("a", "f"), stations.map { it.id })
        val a = stations.first()
        assertEquals("Radio Uno", a.name)
        assertEquals("IT", a.countryCode)
        assertEquals("Roma", a.city)
        assertNull("insecure logos are not loaded", stations[1].logoUrl)
    }

    @Test
    fun `directory genres become categories`() {
        assertEquals(RadioCategory.NEWS, RadioBrowserParser.categoryOf(listOf("news", "talk")))
        assertEquals(RadioCategory.NEWS, RadioBrowserParser.categoryOf(listOf("Public Radio")))
        assertEquals(RadioCategory.ENTERTAINMENT, RadioBrowserParser.categoryOf(listOf("sports")))
        assertEquals(RadioCategory.MUSIC, RadioBrowserParser.categoryOf(listOf("rock", "80s")))
        assertEquals(RadioCategory.MUSIC, RadioBrowserParser.categoryOf(emptyList()))
    }

    @Test
    fun `play-time URL comes from the directory, keeping a verified HTTPS address`() {
        assertEquals("https://s/x", RadioBrowserParser.clickUrl(mapper.readTree("""{"ok":true,"url":"https://s/x"}""")))
        assertNull(RadioBrowserParser.clickUrl(mapper.readTree("""{"ok":false,"url":"https://s/x"}""")))
        assertEquals("https://a/s", RadioUrls.preferSecure("https://a/s", "http://a/s"))
        assertEquals("https://new/s", RadioUrls.preferSecure("https://a/s", "https://new/s"))
        assertEquals("http://moved/s", RadioUrls.preferSecure("http://a/s", "http://moved/s"))
        assertEquals("https://a/s", RadioUrls.preferSecure("https://a/s", null))
    }

    // --- Search & countries ----------------------------------------------------------------------

    private val tirana = RadioStation("1", "Radio Tirana 1", "AL", "Tirana", "News & Talk", RadioCategory.NEWS, "https://s/1")
    private val bbc = RadioStation("2", "BBC Radio 1", "GB", "London", "Pop", RadioCategory.POPULAR, "https://s/2")
    private val virgin = RadioStation("3", "Virgin Radio Italia", "IT", null, "Rock", RadioCategory.MUSIC, "https://s/3")

    @Test
    fun `search matches name, country, city and genre`() {
        val all = listOf(tirana, bbc, virgin)
        fun find(q: String) = all.filter { RadioSearch.matches(it, q) }.map { it.id }
        assertEquals(listOf("1"), find("albania"))
        assertEquals(listOf("2"), find("BBC"))
        assertEquals(listOf("2"), find("london"))
        assertEquals(listOf("3"), find("rock"))
        assertEquals(listOf("3"), find("italy"))
        assertEquals(listOf("1"), find("tirana news"))
        assertEquals(emptyList<String>(), find("   "))
        assertEquals(listOf("1", "2", "3"), RadioSearch.merge(listOf(tirana, bbc), listOf(bbc.copy(name = "dup"), virgin)).map { it.id })
    }

    @Test
    fun `countries are data, flags come from the code`() {
        assertEquals(listOf("AL", "GB", "IT"), RadioCountries.all.map { it.code })
        assertEquals("🇦🇱", RadioCountries.flagOf("al"))
        assertEquals("🇬🇧", RadioCountries.flagOf("GB"))
        assertEquals("🌍", RadioCountries.flagOf("??"))
        assertEquals("Germany", RadioCountries.displayName("DE"))
        assertEquals("Tirana · News & Talk", tirana.subtitle)
        assertFalse(RadioSearch.matches(tirana, "rock"))
    }
}
