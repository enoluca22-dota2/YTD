package com.enoluca.ytd.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryDao {

    // --- Media --------------------------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMedia(entity: LibraryMediaEntity): Long

    @Update
    suspend fun updateMedia(entity: LibraryMediaEntity)

    @Query("SELECT * FROM library_media WHERE id = :id")
    suspend fun getMedia(id: Long): LibraryMediaEntity?

    @Query("SELECT * FROM library_media WHERE id IN (:ids)")
    suspend fun getMedia(ids: List<Long>): List<LibraryMediaEntity>

    @Query("SELECT * FROM library_media WHERE uri = :uri")
    suspend fun getMediaByUri(uri: String): LibraryMediaEntity?

    @Query("SELECT * FROM library_media")
    suspend fun getAllMedia(): List<LibraryMediaEntity>

    @Query("SELECT uri FROM library_media")
    suspend fun getAllUris(): List<String>

    @Query("SELECT * FROM library_media WHERE id = :id")
    fun observeMedia(id: Long): Flow<LibraryMediaEntity?>

    /** Everything, newest first. Missing files are included so the UI can show them as unavailable. */
    @Query("SELECT * FROM library_media ORDER BY dateAdded DESC, id DESC")
    fun observeAllMedia(): Flow<List<LibraryMediaEntity>>

    /**
     * Title, artist, album, file name, or the name of a playlist the item is in. [pattern] is a
     * LIKE pattern built by LibrarySearch (wildcards in the user's text already escaped with '\').
     */
    @Query(
        """SELECT * FROM library_media
           WHERE title LIKE :pattern ESCAPE '\' OR artist LIKE :pattern ESCAPE '\'
              OR album LIKE :pattern ESCAPE '\' OR fileName LIKE :pattern ESCAPE '\'
              OR id IN (SELECT i.mediaId FROM library_playlist_items i
                        JOIN library_playlists p ON p.id = i.playlistId
                        WHERE p.title LIKE :pattern ESCAPE '\')
           ORDER BY dateAdded DESC, id DESC"""
    )
    fun searchMedia(pattern: String): Flow<List<LibraryMediaEntity>>

    @Query("SELECT COUNT(*) AS count, COALESCE(SUM(sizeBytes), 0) AS totalBytes FROM library_media WHERE missingSince IS NULL")
    fun observeStats(): Flow<LibraryStats>

    /** Only what ENAGELYUCA downloaded (Settings → storage). */
    @Query("SELECT COUNT(*) AS count, COALESCE(SUM(sizeBytes), 0) AS totalBytes FROM library_media WHERE missingSince IS NULL AND origin = 'DOWNLOAD'")
    fun observeDownloadedStats(): Flow<LibraryStats>

    @Query("UPDATE library_media SET favorite = :favorite WHERE id IN (:ids)")
    suspend fun setFavorite(ids: List<Long>, favorite: Boolean)

    @Query("UPDATE library_media SET playCount = playCount + 1, lastPlayedAt = :now WHERE id = :id")
    suspend fun recordPlay(id: Long, now: Long)

    @Query("UPDATE library_media SET resumePositionMs = :positionMs WHERE id = :id")
    suspend fun setResumePosition(id: Long, positionMs: Long)

    @Query("UPDATE library_media SET durationMs = :durationMs WHERE id = :id AND (durationMs IS NULL OR durationMs <= 0)")
    suspend fun fillDuration(id: Long, durationMs: Long)

    @Query("UPDATE library_media SET missingSince = :since WHERE id = :id AND missingSince IS NULL")
    suspend fun markMissing(id: Long, since: Long)

    @Query("UPDATE library_media SET missingSince = NULL WHERE id = :id")
    suspend fun markPresent(id: Long)

    @Query("UPDATE library_media SET artworkPath = :path WHERE id = :id")
    suspend fun setArtworkPath(id: Long, path: String)

    @Query("SELECT * FROM library_media WHERE artworkPath IS NULL AND missingSince IS NULL")
    suspend fun getMediaWithoutArtwork(): List<LibraryMediaEntity>

    @Query("DELETE FROM library_media WHERE id IN (:ids)")
    suspend fun deleteMedia(ids: List<Long>)

    @Query("SELECT id FROM library_media WHERE missingSince IS NOT NULL")
    suspend fun getMissingIds(): List<Long>

    /** The item played most recently, for "resume playback" after the app was closed. */
    @Query("SELECT * FROM library_media WHERE lastPlayedAt IS NOT NULL AND missingSince IS NULL ORDER BY lastPlayedAt DESC LIMIT 1")
    suspend fun getLastPlayed(): LibraryMediaEntity?

    // --- Playlists ----------------------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPlaylist(entity: LibraryPlaylistEntity): Long

    @Update
    suspend fun updatePlaylist(entity: LibraryPlaylistEntity)

    @Query("SELECT * FROM library_playlists WHERE id = :id")
    suspend fun getPlaylist(id: Long): LibraryPlaylistEntity?

    @Query("SELECT * FROM library_playlists WHERE sourceKey = :sourceKey")
    suspend fun getPlaylistBySourceKey(sourceKey: String): LibraryPlaylistEntity?

    @Query("DELETE FROM library_playlists WHERE id = :id")
    suspend fun deletePlaylist(id: Long)

    @Query("UPDATE library_playlists SET updatedAt = :now WHERE id = :id")
    suspend fun touchPlaylist(id: Long, now: Long)

    @Query(
        """SELECT p.*,
                  COUNT(m.id) AS itemCount,
                  COALESCE(SUM(m.durationMs), 0) AS totalDurationMs,
                  (SELECT COALESCE(NULLIF(m2.artworkPath, ''), m2.thumbnailUrl)
                     FROM library_playlist_items i2 JOIN library_media m2 ON m2.id = i2.mediaId
                    WHERE i2.playlistId = p.id AND m2.missingSince IS NULL
                      AND COALESCE(NULLIF(m2.artworkPath, ''), m2.thumbnailUrl) IS NOT NULL
                    ORDER BY i2.position LIMIT 1) AS firstArtwork
           FROM library_playlists p
           LEFT JOIN library_playlist_items i ON i.playlistId = p.id
           LEFT JOIN library_media m ON m.id = i.mediaId AND m.missingSince IS NULL
           GROUP BY p.id
           ORDER BY p.favorite DESC, p.updatedAt DESC"""
    )
    fun observePlaylistSummaries(): Flow<List<PlaylistSummary>>

    @Query("SELECT * FROM library_playlists WHERE id = :id")
    fun observePlaylist(id: Long): Flow<LibraryPlaylistEntity?>

    @Query(
        """SELECT m.*, i.position AS position, i.sourceIndex AS sourceIndex
           FROM library_playlist_items i JOIN library_media m ON m.id = i.mediaId
           WHERE i.playlistId = :playlistId ORDER BY i.position, i.addedAt"""
    )
    fun observePlaylistTracks(playlistId: Long): Flow<List<PlaylistTrack>>

    @Query(
        """SELECT m.*, i.position AS position, i.sourceIndex AS sourceIndex
           FROM library_playlist_items i JOIN library_media m ON m.id = i.mediaId
           WHERE i.playlistId = :playlistId ORDER BY i.position, i.addedAt"""
    )
    suspend fun getPlaylistTracks(playlistId: Long): List<PlaylistTrack>

    @Query("SELECT * FROM library_playlist_items WHERE playlistId = :playlistId ORDER BY position, addedAt")
    suspend fun getPlaylistItems(playlistId: Long): List<PlaylistItemEntity>

    @Query("SELECT playlistId FROM library_playlist_items WHERE mediaId = :mediaId")
    suspend fun getPlaylistIdsContaining(mediaId: Long): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPlaylistItem(item: PlaylistItemEntity): Long

    @Query("DELETE FROM library_playlist_items WHERE playlistId = :playlistId AND mediaId = :mediaId")
    suspend fun deletePlaylistItem(playlistId: Long, mediaId: Long)

    @Query("UPDATE library_playlist_items SET position = :position WHERE playlistId = :playlistId AND mediaId = :mediaId")
    suspend fun setPlaylistItemPosition(playlistId: Long, mediaId: Long, position: Int)

    /** Rewrites positions 0..n-1 in the given order (one transaction, so the order is never half-applied). */
    @Transaction
    suspend fun renumberPlaylist(playlistId: Long, orderedMediaIds: List<Long>) {
        orderedMediaIds.forEachIndexed { index, mediaId -> setPlaylistItemPosition(playlistId, mediaId, index) }
    }

    // --- Download batch → playlist ------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBatch(batch: PlaylistBatchEntity)

    @Query("SELECT playlistId FROM library_playlist_batches WHERE batchId = :batchId")
    suspend fun getPlaylistIdForBatch(batchId: String): Long?
}
