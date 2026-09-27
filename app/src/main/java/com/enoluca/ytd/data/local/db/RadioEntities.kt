package com.enoluca.ytd.data.local.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * A radio station the user saved (My Radio) or played (Recently played). Directory/catalog data
 * is copied here so both lists work offline and keep working if a station leaves the directory.
 * Live radio never appears in the Library or Downloads: nothing is recorded.
 */
@Entity(tableName = "radio_stations", indices = [Index("favorite"), Index("lastPlayedAt")])
data class RadioStationEntity(
    @PrimaryKey val id: String,
    val name: String,
    val countryCode: String,
    val city: String?,
    val genre: String?,
    val category: String,
    val streamUrl: String,
    val logoUrl: String?,
    val websiteUrl: String?,
    val codec: String?,
    val bitrate: Int?,
    @ColumnInfo(defaultValue = "0") val hls: Boolean = false,
    @ColumnInfo(defaultValue = "0") val favorite: Boolean = false,
    val favoritedAt: Long? = null,
    val lastPlayedAt: Long? = null,
    @ColumnInfo(defaultValue = "0") val playCount: Int = 0,
    val updatedAt: Long,
)

/**
 * What the player had loaded (single row), so reopening the app shows the same queue at the same
 * place — paused, never auto-playing. [itemIds] are player media ids, one per line: Library ids
 * ("42") and radio stations ("radio:<id>").
 */
@Entity(tableName = "playback_snapshot")
data class PlaybackSnapshotEntity(
    @PrimaryKey val id: Int = 1,
    val itemIds: String,
    val currentIndex: Int,
    val positionMs: Long,
    val shuffle: Boolean,
    val repeatMode: Int,
    val contextTitle: String?,
    val contextPlaylistId: Long?,
    val updatedAt: Long,
)

@Dao
interface RadioDao {
    @Query("SELECT * FROM radio_stations WHERE id = :id")
    suspend fun get(id: String): RadioStationEntity?

    @Upsert
    suspend fun upsert(entity: RadioStationEntity)

    @Query("SELECT * FROM radio_stations WHERE favorite = 1 ORDER BY favoritedAt DESC, name")
    fun observeFavorites(): Flow<List<RadioStationEntity>>

    @Query("SELECT * FROM radio_stations WHERE lastPlayedAt IS NOT NULL ORDER BY lastPlayedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<RadioStationEntity>>

    @Query("SELECT * FROM radio_stations")
    fun observeAll(): Flow<List<RadioStationEntity>>

    @Query("UPDATE radio_stations SET favorite = :favorite, favoritedAt = :at, updatedAt = :at WHERE id = :id")
    suspend fun setFavorite(id: String, favorite: Boolean, at: Long)

    @Query("UPDATE radio_stations SET lastPlayedAt = :at, playCount = playCount + 1, updatedAt = :at WHERE id = :id")
    suspend fun recordPlay(id: String, at: Long)

    @Query("UPDATE radio_stations SET lastPlayedAt = NULL WHERE favorite = 0 AND lastPlayedAt IS NOT NULL AND id NOT IN (SELECT id FROM radio_stations WHERE lastPlayedAt IS NOT NULL ORDER BY lastPlayedAt DESC LIMIT :keep)")
    suspend fun trimRecent(keep: Int)

    /** Stations that are neither saved nor recent carry no information any more. */
    @Query("DELETE FROM radio_stations WHERE favorite = 0 AND lastPlayedAt IS NULL")
    suspend fun deleteUnused()

    @Query("UPDATE radio_stations SET lastPlayedAt = NULL")
    suspend fun clearRecent()
}

@Dao
interface PlaybackSnapshotDao {
    @Query("SELECT * FROM playback_snapshot WHERE id = 1")
    suspend fun get(): PlaybackSnapshotEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(snapshot: PlaybackSnapshotEntity)

    @Query("DELETE FROM playback_snapshot")
    suspend fun clear()

    @Transaction
    suspend fun replace(snapshot: PlaybackSnapshotEntity?) {
        if (snapshot == null) clear() else save(snapshot)
    }
}
