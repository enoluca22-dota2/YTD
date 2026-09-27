package com.enoluca.ytd.data.local.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        DownloadEntity::class,
        HistoryEntity::class,
        LibraryMediaEntity::class,
        LibraryPlaylistEntity::class,
        PlaylistItemEntity::class,
        PlaylistBatchEntity::class,
        RadioStationEntity::class,
        PlaybackSnapshotEntity::class,
    ],
    version = 6,
    exportSchema = true,
    // 4 → 5 adds the Library tables and downloads.playlistSourceIndex; 5 → 6 adds saved/recent
    // radio stations and the playback snapshot. Existing rows are always kept.
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6),
    ],
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun downloadDao(): DownloadDao
    abstract fun historyDao(): HistoryDao
    abstract fun libraryDao(): LibraryDao
    abstract fun radioDao(): RadioDao
    abstract fun playbackSnapshotDao(): PlaybackSnapshotDao

    companion object {
        const val DATABASE_NAME = "ytd_database"
    }
}
