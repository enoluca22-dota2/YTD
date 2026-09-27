package com.enoluca.ytd.playback

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.enoluca.ytd.data.local.db.AppDatabase
import com.enoluca.ytd.data.local.db.LibraryMediaEntity
import com.enoluca.ytd.data.local.db.MediaOrigin
import com.enoluca.ytd.data.local.db.MediaType
import com.enoluca.ytd.library.ArtworkCache
import com.enoluca.ytd.library.LibraryRepository
import com.enoluca.ytd.library.TestMedia
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import com.enoluca.ytd.radio.RadioBrowserDirectory
import com.enoluca.ytd.radio.RadioCatalog
import com.enoluca.ytd.radio.RadioRepository
import java.util.Collections

/**
 * The playback engine the service uses (ExoPlayer) with ENAGELYUCA's queue items and
 * [PlaybackTracker], playing real files: queue order, auto-advance, shuffle/repeat, resume
 * positions and play counts written back to the Library.
 */
@RunWith(AndroidJUnit4::class)
class PlaybackEngineTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var library: LibraryRepository
    private lateinit var mainScope: CoroutineScope
    private lateinit var ioScope: CoroutineScope
    private lateinit var player: ExoPlayer
    private lateinit var tracker: PlaybackTracker

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        library = LibraryRepository(context, db, ArtworkCache(context))
        mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        onMain {
            player = ExoPlayer.Builder(context).build()
            val radio = RadioRepository(
                object : RadioCatalog { override fun stations() = emptyList<com.enoluca.ytd.radio.RadioStation>() },
                RadioBrowserDirectory(),
                db.radioDao(),
            )
            tracker = PlaybackTracker(player, library, radio, PlaybackSnapshotStore(db.playbackSnapshotDao()), mainScope, ioScope)
            player.addListener(tracker)
        }
    }

    @After
    fun tearDown() {
        onMain {
            tracker.release()
            player.release()
        }
        mainScope.cancel()
        ioScope.cancel()
        db.close()
        TestMedia.cleanUp(context)
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Any? = null
        instrumentation.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun waitUntil(what: String, timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("Timed out waiting for: $what")
            Thread.sleep(50)
        }
    }

    private fun media(title: String, seconds: Int): Pair<LibraryMediaEntity, MediaItem> = runBlocking {
        val file = TestMedia.wav(context, title, seconds)
        val entity = LibraryMediaEntity(
            uri = TestMedia.uriOf(file), fileName = file.name, title = title, artist = "Tester", album = null,
            thumbnailUrl = null, mediaType = MediaType.AUDIO, mimeType = "audio/x-wav", durationMs = seconds * 1000L,
            sizeBytes = file.length(), dateAdded = System.currentTimeMillis(), dateModified = null, sourceUrl = null,
            sourcePlatform = null, downloadId = null, origin = MediaOrigin.DOWNLOAD, location = null,
        )
        val id = db.libraryDao().insertMedia(entity)
        val saved = entity.copy(id = id)
        saved to PlaybackItems.playable(saved, "Test playlist")
    }

    private fun queueIds(): List<Long?> = onMain { (0 until player.mediaItemCount).map { PlaybackItems.libraryId(player.getMediaItemAt(it)) } }

    private fun stored(id: Long): LibraryMediaEntity = runBlocking { library.getMedia(id)!! }

    @Test
    fun aPlaylistPlaysItemAfterItemInItsOrder() {
        val (a, itemA) = media("A", 1)
        val (b, itemB) = media("B", 1)
        val (c, itemC) = media("C", 1)
        val played = Collections.synchronizedList(mutableListOf<Long?>())
        onMain {
            player.addListener(object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    played += PlaybackItems.libraryId(mediaItem)
                }
            })
            player.setMediaItems(listOf(itemA, itemB, itemC))
            player.prepare()
            player.play()
        }
        waitUntil("the playlist to finish") { onMain { player.playbackState == Player.STATE_ENDED } }
        assertEquals(listOf(a.id, b.id, c.id), played.toList())
        assertEquals("Test playlist", onMain { PlaybackItems.contextOf(player.currentMediaItem) })
        // Every item counts as played; finished items start over next time.
        waitUntil("play counts") { listOf(a, b, c).all { stored(it.id).playCount == 1 } }
        listOf(a, b, c).forEach { assertEquals(0L, stored(it.id).resumePositionMs) }
    }

    @Test
    fun theQueueCanBeReorderedAndShuffleAndRepeatChangeState() {
        val (a, itemA) = media("QA", 1)
        val (b, itemB) = media("QB", 1)
        val (c, itemC) = media("QC", 1)
        onMain { player.setMediaItems(listOf(itemA, itemB, itemC)) }
        assertEquals(listOf(a.id, b.id, c.id), queueIds())

        onMain { player.moveMediaItem(2, 0) } // "play C first"
        assertEquals(listOf(c.id, a.id, b.id), queueIds())
        onMain { player.removeMediaItem(1) } // remove A from the queue
        assertEquals(listOf(c.id, b.id), queueIds())
        onMain { player.addMediaItem(1, itemA) } // "play next" after C
        assertEquals(listOf(c.id, a.id, b.id), queueIds())

        onMain { player.shuffleModeEnabled = true }
        val shuffled = onMain {
            val t = player.currentTimeline
            generateSequence(t.getFirstWindowIndex(true)) { i -> t.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, true).takeIf { it != C.INDEX_UNSET } }.toList()
        }
        assertEquals(setOf(0, 1, 2), shuffled.toSet()) // every item still plays exactly once
        assertEquals(3, shuffled.size)
        onMain { player.shuffleModeEnabled = false }

        var mode = Player.REPEAT_MODE_OFF
        val seen = mutableListOf<Int>()
        repeat(3) {
            mode = nextRepeatMode(mode)
            onMain { player.repeatMode = mode }
            seen += onMain { player.repeatMode }
        }
        assertEquals(listOf(Player.REPEAT_MODE_ALL, Player.REPEAT_MODE_ONE, Player.REPEAT_MODE_OFF), seen)
    }

    @Test
    fun thePositionIsRememberedWhenPausedAndClearedWhenFinished() {
        val (a, itemA) = media("Resume", 60)
        onMain {
            player.setMediaItem(itemA)
            player.prepare()
            player.seekTo(15_000)
            player.play()
        }
        waitUntil("playback past 15.5 s") { onMain { player.isPlaying && player.currentPosition > 15_500 } }
        onMain { player.pause() }
        waitUntil("the stored position") { stored(a.id).resumePositionMs in 15_000..20_000 }

        onMain {
            player.seekTo(59_000)
            player.play()
        }
        waitUntil("the end") { onMain { player.playbackState == Player.STATE_ENDED } }
        waitUntil("the position reset") { stored(a.id).resumePositionMs == 0L }
    }

    @Test
    fun skippingRemembersWhereTheSkippedItemStopped() {
        val (a, itemA) = media("SkipA", 60)
        val (_, itemB) = media("SkipB", 60)
        onMain {
            player.setMediaItems(listOf(itemA, itemB), 0, 12_000)
            player.prepare()
            player.play()
        }
        waitUntil("playback past 12.2 s") { onMain { player.isPlaying && player.currentPosition > 12_200 } }
        onMain { player.seekToNextMediaItem() }
        waitUntil("A's stored position") { stored(a.id).resumePositionMs in 12_000..16_000 }
        assertTrue(onMain { PlaybackItems.libraryId(player.currentMediaItem) } != a.id)
    }
}
