package com.enoluca.ytd.download

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.enoluca.ytd.core.NetworkMonitor
import com.enoluca.ytd.data.local.datastore.SettingsDataStore
import com.enoluca.ytd.data.local.db.AppDatabase
import com.enoluca.ytd.data.local.db.DownloadEntity
import com.enoluca.ytd.data.model.AnalysisResult
import com.enoluca.ytd.data.model.DownloadStatus
import com.enoluca.ytd.data.model.PlaylistEntry
import com.enoluca.ytd.data.model.QuickFormat
import com.enoluca.ytd.data.platform.DetectedPlatform
import com.enoluca.ytd.data.provider.DownloadJobRequest
import com.enoluca.ytd.data.provider.DownloadResult
import com.enoluca.ytd.data.provider.MediaProvider
import com.enoluca.ytd.data.provider.ProviderException
import com.enoluca.ytd.data.provider.StoragePublisher
import com.enoluca.ytd.data.repository.DownloadRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The real queue (Room + DownloadEngine + StoragePublisher + notifications) on a device, with
 * yt-dlp replaced by a scripted provider so outcomes are deterministic:
 *
 *   ok://…     finishes after a few progress steps
 *   fail://…   fails permanently (e.g. a removed video)
 *   flaky://…  fails the first time, works on retry
 *   slow://…   keeps downloading until cancelled/paused
 */
