package com.enoluca.ytd.radio

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.enoluca.ytd.data.local.db.AppDatabase
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/** My Radio, Recently played, search and play-time URLs on a real (in-memory) database. */
@RunWith(AndroidJUnit4::class)
class RadioRepositoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase
    private lateinit var directory: FakeDirectory
    private lateinit var repo: RadioRepository
    private var clock = 1_000L

    private val tirana = RadioStation("al-1", "Radio Tirana 1", "AL", "Tirana", "News & Talk", RadioCategory.NEWS, "http://tirana/1")
    private val rai = RadioStation("it-1", "Rai Radio 1", "IT", null, "News", RadioCategory.NEWS, "https://rai/1")
    private val bbc = RadioStation("gb-1", "BBC Radio 1", "GB", "London", "Pop", RadioCategory.POPULAR, "https://bbc/1.m3u8", isHls = true)

    private class FakeDirectory : RadioDirectory {
        var available = true
        var slowMs = 0L
        val urls = mutableMapOf<String, String>()
        val stations = mutableListOf<RadioStation>()
        override suspend fun byCountry(countryCode: String, limit: Int) = check().let { stations.filter { it.countryCode == countryCode } }
        override suspend fun top(limit: Int) = check().let { stations.toList() }
        override suspend fun search(query: String, limit: Int) = check().let { stations.filter { RadioSearch.matches(it, query) } }
        override suspend fun currentStreamUrl(stationId: String): String? {
            if (slowMs > 0) delay(slowMs)
            return if (available) urls[stationId] else null
        }
        private fun check() { if (!available) throw IOException("offline") }
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        directory = FakeDirectory()
        val catalog = object : RadioCatalog { override fun stations() = listOf(tirana, rai, bbc) }
        repo = RadioRepository(catalog, directory, db.radioDao(), now = { clock++ })
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun favoritesAreSavedWithTheStationAndRemovedCleanly() = runBlocking {
        repo.setFavorite(rai, true)
        repo.setFavorite(tirana, true)
        assertEquals("newest first", listOf("al-1", "it-1"), repo.favorites.first().map { it.id })
        assertEquals(setOf("al-1", "it-1"), repo.favoriteIds.first())
        assertTrue(repo.favorites.first().all { it.isFavorite })

        repo.setFavorite(rai, false)
        assertEquals(listOf("al-1"), repo.favorites.first().map { it.id })
        assertNull("an unsaved, never played station leaves no row", db.radioDao().get("it-1"))
    }

    @Test
    fun recentlyPlayedIsNewestFirstCappedAndKeepsFavorites() = runBlocking {
        repo.setFavorite(tirana, true)
        repo.recordPlayed("al-1")
        repo.recordPlayed("it-1") // from the catalog, not saved yet
        repo.recordPlayed("gb-1")
        assertEquals(listOf("gb-1", "it-1", "al-1"), repo.recent.first().map { it.id })
        assertTrue("HLS flag survives the database", repo.recent.first().first { it.id == "gb-1" }.isHls)

        // Many more stations: the list keeps the newest RECENT_LIMIT.
        repeat(RadioRepository.RECENT_LIMIT + 5) { i ->
            val s = RadioStation("x$i", "Extra $i", "IT", null, null, RadioCategory.MUSIC, "https://x/$i")
            repo.remember(s)
            repo.recordPlayed(s.id)
        }
        val recent = repo.recent.first()
        assertEquals(RadioRepository.RECENT_LIMIT, recent.size)
        assertEquals("x${RadioRepository.RECENT_LIMIT + 4}", recent.first().id)
        assertEquals("favorites stay saved", listOf("al-1"), repo.favorites.first().map { it.id })

        repo.clearRecent()
        assertTrue(repo.recent.first().isEmpty())
        assertEquals(listOf("al-1"), repo.favorites.first().map { it.id })
    }

    @Test
    fun playbackUsesTheDirectoryUrlButKeepsVerifiedHttpsAndFallsBack() = runBlocking {
        // Moved stream: the directory's current URL is used (and remembered for saved stations).
        repo.setFavorite(tirana, true)
        directory.urls["al-1"] = "http://tirana/new"
        assertEquals("http://tirana/new", repo.resolveForPlayback("al-1")!!.streamUrl)
        assertEquals("http://tirana/new", db.radioDao().get("al-1")!!.streamUrl)

        // Same address over plain HTTP: keep the verified HTTPS one.
        directory.urls["it-1"] = "http://rai/1"
        assertEquals("https://rai/1", repo.resolveForPlayback("it-1")!!.streamUrl)

        // Directory unreachable or too slow: the last known URL still plays.
        directory.available = false
        assertEquals("https://bbc/1.m3u8", repo.resolveForPlayback("gb-1")!!.streamUrl)
        directory.available = true
        directory.urls["gb-1"] = "https://bbc/other.m3u8"
        directory.slowMs = 10_000
        val started = System.currentTimeMillis()
        assertEquals("https://bbc/1.m3u8", repo.resolveForPlayback("gb-1")!!.streamUrl)
        assertTrue("doesn't wait for a slow directory", System.currentTimeMillis() - started < 6_000)

        assertNull("unknown ids can't be played", repo.resolveForPlayback("nope"))
    }

    @Test
    fun searchAndBrowseWorkOfflineForCuratedStations() = runBlocking {
        assertEquals(listOf("al-1"), repo.searchLocal("albania", emptyList()).map { it.id })
        assertEquals(listOf("gb-1"), repo.searchLocal("london", emptyList()).map { it.id })
        assertEquals(listOf("it-1", "al-1").sorted(), repo.searchLocal("news", emptyList()).map { it.id }.sorted())
        assertEquals(listOf("al-1"), repo.featured("AL").map { it.id })

        directory.available = false
        try {
            repo.countryStations("IT")
            fail("an unreachable directory is reported, not shown as 'no stations'")
        } catch (e: IOException) {
            // expected
        }
        assertFalse(repo.featured("IT").isEmpty())

        directory.available = true
        directory.stations += RadioStation("it-9", "Radio Italia", "IT", null, "Italian pop", RadioCategory.MUSIC, "https://ri/9")
        assertEquals(listOf("it-9"), repo.countryStations("IT").map { it.id })
        assertEquals(listOf("it-9"), repo.searchDirectory("italia").map { it.id })
    }
}
