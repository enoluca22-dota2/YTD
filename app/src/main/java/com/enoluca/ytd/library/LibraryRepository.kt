package com.enoluca.ytd.library

import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.room.withTransaction
import com.enoluca.ytd.core.FileActions
import com.enoluca.ytd.data.local.db.AppDatabase
import com.enoluca.ytd.data.local.db.LibraryMediaEntity
import com.enoluca.ytd.data.local.db.LibraryPlaylistEntity
import com.enoluca.ytd.data.local.db.LibraryStats
import com.enoluca.ytd.data.local.db.PlaylistBatchEntity
import com.enoluca.ytd.data.local.db.PlaylistItemEntity
import com.enoluca.ytd.data.local.db.PlaylistSummary
import com.enoluca.ytd.data.local.db.PlaylistTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * The Library: media that exists on the device, playlists, favorites and playback bookkeeping.
 * It only ever reads/deletes files through their URIs; it never depends on the downloader or the
 * player (they depend on it).
 */
class LibraryRepository(
    private val context: Context,
    private val db: AppDatabase,
    private val artwork: ArtworkCache,
) {
    private val dao = db.libraryDao()

    // --- Observing ----------------------------------------------------------------------------

    fun observeAllMedia(): Flow<List<LibraryMediaEntity>> = dao.observeAllMedia()
    fun search(query: String): Flow<List<LibraryMediaEntity>> = dao.searchMedia(LibrarySearch.likePattern(query))
    fun observeStats(): Flow<LibraryStats> = dao.observeStats()
    fun observeDownloadedStats(): Flow<LibraryStats> = dao.observeDownloadedStats()
    fun observePlaylists(): Flow<List<PlaylistSummary>> = dao.observePlaylistSummaries()
    fun observePlaylist(id: Long): Flow<LibraryPlaylistEntity?> = dao.observePlaylist(id)
    fun observePlaylistTracks(id: Long): Flow<List<PlaylistTrack>> = dao.observePlaylistTracks(id)
    fun observeMedia(id: Long): Flow<LibraryMediaEntity?> = dao.observeMedia(id)

    suspend fun getMedia(id: Long): LibraryMediaEntity? = dao.getMedia(id)

    /** In the order of [ids]; unknown ids are skipped. */
    suspend fun getMedia(ids: List<Long>): List<LibraryMediaEntity> {
        val byId = ids.chunked(500).flatMap { dao.getMedia(it) }.associateBy { it.id }
        return ids.mapNotNull { byId[it] }
    }

    suspend fun findByUri(uri: String): LibraryMediaEntity? = dao.getMediaByUri(uri)
    suspend fun getPlaylistTracks(playlistId: Long): List<PlaylistTrack> = dao.getPlaylistTracks(playlistId)
    suspend fun getPlaylist(id: Long): LibraryPlaylistEntity? = dao.getPlaylist(id)
    suspend fun lastPlayed(): LibraryMediaEntity? = dao.getLastPlayed()

    // --- Playlists ----------------------------------------------------------------------------

    suspend fun createPlaylist(title: String): Long {
        val now = System.currentTimeMillis()
        return dao.insertPlaylist(
            LibraryPlaylistEntity(
                title = title.trim().ifEmpty { "New playlist" },
                thumbnailUrl = null,
                sourceUrl = null,
                sourceKey = null,
                sourcePlatform = null,
                createdAt = now,
                updatedAt = now,
            )
        )
    }

    /** A new playlist already holding [mediaIds] (in order), created atomically: never half-filled. Returns (id, added). */
    suspend fun createPlaylistWith(title: String, mediaIds: List<Long>): Pair<Long, Int> = db.withTransaction {
        val id = createPlaylist(title)
        id to addToPlaylist(id, mediaIds)
    }

    suspend fun renamePlaylist(id: Long, title: String) {
        val playlist = dao.getPlaylist(id) ?: return
        val clean = title.trim().takeIf { it.isNotEmpty() } ?: return
        dao.updatePlaylist(playlist.copy(title = clean, updatedAt = System.currentTimeMillis()))
    }

    suspend fun setPlaylistFavorite(id: Long, favorite: Boolean) {
        val playlist = dao.getPlaylist(id) ?: return
        dao.updatePlaylist(playlist.copy(favorite = favorite))
    }

    /**
     * Deletes the playlist. Its media stays in the Library unless [deleteMedia] is true — then
     * the files themselves are deleted too (the user explicitly chose "Delete playlist and media").
     */
    suspend fun deletePlaylist(id: Long, deleteMedia: Boolean): DeleteResult? {
        val mediaIds = if (deleteMedia) dao.getPlaylistTracks(id).map { it.media.id } else emptyList()
        dao.deletePlaylist(id)
        return if (deleteMedia && mediaIds.isNotEmpty()) deleteMedia(mediaIds) else null
    }

    /** Appends [mediaIds] (in that order) to the end; items already in the playlist are skipped. Returns how many were added. */
    suspend fun addToPlaylist(playlistId: Long, mediaIds: List<Long>): Int = db.withTransaction {
        if (dao.getPlaylist(playlistId) == null) return@withTransaction 0
        val existing = dao.getPlaylistItems(playlistId)
        val present = existing.map { it.mediaId }.toHashSet()
        var next = (existing.maxOfOrNull { it.position } ?: -1) + 1
        val now = System.currentTimeMillis()
        var added = 0
        mediaIds.distinct().filter { it !in present }.forEach { mediaId ->
            if (dao.insertPlaylistItem(PlaylistItemEntity(playlistId, mediaId, next, sourceIndex = null, addedAt = now)) != -1L) {
                next++
                added++
            }
        }
        if (added > 0) dao.touchPlaylist(playlistId, now)
        added
    }

    /**
     * Adds an item that belongs to a source playlist at its place in the source order (downloads
     * complete in any order; retried items slot back in where they belong).
     */
    suspend fun addFromSource(playlistId: Long, mediaId: Long, sourceIndex: Int?) = db.withTransaction {
        if (dao.getPlaylist(playlistId) == null) return@withTransaction
        val items = dao.getPlaylistItems(playlistId)
        if (items.any { it.mediaId == mediaId }) return@withTransaction
        val index = PlaylistOrdering.insertionIndex(items.map { it.sourceIndex }, sourceIndex)
        val now = System.currentTimeMillis()
        dao.insertPlaylistItem(PlaylistItemEntity(playlistId, mediaId, position = items.size, sourceIndex = sourceIndex, addedAt = now))
        val order = PlaylistOrdering.insert(items.map { it.mediaId }, index, mediaId)
        dao.renumberPlaylist(playlistId, order)
        dao.touchPlaylist(playlistId, now)
    }

    /** Removes the item from the playlist only; the media file and its Library entry stay. */
    suspend fun removeFromPlaylist(playlistId: Long, mediaId: Long) = db.withTransaction {
        dao.deletePlaylistItem(playlistId, mediaId)
        dao.renumberPlaylist(playlistId, dao.getPlaylistItems(playlistId).map { it.mediaId })
        dao.touchPlaylist(playlistId, System.currentTimeMillis())
    }

    suspend fun movePlaylistItem(playlistId: Long, from: Int, to: Int) = db.withTransaction {
        val order = dao.getPlaylistItems(playlistId).map { it.mediaId }
        dao.renumberPlaylist(playlistId, PlaylistOrdering.move(order, from, to))
    }

    /**
     * Links the download jobs of one batch ([batchId]) to the Library playlist for that source
     * playlist, creating it the first time. Downloading the same playlist again reuses it.
     */
    suspend fun linkBatchToSourcePlaylist(
        batchId: String,
        sourceKey: String,
        title: String,
        thumbnailUrl: String?,
        sourceUrl: String,
        sourcePlatform: String?,
    ): Long = db.withTransaction {
        val now = System.currentTimeMillis()
        val existing = dao.getPlaylistBySourceKey(sourceKey)
        val playlistId = existing?.id ?: dao.insertPlaylist(
            LibraryPlaylistEntity(
                title = title.ifBlank { "Playlist" },
                thumbnailUrl = thumbnailUrl,
                sourceUrl = sourceUrl,
                sourceKey = sourceKey,
                sourcePlatform = sourcePlatform,
                createdAt = now,
                updatedAt = now,
            )
        )
        if (existing != null && existing.thumbnailUrl == null && thumbnailUrl != null) {
            dao.updatePlaylist(existing.copy(thumbnailUrl = thumbnailUrl))
        }
        dao.insertBatch(PlaylistBatchEntity(batchId, playlistId))
        playlistId
    }

    suspend fun playlistForBatch(batchId: String): Long? = dao.getPlaylistIdForBatch(batchId)

    // --- Media --------------------------------------------------------------------------------

    suspend fun setFavorite(ids: List<Long>, favorite: Boolean) = dao.setFavorite(ids, favorite)

    sealed interface DeleteResult {
        data class Done(val deleted: Int, val failed: Int) : DeleteResult

        /**
         * Some files belong to other apps: Android must ask the user first. Launch [intentSender];
         * if the user agrees, call [confirmDeleted] with [pendingIds].
         */
        data class NeedsConsent(val intentSender: IntentSender, val pendingIds: List<Long>, val deleted: Int) : DeleteResult
    }

    /** Deletes the files and their Library entries (and thus their playlist memberships). */
    suspend fun deleteMedia(ids: List<Long>): DeleteResult = withContext(Dispatchers.IO) {
        val items = getMedia(ids)
        var deleted = 0
        val needConsent = mutableListOf<LibraryMediaEntity>()
        val failed = mutableListOf<LibraryMediaEntity>()
        for (item in items) {
            when {
                !item.isAvailable || FileActions.delete(context, item.uri) || !MediaFiles.exists(context, item.uri) -> {
                    forget(listOf(item.id))
                    deleted++
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Uri.parse(item.uri).authority == MediaStore.AUTHORITY ->
                    needConsent += item
                else -> failed += item
            }
        }
        if (needConsent.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val request = MediaStore.createDeleteRequest(context.contentResolver, needConsent.map { Uri.parse(it.uri) })
            DeleteResult.NeedsConsent(request.intentSender, needConsent.map { it.id }, deleted)
        } else {
            DeleteResult.Done(deleted, failed.size)
        }
    }

    /** After the system delete dialog: forget the items whose files are really gone. Returns how many. */
    suspend fun confirmDeleted(ids: List<Long>): Int = withContext(Dispatchers.IO) {
        val gone = getMedia(ids).filter { !MediaFiles.exists(context, it.uri) }.map { it.id }
        forget(gone)
        gone.size
    }

    /** Removes Library entries (not files): used for missing files and deleted media. */
    suspend fun forget(ids: List<Long>) {
        if (ids.isEmpty()) return
        ids.chunked(500).forEach { dao.deleteMedia(it) }
        ids.forEach(artwork::delete)
    }

    /** "Remove missing items": forgets every entry whose file is gone. */
    suspend fun forgetMissing(): Int {
        val ids = dao.getMissingIds()
        forget(ids)
        return ids.size
    }

    // --- Playback bookkeeping (written by the playback service) -------------------------------

    suspend fun recordPlay(id: Long) = dao.recordPlay(id, System.currentTimeMillis())

    suspend fun saveResumePosition(id: Long, positionMs: Long, durationMs: Long?) =
        dao.setResumePosition(id, ResumePolicy.positionToStore(positionMs, durationMs))

    suspend fun fillDuration(id: Long, durationMs: Long) {
        if (durationMs > 0) dao.fillDuration(id, durationMs)
    }

    /** The player couldn't open the file: show it as missing instead of failing again and again. */
    suspend fun markMissingIfGone(id: Long): Boolean = withContext(Dispatchers.IO) {
        val item = dao.getMedia(id) ?: return@withContext false
        if (MediaFiles.exists(context, item.uri)) return@withContext false
        dao.markMissing(id, System.currentTimeMillis())
        true
    }
}
