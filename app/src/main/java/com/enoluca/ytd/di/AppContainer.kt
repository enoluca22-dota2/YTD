package com.enoluca.ytd.di

import android.content.Context
import androidx.room.Room
import com.enoluca.ytd.core.NetworkMonitor
import com.enoluca.ytd.data.analyzer.MediaAnalyzer
import com.enoluca.ytd.data.local.datastore.SettingsDataStore
import com.enoluca.ytd.data.local.db.AppDatabase
import com.enoluca.ytd.data.local.db.DownloadDao
import com.enoluca.ytd.data.local.db.HistoryDao
import com.enoluca.ytd.data.provider.MediaProvider
import com.enoluca.ytd.data.provider.ProviderException
import com.enoluca.ytd.data.provider.StoragePublisher
import com.enoluca.ytd.data.provider.YtDlpMediaProvider
import com.enoluca.ytd.data.provider.YtDlpUpdater
import com.enoluca.ytd.data.repository.DownloadRepository
import com.enoluca.ytd.data.repository.HistoryRepository
import com.enoluca.ytd.download.DownloadEngine
import com.enoluca.ytd.download.DownloadNotifications
import com.enoluca.ytd.download.DownloadService
import com.enoluca.ytd.library.ArtworkCache
import com.enoluca.ytd.library.LibraryIndexer
import com.enoluca.ytd.library.LibraryRepository
import com.enoluca.ytd.playback.PlaybackSnapshotStore
import com.enoluca.ytd.playback.PlayerConnection
import com.enoluca.ytd.radio.AssetRadioCatalog
import com.enoluca.ytd.radio.RadioBrowserDirectory
import com.enoluca.ytd.radio.RadioRepository
import com.enoluca.ytd.update.UpdateManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Hand-rolled dependency container (no DI framework) shared by the whole process. A single
 * instance lives on [com.enoluca.ytd.YtdApplication] and is reachable from Activities, the
 * download Service, and BroadcastReceivers alike, so the [DownloadEngine] singleton keeps running
 * independent of any particular UI component's lifecycle.
 */
class AppContainer(private val appContext: Context) {

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Completed once the bundled yt-dlp/python/ffmpeg binaries are unpacked and initialized. */
    val engineReady = CompletableDeferred<Unit>()

    private val database: AppDatabase by lazy {
        Room.databaseBuilder(appContext, AppDatabase::class.java, AppDatabase.DATABASE_NAME)
            .fallbackToDestructiveMigration(true)
            .build()
    }

    val downloadDao: DownloadDao by lazy { database.downloadDao() }
    val historyDao: HistoryDao by lazy { database.historyDao() }

    val settingsDataStore: SettingsDataStore by lazy { SettingsDataStore(appContext) }
    val networkMonitor: NetworkMonitor by lazy { NetworkMonitor(appContext) }

    private val ytDlpProvider: YtDlpMediaProvider by lazy {
        YtDlpMediaProvider(networkMonitor) {
            try {
                engineReady.await()
            } catch (e: Exception) {
                throw ProviderException.EngineUnavailable(e)
            }
        }
    }
    val mediaProvider: MediaProvider get() = ytDlpProvider

    /** yt-dlp updates: throttled, never while a download/analysis uses the binary. */
    val ytDlpUpdater: YtDlpUpdater by lazy {
        YtDlpUpdater(
            context = appContext,
            isBusy = { downloadEngine.activeCount.value > 0 || ytDlpProvider.isAnalyzing },
            isOnline = { networkMonitor.isOnline() },
        )
    }
    private val storagePublisher: StoragePublisher by lazy { StoragePublisher(appContext) }
    val downloadNotifications: DownloadNotifications by lazy { DownloadNotifications(appContext) }

    /** Created (and its queue watcher started) on first access. */
    val downloadEngine: DownloadEngine by lazy {
        DownloadEngine(
            context = appContext,
            downloadDao = downloadDao,
            historyDao = historyDao,
            mediaProvider = mediaProvider,
            storagePublisher = storagePublisher,
            settingsDataStore = settingsDataStore,
            notifications = downloadNotifications,
            networkMonitor = networkMonitor,
            scope = applicationScope,
            requestForeground = { DownloadService.start(appContext) },
            // Downloader → file → Library: every published file is indexed right away.
            completionListener = libraryIndexer,
        ).also { it.start() }
    }

    // --- Library & playback (Downloader → Media file → Library → Player) ----------------------

    private val artworkCache: ArtworkCache by lazy { ArtworkCache(appContext) }
    val libraryRepository: LibraryRepository by lazy { LibraryRepository(appContext, database, artworkCache) }
    val libraryIndexer: LibraryIndexer by lazy {
        LibraryIndexer(appContext, database, libraryRepository, artworkCache, settingsDataStore, applicationScope)
    }

    /** Saved Now Playing queue (restored when the playback service starts). */
    val playbackSnapshots: PlaybackSnapshotStore by lazy { PlaybackSnapshotStore(database.playbackSnapshotDao()) }

    // --- Radio (live streams; never part of the Library or Downloads) -------------------------

    /** Bundled catalog + Radio Browser directory; swap [RadioBrowserDirectory] to change the directory service. */
    val radioRepository: RadioRepository by lazy {
        RadioRepository(AssetRadioCatalog(appContext), RadioBrowserDirectory(), database.radioDao())
    }

    /** The UI's connection to the playback service; commands run on the main thread (Media3 requirement). */
    val playerConnection: PlayerConnection by lazy {
        PlayerConnection(appContext, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
    }

    /** GitHub Releases updater (Settings → Check for Updates). */
    val updateManager: UpdateManager by lazy { UpdateManager(appContext, applicationScope, networkMonitor) }

    /** YouTube/TikTok link analyzer: matches the link to its provider, rejects everything else. */
    val analyzer: MediaAnalyzer by lazy { MediaAnalyzer(mediaProvider) }
    val downloadRepository: DownloadRepository by lazy { DownloadRepository(downloadDao, downloadEngine) }
    val historyRepository: HistoryRepository by lazy { HistoryRepository(appContext, historyDao) }
}
