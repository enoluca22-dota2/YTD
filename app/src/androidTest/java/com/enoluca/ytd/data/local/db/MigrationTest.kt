package com.enoluca.ytd.data.local.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.enoluca.ytd.data.model.DownloadStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Existing queues and history survive the update that added playlist batches (v3 → v4). The v3
 * database is created with the exact SQL Room exported for version 3 (app/schemas/…/3.json), then
 * opened by the current AppDatabase, which runs the real auto-migration.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun cleanUp() {
        context.deleteDatabase(DB)
    }

    @Test
    fun v3RowsSurviveAndGetEmptyBatchColumns() = runBlocking<Unit> {
        context.deleteDatabase(DB)
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(DB), null).use { db ->
            V3_SCHEMA.forEach(db::execSQL)
            db.version = 3
            db.execSQL(
                """INSERT INTO downloads (id, sourceUrl, webpageUrl, title, formatId, formatKind, requiresAudioMerge,
                   requiresAudioExtraction, category, fileBaseName, status, progressPercent, downloadedBytes,
                   speedBytesPerSec, retryCount, createdAt, updatedAt, queuePosition)
                   VALUES (1, 'u', 'u', 'Old job', 'best', 'VIDEO', 1, 0, 'VIDEO', 'old', 'QUEUED', 0, 0, 0, 0, 1, 1, 0)"""
            )
            db.execSQL(
                """INSERT INTO history (id, downloadId, title, sourceUrl, webpageUrl, filename, formatLabel, category, status, completedAt)
                   VALUES (1, 1, 'Old', 'u', 'u', 'old.mp4', 'MP4', 'VIDEO', 'COMPLETED', 5)"""
            )
        }

        val room = Room.databaseBuilder(context, AppDatabase::class.java, DB).build()
        try {
            val job = room.downloadDao().getById(1)!!
            assertEquals("Old job", job.title)
            assertEquals(DownloadStatus.QUEUED, job.status)
            assertNull(job.batchId)
            assertNull(job.batchSize)
            assertNull(job.uploader)
            val history = room.historyDao().findByUrls(listOf("u")).single()
            assertEquals("old.mp4", history.filename)
            assertNull(history.errorMessage)
            assertNull(history.location)
        } finally {
            room.close()
        }
    }

    /**
     * The Library update (v4 → v5): downloads, queue and history are kept; the Library tables
     * appear empty and a completed download from before can be linked to Library data.
     */
    @Test
    fun v4DataSurvivesTheLibraryUpdate() = runBlocking<Unit> {
        context.deleteDatabase(DB)
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(DB), null).use { db ->
            V4_SCHEMA.forEach(db::execSQL)
            db.version = 4
            db.execSQL(
                """INSERT INTO downloads (id, sourceUrl, webpageUrl, title, formatId, formatKind, requiresAudioMerge,
                   requiresAudioExtraction, category, fileBaseName, status, progressPercent, downloadedBytes,
                   speedBytesPerSec, retryCount, createdAt, updatedAt, queuePosition, batchId, batchTitle, batchIndex, batchSize, uploader)
                   VALUES (7, 'u', 'u', 'Queued song', 'bestaudio', 'AUDIO', 0, 1, 'MUSIC', 'song', 'PAUSED', 12.5, 10, 0, 0, 1, 1, 3,
                           'pl-X-1', 'Mix', 2, 9, 'Singer')"""
            )
            db.execSQL(
                """INSERT INTO history (id, downloadId, title, sourceUrl, webpageUrl, filename, formatLabel, category, status, completedAt, location)
                   VALUES (3, 7, 'Done', 'u', 'u', 'done.mp3', 'MP3', 'MUSIC', 'COMPLETED', 5, 'Music/ENAGELYUCA')"""
            )
        }

        val room = Room.databaseBuilder(context, AppDatabase::class.java, DB).build()
        try {
            val job = room.downloadDao().getById(7)!!
            assertEquals("Queued song", job.title)
            assertEquals(DownloadStatus.PAUSED, job.status)
            assertEquals(12.5f, job.progressPercent)
            assertEquals("pl-X-1", job.batchId)
            assertEquals("Singer", job.uploader)
            assertNull(job.playlistSourceIndex)
            val history = room.historyDao().findByUrls(listOf("u")).single()
            assertEquals("done.mp3", history.filename)
            assertEquals("Music/ENAGELYUCA", history.location)
            // The Library exists and is usable.
            val library = room.libraryDao()
            assertEquals(0, library.getAllMedia().size)
            val playlistId = library.insertPlaylist(
                LibraryPlaylistEntity(title = "Workout", thumbnailUrl = null, sourceUrl = null, sourceKey = null, sourcePlatform = null, createdAt = 1, updatedAt = 1),
            )
            assertEquals("Workout", library.getPlaylist(playlistId)!!.title)
        } finally {
            room.close()
        }
    }

    /**
     * The media-hub update (v5 → v6, app 0.3.0): downloads, history, the whole Library (media,
     * favorites, resume positions, playlists and their order) are kept; the radio and playback
     * snapshot tables appear empty and usable.
     */
    @Test
    fun v5LibraryAndDownloadsSurviveTheRadioUpdate() = runBlocking<Unit> {
        context.deleteDatabase(DB)
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(DB), null).use { db ->
            V5_SCHEMA.forEach(db::execSQL)
            db.version = 5
            db.execSQL(
                """INSERT INTO downloads (id, sourceUrl, webpageUrl, title, formatId, formatKind, requiresAudioMerge,
                   requiresAudioExtraction, category, fileBaseName, status, progressPercent, downloadedBytes,
                   speedBytesPerSec, retryCount, createdAt, updatedAt, queuePosition, playlistSourceIndex)
                   VALUES (9, 'u9', 'u9', 'Kept job', 'best', 'VIDEO', 1, 0, 'VIDEO', 'kept', 'PAUSED', 40, 10, 0, 0, 1, 1, 0, 4)"""
            )
            db.execSQL(
                """INSERT INTO history (id, downloadId, title, sourceUrl, webpageUrl, filename, formatLabel, category, status, completedAt, location)
                   VALUES (4, 9, 'Song', 'u9', 'u9', 'song.mp3', 'MP3', 'MUSIC', 'COMPLETED', 5, 'Music/ENAGELYUCA')"""
            )
            db.execSQL(
                """INSERT INTO library_media (id, uri, fileName, title, artist, mediaType, dateAdded, origin, favorite, playCount, resumePositionMs, downloadId)
                   VALUES (21, 'content://m/21', 'song.mp3', 'Song', 'Singer', 'AUDIO', 100, 'DOWNLOAD', 1, 3, 61000, 9)"""
            )
            db.execSQL(
                """INSERT INTO library_media (id, uri, title, mediaType, dateAdded, origin, favorite, playCount, resumePositionMs)
                   VALUES (22, 'content://m/22', 'Clip', 'VIDEO', 200, 'DEVICE', 0, 0, 0)"""
            )
            db.execSQL("INSERT INTO library_playlists (id, title, createdAt, updatedAt, favorite) VALUES (5, 'Road Trip', 1, 1, 0)")
            db.execSQL("INSERT INTO library_playlist_items (playlistId, mediaId, position, addedAt) VALUES (5, 22, 0, 1)")
            db.execSQL("INSERT INTO library_playlist_items (playlistId, mediaId, position, addedAt) VALUES (5, 21, 1, 1)")
        }

        val room = Room.databaseBuilder(context, AppDatabase::class.java, DB).build()
        try {
            assertEquals("Kept job", room.downloadDao().getById(9)!!.title)
            assertEquals(4, room.downloadDao().getById(9)!!.playlistSourceIndex)
            assertEquals("song.mp3", room.historyDao().findByUrls(listOf("u9")).single().filename)
            val library = room.libraryDao()
            val song = library.getMedia(21)!!
            assertEquals("Singer", song.artist)
            assertEquals(true, song.favorite)
            assertEquals(3, song.playCount)
            assertEquals(61000L, song.resumePositionMs)
            assertEquals(listOf("Clip", "Song"), library.getPlaylistTracks(5).map { it.media.title })
            // New in v6: saved radio stations and the playback snapshot, empty and usable.
            val radio = room.radioDao()
            radio.upsert(
                RadioStationEntity(
                    id = "s1", name = "Radio Tirana 1", countryCode = "AL", city = "Tirana", genre = "News", category = "news",
                    streamUrl = "http://x/s1", logoUrl = null, websiteUrl = null, codec = "MP3", bitrate = 128, updatedAt = 1,
                ),
            )
            radio.setFavorite("s1", true, 2)
            assertEquals(listOf("s1"), radio.observeFavorites().first().map { it.id })
            assertNull(room.playbackSnapshotDao().get())
        } finally {
            room.close()
        }
    }

    private companion object {
        const val DB = "migration-test.db"

        /** Verbatim from app/schemas/com.enoluca.ytd.data.local.db.AppDatabase/5.json. */
        val V5_SCHEMA = listOf(
            """CREATE TABLE IF NOT EXISTS `downloads` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sourceUrl` TEXT NOT NULL, `webpageUrl` TEXT NOT NULL, `title` TEXT NOT NULL, `thumbnailUrl` TEXT, `extractorKey` TEXT, `formatId` TEXT NOT NULL, `formatKind` TEXT NOT NULL, `resolutionLabel` TEXT, `container` TEXT, `videoCodec` TEXT, `audioCodec` TEXT, `requiresAudioMerge` INTEGER NOT NULL, `requiresAudioExtraction` INTEGER NOT NULL, `audioBitrateKbps` INTEGER, `category` TEXT NOT NULL, `fileBaseName` TEXT NOT NULL, `status` TEXT NOT NULL, `progressPercent` REAL NOT NULL, `downloadedBytes` INTEGER NOT NULL, `totalBytes` INTEGER, `speedBytesPerSec` INTEGER NOT NULL, `etaSeconds` INTEGER, `errorMessage` TEXT, `retryCount` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `completedAt` INTEGER, `fileUri` TEXT, `queuePosition` INTEGER NOT NULL DEFAULT 0, `playlistIndex` INTEGER, `batchId` TEXT, `batchTitle` TEXT, `batchIndex` INTEGER, `batchSize` INTEGER, `uploader` TEXT, `playlistSourceIndex` INTEGER)""",
            """CREATE TABLE IF NOT EXISTS `history` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `downloadId` INTEGER NOT NULL, `title` TEXT NOT NULL, `sourceUrl` TEXT NOT NULL, `webpageUrl` TEXT NOT NULL, `thumbnailUrl` TEXT, `filename` TEXT NOT NULL, `formatLabel` TEXT NOT NULL, `resolutionLabel` TEXT, `category` TEXT NOT NULL, `fileSizeBytes` INTEGER, `status` TEXT NOT NULL, `fileUri` TEXT, `completedAt` INTEGER NOT NULL, `errorMessage` TEXT, `location` TEXT)""",
            """CREATE TABLE IF NOT EXISTS `library_media` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `uri` TEXT NOT NULL, `fileName` TEXT, `title` TEXT NOT NULL, `artist` TEXT, `album` TEXT, `thumbnailUrl` TEXT, `artworkPath` TEXT, `mediaType` TEXT NOT NULL, `mimeType` TEXT, `durationMs` INTEGER, `sizeBytes` INTEGER, `dateAdded` INTEGER NOT NULL, `dateModified` INTEGER, `sourceUrl` TEXT, `sourcePlatform` TEXT, `downloadId` INTEGER, `origin` TEXT NOT NULL, `location` TEXT, `favorite` INTEGER NOT NULL DEFAULT 0, `playCount` INTEGER NOT NULL DEFAULT 0, `lastPlayedAt` INTEGER, `resumePositionMs` INTEGER NOT NULL DEFAULT 0, `missingSince` INTEGER)""",
            """CREATE UNIQUE INDEX IF NOT EXISTS `index_library_media_uri` ON `library_media` (`uri`)""",
            """CREATE INDEX IF NOT EXISTS `index_library_media_downloadId` ON `library_media` (`downloadId`)""",
            """CREATE INDEX IF NOT EXISTS `index_library_media_mediaType` ON `library_media` (`mediaType`)""",
            """CREATE TABLE IF NOT EXISTS `library_playlists` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `thumbnailUrl` TEXT, `sourceUrl` TEXT, `sourceKey` TEXT, `sourcePlatform` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `favorite` INTEGER NOT NULL DEFAULT 0)""",
            """CREATE UNIQUE INDEX IF NOT EXISTS `index_library_playlists_sourceKey` ON `library_playlists` (`sourceKey`)""",
            """CREATE TABLE IF NOT EXISTS `library_playlist_items` (`playlistId` INTEGER NOT NULL, `mediaId` INTEGER NOT NULL, `position` INTEGER NOT NULL, `sourceIndex` INTEGER, `addedAt` INTEGER NOT NULL, PRIMARY KEY(`playlistId`, `mediaId`), FOREIGN KEY(`playlistId`) REFERENCES `library_playlists`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`mediaId`) REFERENCES `library_media`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )""",
            """CREATE INDEX IF NOT EXISTS `index_library_playlist_items_mediaId` ON `library_playlist_items` (`mediaId`)""",
            """CREATE TABLE IF NOT EXISTS `library_playlist_batches` (`batchId` TEXT NOT NULL, `playlistId` INTEGER NOT NULL, PRIMARY KEY(`batchId`), FOREIGN KEY(`playlistId`) REFERENCES `library_playlists`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )""",
            """CREATE INDEX IF NOT EXISTS `index_library_playlist_batches_playlistId` ON `library_playlist_batches` (`playlistId`)""",
            """CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)""",
            """INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, 'ebe322708db1aa6b1ee2cb1bc1e47c2a')""",
        )

        /** Verbatim from app/schemas/com.enoluca.ytd.data.local.db.AppDatabase/4.json. */
        val V4_SCHEMA = listOf(
            """CREATE TABLE IF NOT EXISTS `downloads` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sourceUrl` TEXT NOT NULL, `webpageUrl` TEXT NOT NULL, `title` TEXT NOT NULL, `thumbnailUrl` TEXT, `extractorKey` TEXT, `formatId` TEXT NOT NULL, `formatKind` TEXT NOT NULL, `resolutionLabel` TEXT, `container` TEXT, `videoCodec` TEXT, `audioCodec` TEXT, `requiresAudioMerge` INTEGER NOT NULL, `requiresAudioExtraction` INTEGER NOT NULL, `audioBitrateKbps` INTEGER, `category` TEXT NOT NULL, `fileBaseName` TEXT NOT NULL, `status` TEXT NOT NULL, `progressPercent` REAL NOT NULL, `downloadedBytes` INTEGER NOT NULL, `totalBytes` INTEGER, `speedBytesPerSec` INTEGER NOT NULL, `etaSeconds` INTEGER, `errorMessage` TEXT, `retryCount` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `completedAt` INTEGER, `fileUri` TEXT, `queuePosition` INTEGER NOT NULL DEFAULT 0, `playlistIndex` INTEGER, `batchId` TEXT, `batchTitle` TEXT, `batchIndex` INTEGER, `batchSize` INTEGER, `uploader` TEXT)""",
            """CREATE TABLE IF NOT EXISTS `history` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `downloadId` INTEGER NOT NULL, `title` TEXT NOT NULL, `sourceUrl` TEXT NOT NULL, `webpageUrl` TEXT NOT NULL, `thumbnailUrl` TEXT, `filename` TEXT NOT NULL, `formatLabel` TEXT NOT NULL, `resolutionLabel` TEXT, `category` TEXT NOT NULL, `fileSizeBytes` INTEGER, `status` TEXT NOT NULL, `fileUri` TEXT, `completedAt` INTEGER NOT NULL, `errorMessage` TEXT, `location` TEXT)""",
            """CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)""",
            """INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '091c9f75180c52aec7febf948db63a7c')""",
        )

        /** Verbatim from app/schemas/com.enoluca.ytd.data.local.db.AppDatabase/3.json. */
        val V3_SCHEMA = listOf(
            """CREATE TABLE IF NOT EXISTS `downloads` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sourceUrl` TEXT NOT NULL, `webpageUrl` TEXT NOT NULL, `title` TEXT NOT NULL, `thumbnailUrl` TEXT, `extractorKey` TEXT, `formatId` TEXT NOT NULL, `formatKind` TEXT NOT NULL, `resolutionLabel` TEXT, `container` TEXT, `videoCodec` TEXT, `audioCodec` TEXT, `requiresAudioMerge` INTEGER NOT NULL, `requiresAudioExtraction` INTEGER NOT NULL, `audioBitrateKbps` INTEGER, `category` TEXT NOT NULL, `fileBaseName` TEXT NOT NULL, `status` TEXT NOT NULL, `progressPercent` REAL NOT NULL, `downloadedBytes` INTEGER NOT NULL, `totalBytes` INTEGER, `speedBytesPerSec` INTEGER NOT NULL, `etaSeconds` INTEGER, `errorMessage` TEXT, `retryCount` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `completedAt` INTEGER, `fileUri` TEXT, `queuePosition` INTEGER NOT NULL DEFAULT 0, `playlistIndex` INTEGER)""",
            """CREATE TABLE IF NOT EXISTS `history` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `downloadId` INTEGER NOT NULL, `title` TEXT NOT NULL, `sourceUrl` TEXT NOT NULL, `webpageUrl` TEXT NOT NULL, `thumbnailUrl` TEXT, `filename` TEXT NOT NULL, `formatLabel` TEXT NOT NULL, `resolutionLabel` TEXT, `category` TEXT NOT NULL, `fileSizeBytes` INTEGER, `status` TEXT NOT NULL, `fileUri` TEXT, `completedAt` INTEGER NOT NULL)""",
            """CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)""",
            """INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '96b8a858fb162e6195c32cd6547a23f9')""",
        )
    }
}
