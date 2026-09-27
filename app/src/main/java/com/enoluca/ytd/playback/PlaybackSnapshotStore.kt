package com.enoluca.ytd.playback

import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import com.enoluca.ytd.data.local.db.PlaybackSnapshotDao
import com.enoluca.ytd.data.local.db.PlaybackSnapshotEntity

/** What the player had loaded, captured on the player thread. */
data class QueueSnapshot(
    val mediaIds: List<String>,
    val currentIndex: Int,
    val positionMs: Long,
    val shuffle: Boolean,
    val repeatMode: Int,
    val contextTitle: String?,
    val contextPlaylistId: Long?,
) {
    companion object {
        /** Current player state, or null when nothing is loaded. Live radio has no position to keep. */
        fun of(player: Player): QueueSnapshot? {
            val count = player.mediaItemCount
            if (count == 0) return null
            val current = player.currentMediaItem
            return QueueSnapshot(
                mediaIds = (0 until count).map { player.getMediaItemAt(it).mediaId },
                currentIndex = player.currentMediaItemIndex.coerceIn(0, count - 1),
                positionMs = if (PlaybackItems.isRadio(current)) 0 else player.currentPosition.coerceAtLeast(0),
                shuffle = player.shuffleModeEnabled,
                repeatMode = player.repeatMode,
                contextTitle = PlaybackItems.contextOf(current),
                contextPlaylistId = PlaybackItems.contextPlaylistOf(current),
            )
        }
    }
}

/** Encoding of [QueueSnapshot.mediaIds] in the database (media ids never contain line breaks). */
object QueueSnapshotCodec {
    fun encode(ids: List<String>): String = ids.joinToString("\n")
    fun decode(text: String): List<String> = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * Where to start after some saved items could not be restored: the same item if it survived,
     * else the next surviving one, else the first. [kept] are the surviving original indices.
     */
    fun restoredIndex(savedIndex: Int, kept: List<Int>): Int {
        if (kept.isEmpty()) return 0
        kept.indexOf(savedIndex).takeIf { it >= 0 }?.let { return it }
        return kept.indexOfFirst { it > savedIndex }.takeIf { it >= 0 } ?: 0
    }
}

/**
 * Saves the queue so ENAGELYUCA reopens with the same Now Playing (paused, at the same place),
 * and restores it for the playback service.
 */
class PlaybackSnapshotStore(private val dao: PlaybackSnapshotDao, private val now: () -> Long = System::currentTimeMillis) {

    data class Restored(val items: List<MediaItem>, val index: Int, val positionMs: Long, val shuffle: Boolean, val repeatMode: Int)

    suspend fun save(snapshot: QueueSnapshot?) {
        dao.replace(
            snapshot?.let {
                PlaybackSnapshotEntity(
                    itemIds = QueueSnapshotCodec.encode(it.mediaIds),
                    currentIndex = it.currentIndex,
                    positionMs = it.positionMs,
                    shuffle = it.shuffle,
                    repeatMode = it.repeatMode,
                    contextTitle = it.contextTitle,
                    contextPlaylistId = it.contextPlaylistId,
                    updatedAt = now(),
                )
            },
        )
    }

    /** The saved queue resolved to playable items (missing files / unknown stations dropped), or null. */
    suspend fun restore(resolve: suspend (List<MediaItem>) -> List<Pair<Int, MediaItem>>): Restored? {
        val saved = dao.get() ?: return null
        val ids = QueueSnapshotCodec.decode(saved.itemIds)
        if (ids.isEmpty()) return null
        val requests = ids.map { id -> request(id, saved.contextTitle, saved.contextPlaylistId) }
        val resolved = resolve(requests)
        if (resolved.isEmpty()) return null
        val index = QueueSnapshotCodec.restoredIndex(saved.currentIndex, resolved.map { it.first })
        val sameItem = resolved[index].first == saved.currentIndex
        return Restored(
            items = resolved.map { it.second },
            index = index,
            positionMs = if (sameItem) saved.positionMs else C.TIME_UNSET,
            shuffle = saved.shuffle,
            repeatMode = saved.repeatMode,
        )
    }

    private fun request(mediaId: String, contextTitle: String?, contextPlaylistId: Long?): MediaItem {
        val extras = Bundle().apply {
            contextTitle?.let { putString(PlaybackItems.EXTRA_CONTEXT, it) }
            contextPlaylistId?.let { putLong(PlaybackItems.EXTRA_CONTEXT_PLAYLIST, it) }
        }
        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(MediaMetadata.Builder().setExtras(extras).build())
            .build()
    }
}
