package com.enoluca.ytd.library

import androidx.media3.common.Player
import com.enoluca.ytd.data.local.db.LibraryMediaEntity
import com.enoluca.ytd.data.local.db.MediaOrigin
import com.enoluca.ytd.data.local.db.MediaType
import com.enoluca.ytd.playback.nextRepeatMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistOrderingTest {

    @Test
    fun itemsFromASourcePlaylistKeepTheSourceOrderWhateverOrderTheyCompleteIn() {
        // Downloads of items 1..5 finish in the order 3, 1, 5, 2, 4.
        var order = emptyList<Int>()
        for (sourceIndex in listOf(3, 1, 5, 2, 4)) {
            val at = PlaylistOrdering.insertionIndex(order, sourceIndex)
            order = PlaylistOrdering.insert(order, at, sourceIndex)
        }
        assertEquals(listOf(1, 2, 3, 4, 5), order)
    }

    @Test
    fun itemsAddedByHandGoToTheEnd() {
        assertEquals(3, PlaylistOrdering.insertionIndex(listOf(1, 2, 3), null))
        assertEquals(2, PlaylistOrdering.insertionIndex(listOf(1, null), null))
    }

    @Test
    fun aRetriedItemSlotsBackBetweenItsNeighbours() {
        // 1, 2, 4 downloaded (3 failed); the user then added a song by hand (null).
        val current = listOf(1, 2, 4, null)
        assertEquals(2, PlaylistOrdering.insertionIndex(current, 3))
    }

    @Test
    fun anItemFromLaterInTheSourceGoesAfterHandAddedOnesIfNothingComesLater() {
        assertEquals(3, PlaylistOrdering.insertionIndex(listOf(1, null, 2), 7))
    }

    @Test
    fun moveReordersAndClamps() {
        val items = listOf("A", "B", "C", "D")
        assertEquals(listOf("B", "C", "A", "D"), PlaylistOrdering.move(items, 0, 2))
        assertEquals(listOf("D", "A", "B", "C"), PlaylistOrdering.move(items, 3, 0))
        assertEquals(listOf("A", "B", "D", "C"), PlaylistOrdering.move(items, 2, 99))
        assertEquals(items, PlaylistOrdering.move(items, 7, 0))
        assertEquals(items, PlaylistOrdering.move(items, 1, 1))
    }
}

class ResumePolicyTest {

    private val hour = 60 * 60_000L

    @Test
    fun aMeaningfulPositionIsStored() {
        // 01:42:35 video left at 00:47:12
        val duration = hour + 42 * 60_000L + 35_000
        val position = 47 * 60_000L + 12_000
        assertEquals(position, ResumePolicy.positionToStore(position, duration))
    }

    @Test
    fun theStartAndTheEndAreNotWorthResuming() {
        assertEquals(0, ResumePolicy.positionToStore(4_000, hour))
        assertEquals(0, ResumePolicy.positionToStore(hour - 5_000, hour))
        assertEquals(0, ResumePolicy.positionToStore(hour * 98 / 100, hour))
        assertEquals(30_000, ResumePolicy.positionToStore(30_000, null))
    }

    @Test
    fun videosOfferResumeSongsDont() {
        assertTrue(ResumePolicy.shouldOfferResume(MediaType.VIDEO, 20 * 60_000L, hour))
        assertFalse(ResumePolicy.shouldOfferResume(MediaType.VIDEO, 3_000, hour))
        assertFalse(ResumePolicy.shouldOfferResume(MediaType.AUDIO, 60_000, 4 * 60_000L))
    }

    @Test
    fun longAudioContinuesShortSongsStartOver() {
        assertEquals(25 * 60_000L, ResumePolicy.autoResumePosition(MediaType.AUDIO, 25 * 60_000L, hour))
        assertEquals(0, ResumePolicy.autoResumePosition(MediaType.AUDIO, 90_000, 4 * 60_000L))
        assertEquals(0, ResumePolicy.autoResumePosition(MediaType.VIDEO, 25 * 60_000L, hour))
    }
}

class LibrarySearchAndKindsTest {

