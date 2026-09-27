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
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End to end through the real PlaybackService (MediaSessionService + ExoPlayer) and the app's
 * PlayerConnection (MediaController): the UI sends Library ids, the service resolves them to the
 * files and plays; state flows back to the UI.
 */
@RunWith(AndroidJUnit4::class)
class PlaybackServiceTest {

    private val app: YtdApplication = ApplicationProvider.getApplicationContext()
    private val container = app.container
    private val connection = container.playerConnection
    private val created = mutableListOf<Long>()

    @After
    fun tearDown() = runBlocking {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            connection.clearQueue()
            connection.disconnect()
        }
        container.libraryRepository.forget(created)
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

    private fun libraryItem(title: String, seconds: Int): LibraryMediaEntity = runBlocking {
        val file = TestMedia.wav(app, "svc-$title", seconds)
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

    @Test
    fun theUiPlaysLibraryItemsThroughThePlaybackService() {
        val a = libraryItem("Service A", 20)
        val b = libraryItem("Service B", 20)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { connection.connect() }
        waitFor("the controller to connect") { it.connected }

        connection.play(listOf(a, b), startIndex = 0, contextTitle = "Service test")
        val playing = waitFor("A to play") { it.isPlaying && it.current?.mediaId == a.id }
        assertEquals("Service test", playing.contextTitle)
        assertEquals(listOf(a.id, b.id), playing.queue.map { it.mediaId })
        assertEquals("Service A", playing.current?.title)

        connection.next()
        waitFor("B to play") { it.isPlaying && it.current?.mediaId == b.id }

        connection.pause()
        waitFor("pause") { !it.isPlaying && !it.playWhenReady }

        connection.play()
        waitFor("resume") { it.isPlaying }

        connection.clearQueue()
        waitFor("an empty queue") { !it.hasMedia }
    }
}