@RunWith(AndroidJUnit4::class)
class DownloadQueueTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var engine: DownloadEngine
    private lateinit var repository: DownloadRepository
    private val provider = ScriptedProvider()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        engine = DownloadEngine(
            context = context,
            downloadDao = db.downloadDao(),
            historyDao = db.historyDao(),
            mediaProvider = provider,
            storagePublisher = StoragePublisher(context),
            settingsDataStore = SettingsDataStore(context),
            notifications = DownloadNotifications(context).also { it.ensureChannels() },
            networkMonitor = NetworkMonitor(context),
            scope = scope,
            requestForeground = {},
        )
        repository = DownloadRepository(db.downloadDao(), engine)
        engine.start()
    }

    @After
    fun tearDown() = runBlocking<Unit> {
        // Remove what the tests published to shared storage.
        db.downloadDao().getByStatuses(listOf(DownloadStatus.COMPLETED)).mapNotNull { it.fileUri }.forEach {
            runCatching { context.contentResolver.delete(Uri.parse(it), null, null) }
        }
        scope.cancel()
        db.close()
    }

    private suspend fun enqueue(url: String, title: String = url) =
        repository.enqueueQuick(url, title, null, QuickFormat.BestVideo(720))

    private suspend fun awaitStatus(id: Long, vararg wanted: DownloadStatus, timeoutMs: Long = 20_000): DownloadEntity =
        withTimeout(timeoutMs) {
            while (true) {
                val row = db.downloadDao().getById(id)
                if (row != null && row.status in wanted) return@withTimeout row
                delay(50)
            }
            @Suppress("UNREACHABLE_CODE") error("unreachable")
        }

    @Test
    fun oneDownloadGoesThroughEveryStateAndLandsInHistory() = runBlocking<Unit> {
        val id = enqueue("ok://one", "One")
        val done = awaitStatus(id, DownloadStatus.COMPLETED)
        assertNotNull(done.fileUri)
        assertEquals(100f, done.progressPercent)
        val seen = provider.statesSeen[id].orEmpty()
        assertTrue("saw $seen", seen.contains(DownloadStatus.FETCHING_INFO))
        assertTrue("saw $seen", seen.contains(DownloadStatus.DOWNLOADING))
        val history = db.historyDao().findByUrls(listOf("ok://one"))
        assertEquals(DownloadStatus.COMPLETED, history.single().status)
        assertNotNull(history.single().location)
        assertFalse("temp dir removed", engine.tempDirFor(id).exists())
    }

    @Test
    fun multipleDownloadsAllComplete() = runBlocking<Unit> {
        val ids = (1..4).map { enqueue("ok://multi$it") }
        ids.forEach { awaitStatus(it, DownloadStatus.COMPLETED) }
        assertTrue(provider.maxConcurrent.get() <= 2) // default concurrency
    }

    @Test
    fun playlistItemsAreSeparateJobsAndOneFailureDoesNotStopTheRest() = runBlocking<Unit> {
        val entries = listOf(
            PlaylistEntry("A", "ok://pl-a", 1, 60, null),
            PlaylistEntry("B", "fail://pl-b", 2, 60, null),
            PlaylistEntry("C", "ok://pl-c", 3, 60, null),
        )
        assertEquals(3, repository.enqueuePlaylist("https://www.youtube.com/playlist?list=PLX", "PLX", "My list", entries, QuickFormat.BestVideo()))
        val rows = db.downloadDao().getByStatuses(DownloadStatus.entries)
        assertEquals(3, rows.size)
        assertEquals(1, rows.map { it.batchId }.distinct().size)
        assertEquals(listOf(1, 2, 3), rows.sortedBy { it.effectiveQueuePosition }.map { it.batchIndex })
        assertTrue(rows.all { it.batchSize == 3 && it.batchTitle == "My list" })

        val byUrl = rows.associateBy { it.sourceUrl }
        awaitStatus(byUrl.getValue("ok://pl-a").id, DownloadStatus.COMPLETED)
        val failed = awaitStatus(byUrl.getValue("fail://pl-b").id, DownloadStatus.FAILED)
        awaitStatus(byUrl.getValue("ok://pl-c").id, DownloadStatus.COMPLETED)
        assertEquals(ProviderException.VideoUnavailable().message, failed.errorMessage)
        assertEquals(DownloadStatus.FAILED, db.historyDao().findByUrls(listOf("fail://pl-b")).single().status)
    }

    @Test
    fun retryRestartsOnlyTheFailedItem() = runBlocking<Unit> {
        val good = enqueue("ok://r-good")
        val flaky = enqueue("flaky://r-flaky")
        awaitStatus(good, DownloadStatus.COMPLETED)
        awaitStatus(flaky, DownloadStatus.FAILED)
        val goodCalls = provider.calls("ok://r-good")

        engine.retry(flaky)
        awaitStatus(flaky, DownloadStatus.COMPLETED)
        assertEquals("completed item untouched", goodCalls, provider.calls("ok://r-good"))
        assertEquals(2, provider.calls("flaky://r-flaky"))
    }

    @Test
    fun cancelStopsTheWorkKeepsTheRowAndLeavesOthersRunning() = runBlocking<Unit> {
        val slow = enqueue("slow://c-slow")
        val other = enqueue("ok://c-other")
        awaitStatus(slow, DownloadStatus.DOWNLOADING)

        engine.cancel(slow)
        val cancelled = awaitStatus(slow, DownloadStatus.CANCELLED)
        awaitStatus(other, DownloadStatus.COMPLETED)
        // The provider's download coroutine really ended (not just a status flip).
        withTimeout(5_000) { while (provider.running.get() > 0) delay(20) }
        assertTrue(provider.cancelledIds.isNotEmpty())
        assertEquals(DownloadStatus.CANCELLED, cancelled.status)
        assertFalse("temp dir removed", engine.tempDirFor(slow).exists())
        assertEquals(DownloadStatus.CANCELLED, db.historyDao().findByUrls(listOf("slow://c-slow")).single().status)

        // A cancelled item can be retried later.
        provider.slowFinishes = true
        engine.retry(slow)
        awaitStatus(slow, DownloadStatus.COMPLETED)
    }

    @Test
    fun pauseAndResumeKeepTheJob() = runBlocking<Unit> {
        provider.slowFinishes = false
        val id = enqueue("slow://p-slow")
        awaitStatus(id, DownloadStatus.DOWNLOADING)
        engine.pause(id)
        awaitStatus(id, DownloadStatus.PAUSED)
        withTimeout(5_000) { while (provider.running.get() > 0) delay(20) }
        provider.slowFinishes = true
        engine.resume(id)
        awaitStatus(id, DownloadStatus.COMPLETED)
    }

    // --- Progress pipeline: yt-dlp output -> ProgressTracker -> engine -> Room (what the UI observes)

    /** Samples the row (as the UI's Flow sees it) until [until] matches. */
    private suspend fun sample(id: Long, until: (DownloadEntity) -> Boolean): List<DownloadEntity> {
        val seen = mutableListOf<DownloadEntity>()
        withTimeout(30_000) {
            while (true) {
                val row = db.downloadDao().getById(id) ?: break
                seen += row
                if (until(row)) break
                delay(30)
            }
        }
        return seen
    }

    @Test
    fun progressMovesThroughRealIntermediateValuesBeforeCompletion() = runBlocking<Unit> {
        val id = enqueue("ok://progress")
        val rows = sample(id) { it.status == DownloadStatus.COMPLETED }
        val downloading = rows.filter { it.status == DownloadStatus.DOWNLOADING }
        val percents = downloading.map { it.progressPercent }
        val intermediate = percents.filter { it > 0f && it < 100f }.distinct()
        assertTrue("saw only $percents", intermediate.size >= 5)
        assertEquals("never goes backwards", percents.sorted(), percents)
        // Each stored percentage is exactly the stored bytes over the stored total.
        downloading.filter { it.totalBytes != null && it.downloadedBytes > 0 }.forEach {
            assertEquals(it.downloadedBytes * 100f / it.totalBytes!!, it.progressPercent, 0.01f)
        }
        assertTrue("100% only with every byte", downloading.filter { it.progressPercent >= 100f }.all { it.downloadedBytes == it.totalBytes })
    }

    @Test
    fun unknownTotalStaysIndeterminateWithRealBytes() = runBlocking<Unit> {
        val id = enqueue("unknown://size")
        val rows = sample(id) { it.status == DownloadStatus.COMPLETED }
        val downloading = rows.filter { it.status == DownloadStatus.DOWNLOADING && it.downloadedBytes in 1..999_999 }
        assertTrue(downloading.size >= 3)
        downloading.forEach {
            assertEquals(null, it.totalBytes)
            assertEquals(0f, it.progressPercent)
            assertEquals(null, ProgressDisplay.fraction(it)) // indeterminate bar
            assertTrue(ProgressDisplay.text(it).startsWith("Downloading"))
        }
        assertEquals(downloading.map { it.downloadedBytes }.sorted(), downloading.map { it.downloadedBytes })
    }

    @Test
    fun cancellationStopsProgressUpdates() = runBlocking<Unit> {
        val id = enqueue("slow://cancel-progress")
        sample(id) { it.status == DownloadStatus.DOWNLOADING && it.progressPercent > 5f }
        engine.cancel(id)
        val stopped = awaitStatus(id, DownloadStatus.CANCELLED)
        delay(1_500)
        val later = db.downloadDao().getById(id)!!
        assertEquals(DownloadStatus.CANCELLED, later.status)
        assertEquals(stopped.progressPercent, later.progressPercent)
        assertEquals(stopped.downloadedBytes, later.downloadedBytes)
        assertEquals(null, ProgressDisplay.fraction(later))
    }

    @Test
    fun failedDownloadStopsProgressUpdates() = runBlocking<Unit> {
        val id = enqueue("failmid://progress")
        val rows = sample(id) { it.status == DownloadStatus.FAILED }
        assertTrue("progress moved before failing", rows.any { it.status == DownloadStatus.DOWNLOADING && it.progressPercent in 1f..99f })
        val failed = rows.last()
        delay(1_000)
        val later = db.downloadDao().getById(id)!!
        assertEquals(DownloadStatus.FAILED, later.status)
        assertEquals(failed.progressPercent, later.progressPercent)
        assertEquals(failed.downloadedBytes, later.downloadedBytes)
        assertEquals(null, ProgressDisplay.fraction(later))
    }

    @Test
    fun retryResetsAndRestartsProgress() = runBlocking<Unit> {
        val id = enqueue("flakymid://retry-progress")
        val firstRun = sample(id) { it.status == DownloadStatus.FAILED }
        assertTrue(firstRun.maxOf { it.progressPercent } >= 30f)

        engine.retry(id)
        val reset = db.downloadDao().getById(id)!!
        assertEquals(0f, reset.progressPercent)
        assertEquals(0L, reset.downloadedBytes)
        assertEquals(null, reset.totalBytes)

        val secondRun = sample(id) { it.status == DownloadStatus.COMPLETED }
            .filter { it.status == DownloadStatus.DOWNLOADING && it.progressPercent > 0f }
        assertTrue("restarts from the beginning", secondRun.first().progressPercent < 30f)
        assertEquals(secondRun.map { it.progressPercent }.sorted(), secondRun.map { it.progressPercent })
    }

    @Test
    fun queuedItemsKeepIndependentProgress() = runBlocking<Unit> {
        val fast = enqueue("slow://fast-a")
        val slow = enqueue("slow://b")
        val waiting = enqueue("ok://c-waits")
        withTimeout(20_000) {
            while (true) {
                val a = db.downloadDao().getById(fast)!!
                val b = db.downloadDao().getById(slow)!!
                if (a.progressPercent > 20f && b.progressPercent > 1f) break
                delay(30)
            }
        }
        val a = db.downloadDao().getById(fast)!!
        val b = db.downloadDao().getById(slow)!!
        val c = db.downloadDao().getById(waiting)!!
        // Different speeds -> different percentages; each row's numbers are its own.
        assertTrue("fast ${a.progressPercent} vs slow ${b.progressPercent}", a.progressPercent > b.progressPercent + 10f)
        assertEquals(a.downloadedBytes * 100f / a.totalBytes!!, a.progressPercent, 0.01f)
        assertEquals(b.downloadedBytes * 100f / b.totalBytes!!, b.progressPercent, 0.01f)
        // The third item waits for a free slot (2 concurrent) with no progress of its own.
        assertEquals(DownloadStatus.QUEUED, c.status)
        assertEquals(0f, c.progressPercent)
        engine.cancelAll()
    }

    @Test
    fun stalledDownloadHoldsItsRealPercentageAndDropsTheStaleSpeed() = runBlocking<Unit> {
        val id = enqueue("stall://progress")
        sample(id) { it.progressPercent >= 50f }
        delay(4_500) // no output from yt-dlp for > 3 s
        val stalled = db.downloadDao().getById(id)!!
        assertEquals(DownloadStatus.DOWNLOADING, stalled.status)
        assertEquals(50f, stalled.progressPercent) // the bar does not move on its own
        assertEquals(0L, stalled.speedBytesPerSec) // no stale "MB/s" while nothing arrives
        assertEquals(null, stalled.etaSeconds)
        val after = sample(id) { it.status == DownloadStatus.COMPLETED }
        assertTrue("moves again once data flows", after.any { it.status == DownloadStatus.DOWNLOADING && it.progressPercent > 50f })
    }

    @Test
    fun progressAfterAnAutomaticRetryClearsTheRetryMessage() = runBlocking<Unit> {
        val id = enqueue("slow://auto-retry")
        sample(id) { it.status == DownloadStatus.DOWNLOADING && it.progressPercent > 5f }
        // What the engine writes while it waits to retry after a network error.
        db.downloadDao().setErrorMessage(id, "Connection failed. Retrying automatically (1/3)…", System.currentTimeMillis())
        val resumed = sample(id) { it.errorMessage == null }.last()
        assertEquals(DownloadStatus.DOWNLOADING, resumed.status)
        assertTrue(ProgressDisplay.text(resumed).startsWith("Downloading "))
        engine.cancel(id)
    }

    /** Scripted stand-in for yt-dlp. */
    private inner class ScriptedProvider : MediaProvider {
        val statesSeen = ConcurrentHashMap<Long, MutableSet<DownloadStatus>>()
        val cancelledIds = ConcurrentHashMap.newKeySet<String>()
        val running = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)
        private val callCounts = ConcurrentHashMap<String, AtomicInteger>()
        @Volatile var slowFinishes = false

        fun calls(url: String) = callCounts[url]?.get() ?: 0

        override suspend fun analyze(url: String, detected: DetectedPlatform): AnalysisResult = error("not used")

        override suspend fun download(
            job: DownloadJobRequest,
            processId: String,
            onProgress: (percent: Float, etaSeconds: Long?, rawLine: String) -> Unit,
        ): DownloadResult {
            val n = callCounts.getOrPut(job.sourceUrl) { AtomicInteger(0) }.incrementAndGet()
            val now = running.incrementAndGet()
            maxConcurrent.updateAndGet { maxOf(it, now) }
            val id = File(job.outputDirectory).name.toLong()
            try {
                record(id)
                delay(300)
                when {
                    job.sourceUrl.startsWith("fail://") -> throw ProviderException.VideoUnavailable("scripted")
                    job.sourceUrl.startsWith("flaky://") && n == 1 -> throw ProviderException.VideoUnavailable("scripted first attempt")
                }
                // Exactly the lines yt-dlp prints with the app's --print/--progress-template options.
                val url = job.sourceUrl
                val total: Long? = if (url.startsWith("unknown://")) null else 1_000_000L
                val step = if (url.contains("fast")) 50_000L else 10_000L
                onProgress(-1f, null, "[ytdsizes] NA|NA|${total ?: "NA"}|NA")
                var bytes = 0L
                while (true) {
                    val holding = url.startsWith("slow://") && !slowFinishes && bytes >= 990_000L
                    if (!holding) bytes = minOf(bytes + step, 1_000_000L)
                    if (bytes >= 1_000_000L) break
                    onProgress(-1f, 5, "[ytdprog] downloading|$bytes|${total ?: "NA"}|NA|100000|5|18")
                    record(id)
                    if ((url.startsWith("failmid://") || (url.startsWith("flakymid://") && n == 1)) && bytes >= 400_000L) {
                        throw ProviderException.VideoUnavailable("scripted failure at 40%")
                    }
                    // A server that goes quiet at 50% for a few seconds.
                    if (url.startsWith("stall://") && bytes == 500_000L) delay(5_000)
                    delay(40)
                }
                onProgress(-1f, null, "[ytdprog] finished|1000000|1000000|NA|100000|NA|18")
                val out = File(job.outputDirectory, "${job.outputFileNameNoExt}.mp4")
                out.parentFile?.mkdirs()
                out.writeBytes(ByteArray(2048) { it.toByte() })
                return DownloadResult(out.absolutePath)
            } catch (e: kotlinx.coroutines.CancellationException) {
                cancelledIds += processId
                throw e
            } finally {
                running.decrementAndGet()
            }
        }

        override fun cancel(processId: String): Boolean {
            cancelledIds += processId
            return true
        }

        private suspend fun record(id: Long) {
            db.downloadDao().getById(id)?.status?.let { statesSeen.getOrPut(id) { ConcurrentHashMap.newKeySet() }.add(it) }
        }
    }
}
