package com.enoluca.ytd.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import com.enoluca.ytd.data.local.db.LibraryMediaEntity
import com.enoluca.ytd.data.local.db.MediaType
import com.enoluca.ytd.radio.RadioStation
import java.io.File

/**
 * Library item / radio station ⇄ Media3 [MediaItem]. The media id is the Library id ("42") or
 * "radio:<station id>"; the URI is only filled in by the playback service (from the database and
 * the radio catalog), so a controller can only ever play Library items and known stations.
 */
object PlaybackItems {

    /** Name of the playlist/list the item is played from ("Workout"), shown by the player. */
    const val EXTRA_CONTEXT = "enagelyuca.context"
    const val EXTRA_IS_VIDEO = "enagelyuca.video"

    /** Library playlist the item is played from, if any ("Remove from this playlist"). */
    const val EXTRA_CONTEXT_PLAYLIST = "enagelyuca.playlist"

    const val EXTRA_IS_RADIO = "enagelyuca.radio"
    const val EXTRA_RADIO_GENRE = "enagelyuca.radio.genre"
    private const val RADIO_PREFIX = "radio:"

    fun libraryId(item: MediaItem?): Long? = item?.mediaId?.toLongOrNull()

    fun radioId(item: MediaItem?): String? = radioIdOf(item?.mediaId)
    fun radioIdOf(mediaId: String?): String? = mediaId?.takeIf { it.startsWith(RADIO_PREFIX) }?.removePrefix(RADIO_PREFIX)?.ifEmpty { null }
    fun radioMediaId(stationId: String): String = RADIO_PREFIX + stationId
    fun isRadio(item: MediaItem?): Boolean = radioId(item) != null

    fun contextOf(item: MediaItem?): String? = item?.mediaMetadata?.extras?.getString(EXTRA_CONTEXT)

    fun contextPlaylistOf(item: MediaItem?): Long? =
        item?.mediaMetadata?.extras?.getLong(EXTRA_CONTEXT_PLAYLIST, -1L)?.takeIf { it > 0 }

    fun isVideo(item: MediaItem?): Boolean = item?.mediaMetadata?.extras?.getBoolean(EXTRA_IS_VIDEO) ?: false

    /** What the UI sends: id + metadata (so the queue shows right away), no URI. */
    fun request(media: LibraryMediaEntity, contextTitle: String?, contextPlaylistId: Long? = null): MediaItem =
        MediaItem.Builder()
            .setMediaId(media.id.toString())
            .setMediaMetadata(metadata(media, contextTitle, contextPlaylistId))
            .build()

    /** What the service plays: the request resolved against the Library. */
    fun playable(media: LibraryMediaEntity, contextTitle: String?, contextPlaylistId: Long? = null): MediaItem =
        MediaItem.Builder()
            .setMediaId(media.id.toString())
            .setUri(Uri.parse(media.uri))
            .setMediaMetadata(metadata(media, contextTitle, contextPlaylistId))
            .build()

    private fun metadata(media: LibraryMediaEntity, contextTitle: String?, contextPlaylistId: Long?): MediaMetadata {
        val extras = Bundle().apply {
            contextTitle?.let { putString(EXTRA_CONTEXT, it) }
            contextPlaylistId?.let { putLong(EXTRA_CONTEXT_PLAYLIST, it) }
            putBoolean(EXTRA_IS_VIDEO, media.mediaType == MediaType.VIDEO)
        }
        return MediaMetadata.Builder()
            .setTitle(media.title)
            .setArtist(media.artist ?: media.sourcePlatform)
            .setAlbumTitle(media.album ?: contextTitle)
            .setArtworkUri(artworkUri(media))
            .setDurationMs(media.durationMs)
            .setMediaType(if (media.mediaType == MediaType.VIDEO) MediaMetadata.MEDIA_TYPE_VIDEO else MediaMetadata.MEDIA_TYPE_MUSIC)
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .setExtras(extras)
            .build()
    }

    /** What the UI sends to play a station: id + what to show, no stream URL. */
    fun radioRequest(station: RadioStation): MediaItem =
        MediaItem.Builder()
            .setMediaId(radioMediaId(station.id))
            .setMediaMetadata(radioMetadata(station))
            .build()

    /** What the service plays: the station with its current stream URL. */
    fun radioPlayable(station: RadioStation): MediaItem =
        MediaItem.Builder()
            .setMediaId(radioMediaId(station.id))
            .setUri(Uri.parse(station.streamUrl))
            // HLS stations need the HLS extractor even when the URL doesn't end in .m3u8.
            .apply { if (station.isHls || station.streamUrl.contains(".m3u8", ignoreCase = true)) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .setMediaMetadata(radioMetadata(station))
            .build()

    private fun radioMetadata(station: RadioStation): MediaMetadata = MediaMetadata.Builder()
        .setTitle(station.name)
        .setStation(station.name)
        .setArtist(station.subtitle)
        .setGenre(station.genre)
        .setArtworkUri(station.logoUrl?.let(Uri::parse))
        .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
        .setIsPlayable(true)
        .setIsBrowsable(false)
        .setExtras(
            Bundle().apply {
                putBoolean(EXTRA_IS_RADIO, true)
                putString(EXTRA_RADIO_GENRE, station.genre)
                putString(EXTRA_CONTEXT, station.countryName)
            },
        )
        .build()

    /** The cached local artwork (fast, offline); the remote thumbnail until it exists. */
    private fun artworkUri(media: LibraryMediaEntity): Uri? {
        media.artworkPath?.takeIf { it.isNotEmpty() }?.let { path -> return Uri.fromFile(File(path)) }
        return media.thumbnailUrl?.takeIf { it.startsWith("https://") }?.let(Uri::parse)
    }
}
