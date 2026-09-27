package com.enoluca.ytd.playback

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.enoluca.ytd.YtdApplication
import com.enoluca.ytd.data.local.db.DownloadEntity
import com.enoluca.ytd.data.local.db.LibraryMediaEntity
import com.enoluca.ytd.data.model.DownloadCategory
import com.enoluca.ytd.data.model.DownloadStatus
import com.enoluca.ytd.data.model.FormatKind
import com.enoluca.ytd.download.CompletedDownload
import com.enoluca.ytd.library.TestMedia
import com.enoluca.ytd.radio.RadioStation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Live radio through the real PlaybackService and the real network (curated catalog stations),
 * plus the saved Now Playing queue. Needs an internet connection.
 */
@RunWith(AndroidJUnit4::class)
class RadioPlaybackTest {

    private val app: YtdApplication = ApplicationProvider.getApplicationContext()
    private val container = app.container
    private val connection = container.playerConnection
    private val radio = container.radioRepository
    private val created = mutableListOf<Long>()

    @After
    fun tearDown() = runBlocking {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            connection.stop()
            connection.disconnect()
        }
        container.libraryRepository.forget(created)
        TestMedia.cleanUp(app)
    }

    private fun waitFor(what: String, timeoutMs: Long = 30_000, condition: (PlayerUiState) -> Boolean): PlayerUiState {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val state = connection.state.value
            if (condition(state)) return state
            if (System.currentTimeMillis() > deadline) fail("Timed out waiting for $what; last state: $state")
            Thread.sleep(100)
        }
    }

    private fun connect() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { connection.connect() }
        waitFor("the controller to connect") { it.connected }
    }

    private fun station(name: String): RadioStation =
        container.radioRepository.featured("GB").firstOrNull { it.name == name } ?: fail("$name missing from the catalog") as Nothing

    private fun playsLive(station: RadioStation) {
        runBlocking { radio.remember(station) }
        connection.playRadio(station)
        val state = waitFor("${station.name} to play") { it.isPlaying && it.current?.radioStationId == station.id }
        assertTrue(state.isRadio)
        assertEquals(station.name, state.current?.title)
        assertEquals("one live item, nothing queued behind it", 1, state.queue.size)
        assertFalse("live radio has no next", state.hasNext)
        assertFalse("live radio has no previous", state.hasPrevious)
        // It moves to the top of Recently played.
        val deadline = System.currentTimeMillis() + 5_000
        while (runBlocking { radio.recent.first().firstOrNull()?.id } != station.id) {
            if (System.currentTimeMillis() > deadline) fail("${station.name} not in Recently played")
            Thread.sleep(100)
        }
    }

    @Test
    fun anIcecastStationPlaysAndStopEndsPlayback() {
        connect()
        playsLive(station("BBC World Service"))
        connection.stop()
        waitFor("playback to end") { !it.hasMedia && !it.isPlaying }
    }

    @Test
    fun anHlsStationPlays() {
        connect()
        playsLive(station("BBC Radio 1"))
    }

    @Test
    fun radioReplacesTheQueueAndSongsAddedDuringRadioStartANewOne() {
        val song = libraryItem("Radio Q", 30)
        connect()
        playsLive(station("BBC World Service"))
        connection.addToQueue(listOf(song))
        val state = waitFor("the song to replace the live stream") { it.current?.mediaId == song.id }
        assertEquals(listOf(song.id), state.queue.map { it.mediaId })
    }

    @Test
    fun theQueueIsSavedForTheNextLaunch() {
        val a = libraryItem("Snap A", 30)
        val b = libraryItem("Snap B", 30)
        connect()
        connection.play(listOf(a, b), startIndex = 1, contextTitle = "Snapshot test")
        waitFor("B to play") { it.isPlaying && it.current?.mediaId == b.id }
        connection.pause()
        waitFor("pause") { !it.isPlaying }

        // What the service restores at its next start (resolver keeps everything).
        val deadline = System.currentTimeMillis() + 5_000
        while (true) {
            val restored = runBlocking { container.playbackSnapshots.restore { requests -> requests.mapIndexed { i, r -> i to r } } }
            if (restored != null && restored.items.map { it.mediaId } == listOf(a.id.toString(), b.id.toString())) {
                assertEquals(1, restored.index)
                break
            }
            if (System.currentTimeMillis() > deadline) fail("queue not saved; last: ${restored?.items?.map { it.mediaId }}")
            Thread.sleep(100)
        }
    }

    private fun libraryItem(title: String, seconds: Int): LibraryMediaEntity = runBlocking {
        val file = TestMedia.wav(app, "radio-$title", seconds)
        val now = System.currentTimeMillis()
        val download = DownloadEntity(
            id = -now, sourceUrl = "test://$title", webpageUrl = "test://$title", title = title, thumbnailUrl = null,
            extractorKey = null, formatId = "x", formatKind = FormatKind.AUDIO, resolutionLabel = null, container = "wav",
            videoCodec = null, audioCodec = null, requiresAudioMerge = false, requiresAudioExtraction = false,
            category = DownloadCategory.MUSIC, fileBaseName = title, status = DownloadStatus.COMPLETED,
            createdAt = now, updatedAt = now,
        )
        container.libraryIndexer.onDownloadCompleted(CompletedDownload(download, TestMedia.uriOf(file), file.length(), "wav", "audio/x-wav", null))
        container.libraryRepository.findByUri(TestMedia.uriOf(file))!!.also { created += it.id }
    }
}
