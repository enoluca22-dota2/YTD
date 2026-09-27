package com.enoluca.ytd.library

import com.enoluca.ytd.data.local.db.LibraryMediaEntity
import com.enoluca.ytd.data.local.db.MediaType
import java.util.Locale

/** Order inside a playlist. Pure functions, so the rules are unit-tested without a database. */
object PlaylistOrdering {

    /**
     * Where an item that came from a source playlist (1-based [newSourceIndex]) goes, given the
     * playlist's current items in their current order ([existingSourceIndices], null for items
     * the user added by hand). Downloads finish in any order; this keeps the source order: the new
     * item goes right before the first item that comes later in the source playlist. Items
     * without a source index (and a null [newSourceIndex]) go to the end.
     */
    fun insertionIndex(existingSourceIndices: List<Int?>, newSourceIndex: Int?): Int {
        if (newSourceIndex == null) return existingSourceIndices.size
        val before = existingSourceIndices.indexOfFirst { it != null && it > newSourceIndex }
        return if (before >= 0) before else existingSourceIndices.size
    }

    /** [items] with the element at [from] moved to [to] (both clamped to the list). */
    fun <T> move(items: List<T>, from: Int, to: Int): List<T> {
        if (from !in items.indices) return items
        val target = to.coerceIn(0, items.lastIndex)
        if (from == target) return items
        val result = items.toMutableList()
        val moved = result.removeAt(from)
        result.add(target, moved)
        return result
    }

    /** [items] with [newItem] inserted at [index] (clamped). */
    fun <T> insert(items: List<T>, index: Int, newItem: T): List<T> =
        items.toMutableList().apply { add(index.coerceIn(0, size), newItem) }
}

/** When a playback position is worth remembering, and when to offer it back. */
object ResumePolicy {
    /** Positions this close to the start aren't worth resuming. */
    const val MIN_RESUME_MS = 10_000L

    /** Closer than this to the end counts as finished (credits, outro). */
    private const val END_MARGIN_MS = 15_000L

    /** Music shorter than this always starts from the beginning (it's a song, not a mix/podcast). */
    const val LONG_AUDIO_MS = 10 * 60_000L

    /** The position to store when playback of an item stops at [positionMs]; 0 = start over next time. */
    fun positionToStore(positionMs: Long, durationMs: Long?): Long {
        if (positionMs < MIN_RESUME_MS) return 0
        if (durationMs != null && durationMs > 0) {
            if (positionMs >= durationMs - END_MARGIN_MS || positionMs >= durationMs * 97 / 100) return 0
        }
        return positionMs
    }

    /** Videos: ask "Resume / Start over" when a meaningful position is stored. */
    fun shouldOfferResume(type: MediaType, resumeMs: Long, durationMs: Long?): Boolean = when (type) {
        MediaType.VIDEO -> positionToStore(resumeMs, durationMs) > 0
        MediaType.AUDIO -> false
    }

    /** Music: long recordings (mixes, podcasts, audiobooks) quietly continue where they stopped. */
    fun autoResumePosition(type: MediaType, resumeMs: Long, durationMs: Long?): Long = when (type) {
        MediaType.AUDIO -> if ((durationMs ?: 0) >= LONG_AUDIO_MS) positionToStore(resumeMs, durationMs) else 0
        MediaType.VIDEO -> 0
    }
}

/** Search text → SQL LIKE pattern. */
object LibrarySearch {
    /** "%100\% hits%" for "100% hits": the user's own % and _ match literally (ESCAPE '\'). */
    fun likePattern(query: String): String {
        val escaped = query.trim()
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
        return "%$escaped%"
    }
}

/** Which files the Library indexes, and as what. */
object MediaKinds {
    private val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "mkv", "webm", "mov", "3gp", "ts", "avi", "flv")
    private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "opus", "ogg", "oga", "flac", "wav", "wma", "amr", "mka", "weba")

    /** AUDIO / VIDEO for a playable file, null for anything else (images, subtitles, documents…). */
    fun typeOf(mimeType: String?, fileName: String?): MediaType? {
        val mime = mimeType?.lowercase(Locale.ROOT)
        when {
            mime == null || mime == "application/octet-stream" -> Unit
            mime.startsWith("video/") -> return MediaType.VIDEO
            mime.startsWith("audio/") -> return MediaType.AUDIO
            mime == "application/ogg" -> return MediaType.AUDIO
        }
        val extension = fileName?.substringAfterLast('.', "")?.lowercase(Locale.ROOT).orEmpty()
        return when (extension) {
            in VIDEO_EXTENSIONS -> MediaType.VIDEO
            in AUDIO_EXTENSIONS -> MediaType.AUDIO
            else -> null
        }
    }
}

/** Identity of a source playlist, so downloading the same playlist again fills the same Library playlist. */
object SourcePlaylistKey {
    fun of(platformId: String, playlistId: String?, sourceUrl: String): String =
        "$platformId:" + (playlistId?.takeIf { it.isNotBlank() } ?: sourceUrl)
}

/** How many items "Recently added" lists. */
const val RECENTLY_ADDED_LIMIT = 50

/** The Library's filter chips. */
enum class LibraryFilter(val label: String) {
    ALL("All"),
    MUSIC("Music"),
    VIDEOS("Videos"),
    PLAYLISTS("Playlists"),
    FAVORITES("Favorites"),
    RECENTLY_ADDED("Recently added"),
    RECENTLY_PLAYED("Recently played"),
    ;

    /** The media this filter lists, from all media (newest first). Playlists are listed separately. */
    fun apply(media: List<LibraryMediaEntity>): List<LibraryMediaEntity> = when (this) {
        ALL, PLAYLISTS -> media
        MUSIC -> media.filter { it.mediaType == MediaType.AUDIO }
        VIDEOS -> media.filter { it.mediaType == MediaType.VIDEO }
        FAVORITES -> media.filter { it.favorite }
        RECENTLY_ADDED -> media.filter { it.isAvailable }.sortedByDescending { it.dateAdded }.take(RECENTLY_ADDED_LIMIT)
        RECENTLY_PLAYED -> media.filter { it.lastPlayedAt != null }.sortedByDescending { it.lastPlayedAt }
    }
}
