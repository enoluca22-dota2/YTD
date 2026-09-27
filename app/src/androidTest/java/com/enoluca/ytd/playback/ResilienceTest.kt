package com.enoluca.ytd.playback

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.enoluca.ytd.YtdApplication
import com.enoluca.ytd.data.local.db.AppDatabase
import com.enoluca.ytd.data.local.db.DownloadEntity
import com.enoluca.ytd.data.local.db.LibraryMediaEntity
import com.enoluca.ytd.data.model.DownloadCategory
import com.enoluca.ytd.data.model.DownloadStatus
import com.enoluca.ytd.data.model.FormatKind
import com.enoluca.ytd.download.CompletedDownload
import com.enoluca.ytd.library.TestMedia
import com.enoluca.ytd.radio.RadioCategory
import com.enoluca.ytd.radio.RadioStation
import com.enoluca.ytd.ui.library.LibraryViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Things that go wrong on real phones — files deleted or moved behind the app's back, artwork
 * that can't be fetched, a station that's down — through the real PlaybackService, Library and
 * database. None of them may crash the app or stop the rest of the queue.
 */
@RunWith(AndroidJUnit4::class)
class ResilienceTest {

    private val app: YtdApplication = ApplicationProvider.getApplicationContext()
    private val container = app.container
    private val connection = container.playerConnection
    private val library = container.libraryRepository
    private val created = mutableListOf<Long>()
    private val createdPlaylists = mutableListOf<Long>()

