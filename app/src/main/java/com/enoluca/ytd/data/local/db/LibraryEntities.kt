package com.enoluca.ytd.data.local.db

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** What a Library item plays as. */
enum class MediaType { AUDIO, VIDEO }

/** How an item got into the Library. */
enum class MediaOrigin {
    /** Downloaded by ENAGELYUCA (linked to its download job). */
    DOWNLOAD,

    /** Found in Android's MediaStore (ENAGELYUCA's folders, or all media if the user allowed it). */
    DEVICE,

    /** Found in a folder the user added to the Library (Storage Access Framework). */
    FOLDER,
}

/**
 * One playable file on the device. The file itself stays where it is; this row only describes it
 * (never any media bytes). A row whose file has disappeared keeps [missingSince] set until the
 * file comes back or the Library forgets it.
 */
@Entity(
    tableName = "library_media",
    indices = [Index(value = ["uri"], unique = true), Index("downloadId"), Index("mediaType")],
)
data class LibraryMediaEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** content:// URI of the file (MediaStore, SAF document or FileProvider). */
    val uri: String,
    val fileName: String?,
    val title: String,
    val artist: String?,
    val album: String?,
    /** Remote thumbnail from the download (YouTube/TikTok), if known. */
    val thumbnailUrl: String?,
    /** Local, downscaled artwork in the app's files dir; "" = looked for, none exists. */
    val artworkPath: String? = null,
    val mediaType: MediaType,
    val mimeType: String?,
    val durationMs: Long?,
    val sizeBytes: Long?,
    val dateAdded: Long,
    val dateModified: Long?,
    val sourceUrl: String?,
    /** "YouTube", "TikTok"… (display name), or null for files that weren't downloaded here. */
    val sourcePlatform: String?,
    val downloadId: Long?,
    val origin: MediaOrigin,
    /** Human-readable folder ("Music/ENAGELYUCA"). */
    val location: String?,
    @ColumnInfo(defaultValue = "0") val favorite: Boolean = false,
    @ColumnInfo(defaultValue = "0") val playCount: Int = 0,
    val lastPlayedAt: Long? = null,
    @ColumnInfo(defaultValue = "0") val resumePositionMs: Long = 0,
    /** When the file was first found missing; null while it exists. */
    val missingSince: Long? = null,
) {
    val isAvailable: Boolean get() = missingSince == null

    /** What to show as artwork: the cached local file, else the remote thumbnail. */
    val artworkModel: String?
        get() = artworkPath?.takeIf { it.isNotEmpty() } ?: thumbnailUrl
}

/** A Library playlist: created by the user, or automatically from a downloaded YouTube playlist. */
@Entity(tableName = "library_playlists", indices = [Index(value = ["sourceKey"], unique = true)])
data class LibraryPlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val thumbnailUrl: String?,
    val sourceUrl: String?,
    /** Identifies the source playlist ("youtube:PL…"), so downloading it again fills the same playlist. */
    val sourceKey: String?,
    val sourcePlatform: String?,
    val createdAt: Long,
    val updatedAt: Long,
    @ColumnInfo(defaultValue = "0") val favorite: Boolean = false,
)

/** Membership + order. Removing a row never touches the media file. */
@Entity(
    tableName = "library_playlist_items",
    primaryKeys = ["playlistId", "mediaId"],
    foreignKeys = [
        ForeignKey(LibraryPlaylistEntity::class, ["id"], ["playlistId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(LibraryMediaEntity::class, ["id"], ["mediaId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("mediaId")],
)
data class PlaylistItemEntity(
    val playlistId: Long,
    val mediaId: Long,
    /** 0-based order inside the playlist. */
    val position: Int,
    /** 1-based position in the source (YouTube) playlist, for items that came from it. */
    val sourceIndex: Int?,
    val addedAt: Long,
)

/** Download jobs queued from a source playlist (their batchId) → the Library playlist they fill. */
@Entity(
    tableName = "library_playlist_batches",
    foreignKeys = [ForeignKey(LibraryPlaylistEntity::class, ["id"], ["playlistId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("playlistId")],
)
data class PlaylistBatchEntity(
    @PrimaryKey val batchId: String,
    val playlistId: Long,
)

/** A playlist with what its card shows. Counts and durations only include files that exist. */
data class PlaylistSummary(
    @Embedded val playlist: LibraryPlaylistEntity,
    val itemCount: Int,
    val totalDurationMs: Long,
    /** Artwork of the first available item, used when the playlist has no thumbnail of its own. */
    val firstArtwork: String?,
) {
    val coverModel: String? get() = playlist.thumbnailUrl ?: firstArtwork
}

/** A media row inside a playlist, with its position. */
data class PlaylistTrack(
    @Embedded val media: LibraryMediaEntity,
    val position: Int,
    val sourceIndex: Int?,
)

data class LibraryStats(val count: Int, val totalBytes: Long)
