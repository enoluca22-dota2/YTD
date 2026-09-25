package com.enoluca.ytd.data.local.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.enoluca.ytd.data.model.DownloadStatus
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

    private companion object {
        const val DB = "migration-test.db"

        /** Verbatim from app/schemas/com.enoluca.ytd.data.local.db.AppDatabase/3.json. */
        val V3_SCHEMA = listOf(
            """CREATE TABLE IF NOT EXISTS `downloads` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sourceUrl` TEXT NOT NULL, `webpageUrl` TEXT NOT NULL, `title` TEXT NOT NULL, `thumbnailUrl` TEXT, `extractorKey` TEXT, `formatId` TEXT NOT NULL, `formatKind` TEXT NOT NULL, `resolutionLabel` TEXT, `container` TEXT, `videoCodec` TEXT, `audioCodec` TEXT, `requiresAudioMerge` INTEGER NOT NULL, `requiresAudioExtraction` INTEGER NOT NULL, `audioBitrateKbps` INTEGER, `category` TEXT NOT NULL, `fileBaseName` TEXT NOT NULL, `status` TEXT NOT NULL, `progressPercent` REAL NOT NULL, `downloadedBytes` INTEGER NOT NULL, `totalBytes` INTEGER, `speedBytesPerSec` INTEGER NOT NULL, `etaSeconds` INTEGER, `errorMessage` TEXT, `retryCount` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `completedAt` INTEGER, `fileUri` TEXT, `queuePosition` INTEGER NOT NULL DEFAULT 0, `playlistIndex` INTEGER)""",
            """CREATE TABLE IF NOT EXISTS `history` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `downloadId` INTEGER NOT NULL, `title` TEXT NOT NULL, `sourceUrl` TEXT NOT NULL, `webpageUrl` TEXT NOT NULL, `thumbnailUrl` TEXT, `filename` TEXT NOT NULL, `formatLabel` TEXT NOT NULL, `resolutionLabel` TEXT, `category` TEXT NOT NULL, `fileSizeBytes` INTEGER, `status` TEXT NOT NULL, `fileUri` TEXT, `completedAt` INTEGER NOT NULL)""",
            """CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)""",
            """INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '96b8a858fb162e6195c32cd6547a23f9')""",
        )
    }
}