    @After
    fun tearDown() = runBlocking {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            connection.stop()
            connection.disconnect()
        }
        createdPlaylists.forEach { library.deletePlaylist(it, deleteMedia = false) }
        library.forget(created)
        TestMedia.cleanUp(app)
    }

    private fun waitFor(what: String, timeoutMs: Long = 20_000, condition: (PlayerUiState) -> Boolean): PlayerUiState {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val state = connection.state.value
            if (condition(state)) return state
            if (System.currentTimeMillis() > deadline) fail("Timed out waiting for $what; last state: $state")
            Thread.sleep(50)
        }
    }

    private fun until(what: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("Timed out waiting for $what")
            Thread.sleep(50)
        }
    }

    private fun connect() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { connection.connect() }
        waitFor("the controller to connect") { it.connected }
    }

    private fun index(file: File, title: String, thumbnail: String? = null, ext: String = "wav", mime: String = "audio/x-wav"): LibraryMediaEntity = runBlocking {
        val now = System.nanoTime()
        val download = DownloadEntity(
            id = -now, sourceUrl = "test://$title", webpageUrl = "test://$title", title = title, thumbnailUrl = thumbnail,
            extractorKey = null, formatId = "x", formatKind = FormatKind.AUDIO, resolutionLabel = null, container = ext,
            videoCodec = null, audioCodec = null, requiresAudioMerge = false, requiresAudioExtraction = false,
            category = DownloadCategory.MUSIC, fileBaseName = title, status = DownloadStatus.COMPLETED,
            createdAt = 1, updatedAt = 1,
        )
        container.libraryIndexer.onDownloadCompleted(CompletedDownload(download, TestMedia.uriOf(file), file.length(), ext, mime, null))
        library.findByUri(TestMedia.uriOf(file))!!.also { created += it.id }
    }

    private fun song(title: String, seconds: Int = 3) = index(TestMedia.wav(app, "res-$title", seconds), title)

    private fun fileOf(item: LibraryMediaEntity) = File(android.net.Uri.parse(item.uri).path!!)

    @Test
    fun aFileDeletedBehindTheAppsBackIsSkippedMarkedMissingAndTheQueueGoesOn() {
        val a = song("Res A", 2)
        val b = song("Res B (deleted)")
        val c = song("Res C", 20)
        assertTrue(fileOf(b).delete()) // the Library still thinks it's there
        connect()
        connection.play(listOf(a, b, c), startIndex = 0, contextTitle = "Resilience")
        waitFor("A to play") { it.isPlaying && it.current?.mediaId == a.id }
        // A ends → B can't be opened → skipped → C plays.
        waitFor("C to play after the missing B", timeoutMs = 30_000) { it.isPlaying && it.current?.mediaId == c.id }
        until("B to be marked missing") { runBlocking { library.getMedia(b.id)?.isAvailable == false } }
    }

    @Test
    fun aQueueOfOnlyMissingFilesFailsQuietly() {
        val gone = song("Res Gone")
        assertTrue(fileOf(gone).delete())
        connect()
        connection.play(listOf(gone), startIndex = 0)
        waitFor("an error, not a crash") { it.error != null || !it.hasMedia }
        // Once known missing, it isn't offered to the player at all.
        until("marked missing") { runBlocking { library.getMedia(gone.id)?.isAvailable == false } }
        val refreshed = runBlocking { library.getMedia(gone.id)!! }
        connection.play(listOf(refreshed), startIndex = 0)
        Thread.sleep(500)
        assertFalse(connection.state.value.isPlaying)
    }

    @Test
    fun aPlaylistWithDeletedMediaKeepsItsOrderAndPlaysWhatExists() = runBlocking<Unit> {
        val a = song("Pl A", 20)
        val b = song("Pl B (deleted)")
        val c = song("Pl C", 20)
        val playlist = library.createPlaylist("Resilience list").also { createdPlaylists += it }
        library.addToPlaylist(playlist, listOf(a.id, b.id, c.id))
        assertTrue(fileOf(b).delete())
        assertTrue(library.markMissingIfGone(b.id))

        val tracks = library.getPlaylistTracks(playlist)
        assertEquals("the missing item stays listed (dimmed) in its place", listOf(a.id, b.id, c.id), tracks.map { it.media.id })
        assertFalse(tracks[1].media.isAvailable)

        connect()
        connection.play(tracks.map { it.media }, startIndex = 0, contextTitle = "Resilience list", contextPlaylistId = playlist)
        val state = waitFor("the playlist to play") { it.isPlaying && it.current?.mediaId == a.id }
        assertEquals("only files that exist are queued", listOf(a.id, c.id), state.queue.map { it.mediaId })
    }

    @Test
    fun aQueueSavedAsPlaylistIsOnDiskInPlayOrder() {
        val a = song("Save A", 20)
        val b = song("Save B", 20)
        val c = song("Save C", 20)
        connect()
        connection.play(listOf(c, a, b), startIndex = 0, contextTitle = "Queue test")
        val state = waitFor("the queue") { it.queue.size == 3 && it.current?.mediaId == c.id }

        var vm: LibraryViewModel? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            vm = LibraryViewModel(library, container.libraryIndexer, connection)
            vm!!.saveQueueAsPlaylist(state.queue.mapNotNull { it.mediaId }, "Saved queue test")
        }
        val saved = runBlocking {
            var found: Long? = null
            val deadline = System.currentTimeMillis() + 5_000
            while (found == null && System.currentTimeMillis() < deadline) {
                found = library.observePlaylists().first().firstOrNull { it.playlist.title == "Saved queue test" }?.playlist?.id
                if (found == null) Thread.sleep(50)
            }
            found ?: fail("playlist not created") as Nothing
        }
        createdPlaylists += saved

        // "Reopen the app": a separate database connection to the same file sees it.
        val reopened = Room.databaseBuilder(app, AppDatabase::class.java, AppDatabase.DATABASE_NAME).build()
        try {
            val titles = runBlocking { reopened.libraryDao().getPlaylistTracks(saved).map { it.media.title } }
            assertEquals(listOf("Save C", "Save A", "Save B"), titles)
        } finally {
            reopened.close()
        }
    }

    @Test
    fun anUnreachableStationShowsAnErrorAndRetryDoesNotCrash() {
        val dead = RadioStation("audit-unreachable-station", "Offline FM", "IT", null, "Test", RadioCategory.MUSIC, "https://127.0.0.1:9/stream")
        runBlocking { container.radioRepository.remember(dead) }
        connect()
        connection.playRadio(dead)
        val failed = waitFor("the station error", timeoutMs = 30_000) { it.isRadio && it.error != null }
        assertEquals("This station can't be reached right now.", failed.error)
        connection.togglePlayPause() // Play = retry
        Thread.sleep(3_000)
        assertTrue("still the same station, app alive", connection.state.value.current?.radioStationId == dead.id)
        connection.stop()
        waitFor("stop") { !it.hasMedia }
    }

    @Test
    fun missingOrUnfetchableArtworkAndCorruptFilesNeverCrash() = runBlocking<Unit> {
        // Real audio, but its thumbnail URL can't be downloaded.
        val noThumb = index(TestMedia.wav(app, "res-art", 2), "No artwork", thumbnail = "https://127.0.0.1:9/nothing.jpg")
        // Not media at all, named .mp3.
        val junk = File(File(app.filesDir, "test-media").apply { mkdirs() }, "junk.mp3").apply { writeBytes(ByteArray(4096) { 7 }) }
        val corrupt = index(junk, "Corrupt file", ext = "mp3", mime = "audio/mpeg")
        // The artwork jobs run in the background; give them time to fail.
        Thread.sleep(4_000)
        assertNotNull(library.getMedia(noThumb.id))
        assertNotNull(library.getMedia(corrupt.id))
        assertTrue(library.getMedia(noThumb.id)!!.isAvailable)

        // Playing the corrupt file is an error, not a crash; the real one still plays after it.
        connect()
        connection.play(listOf(library.getMedia(corrupt.id)!!, library.getMedia(noThumb.id)!!), startIndex = 0)
        waitFor("the playable file after the corrupt one", timeoutMs = 30_000) { it.current?.mediaId == noThumb.id && it.isPlaying }
    }
}