    @Test
    fun searchTextIsMatchedLiterally() {
        assertEquals("%love%", LibrarySearch.likePattern("  love "))
        assertEquals("%100\\% hits%", LibrarySearch.likePattern("100% hits"))
        assertEquals("%a\\_b%", LibrarySearch.likePattern("a_b"))
        assertEquals("%c:\\\\x%", LibrarySearch.likePattern("c:\\x"))
    }

    @Test
    fun supportedFormatsAreRecognized() {
        listOf("song.mp3", "song.m4a", "song.aac", "song.opus", "song.flac", "song.ogg", "song.wav").forEach {
            assertEquals(it, MediaType.AUDIO, MediaKinds.typeOf(null, it))
        }
        listOf("clip.mp4", "clip.mkv", "clip.webm", "clip.mov", "clip.3gp").forEach {
            assertEquals(it, MediaType.VIDEO, MediaKinds.typeOf(null, it))
        }
        assertEquals(MediaType.VIDEO, MediaKinds.typeOf("video/x-matroska", "x"))
        assertEquals(MediaType.AUDIO, MediaKinds.typeOf("audio/mpeg", "x.bin"))
        // An unhelpful MIME type falls back to the extension.
        assertEquals(MediaType.AUDIO, MediaKinds.typeOf("application/octet-stream", "track.opus"))
        assertNull(MediaKinds.typeOf("image/jpeg", "cover.jpg"))
        assertNull(MediaKinds.typeOf(null, "subtitles.srt"))
    }

    @Test
    fun sourcePlaylistKeysIdentifyTheSamePlaylist() {
        assertEquals("youtube:PL123", SourcePlaylistKey.of("youtube", "PL123", "https://youtube.com/playlist?list=PL123"))
        assertEquals("youtube:https://x", SourcePlaylistKey.of("youtube", null, "https://x"))
    }
}

class LibraryFilterTest {

    private fun media(id: Long, type: MediaType, favorite: Boolean = false, lastPlayed: Long? = null) = LibraryMediaEntity(
        id = id, uri = "content://x/$id", fileName = null, title = "T$id", artist = null, album = null,
        thumbnailUrl = null, mediaType = type, mimeType = null, durationMs = null, sizeBytes = null,
        dateAdded = 1000 - id, dateModified = null, sourceUrl = null, sourcePlatform = null, downloadId = null,
        origin = MediaOrigin.DOWNLOAD, location = null, favorite = favorite, lastPlayedAt = lastPlayed,
    )

    private val all = listOf(
        media(1, MediaType.AUDIO, favorite = true, lastPlayed = 50),
        media(2, MediaType.VIDEO, lastPlayed = 90),
        media(3, MediaType.AUDIO),
        media(4, MediaType.VIDEO, favorite = true),
    )

    @Test
    fun filters() {
        assertEquals(listOf(1L, 2L, 3L, 4L), LibraryFilter.ALL.apply(all).map { it.id })
        assertEquals(listOf(1L, 3L), LibraryFilter.MUSIC.apply(all).map { it.id })
        assertEquals(listOf(2L, 4L), LibraryFilter.VIDEOS.apply(all).map { it.id })
        assertEquals(listOf(1L, 4L), LibraryFilter.FAVORITES.apply(all).map { it.id })
        // Most recently played first.
        assertEquals(listOf(2L, 1L), LibraryFilter.RECENTLY_PLAYED.apply(all).map { it.id })
    }

    @Test
    fun recentlyAddedIsNewestFirstWithoutMissingFilesAndCapped() {
        val shuffled = listOf(all[2], all[0], all[3].copy(missingSince = 5), all[1])
        assertEquals(listOf(1L, 2L, 3L), LibraryFilter.RECENTLY_ADDED.apply(shuffled).map { it.id })
        val many = (1L..80L).map { media(it, MediaType.AUDIO) }
        assertEquals(RECENTLY_ADDED_LIMIT, LibraryFilter.RECENTLY_ADDED.apply(many).size)
    }
}

class RepeatModeTest {
    @Test
    fun repeatCyclesOffAllOne() {
        assertEquals(Player.REPEAT_MODE_ALL, nextRepeatMode(Player.REPEAT_MODE_OFF))
        assertEquals(Player.REPEAT_MODE_ONE, nextRepeatMode(Player.REPEAT_MODE_ALL))
        assertEquals(Player.REPEAT_MODE_OFF, nextRepeatMode(Player.REPEAT_MODE_ONE))
    }
}
