package com.enoluca.ytd

import android.app.Application
import android.util.Log
import com.enoluca.ytd.di.AppContainer
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class YtdApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.downloadNotifications.ensureChannels()
        // Downloaded update APKs are useless once installed (or abandoned).
        container.updateManager.cleanUpOldDownloads()

        // Unpacks the bundled yt-dlp/python/ffmpeg binaries on first run; cheap no-op afterwards.
        // Must not block the main thread. Detection and downloads wait on engineReady.
        container.applicationScope.launch(Dispatchers.IO) {
            try {
                YoutubeDL.init(this@YtdApplication)
                FFmpeg.init(this@YtdApplication)
                container.engineReady.complete(Unit)
            } catch (e: Throwable) {
                // Throwable, not Exception: a broken runtime unpack surfaces as an Error
                // (ExceptionInInitializerError) and must become a message, not a crash.
                Log.e(TAG, "Failed to initialize the download engine's native binaries", e)
                container.engineReady.completeExceptionally(e)
                return@launch
            }

            // The bundled yt-dlp is pinned to whatever was current when youtubedl-android was
            // published, and YouTube changes often enough that a stale extractor starts failing.
            // Check for a new release at most once a day, a little after start-up so it doesn't
            // compete with a link shared into the app, and never while something is downloading.
            // A failed update keeps the installed version (see YtDlpUpdater).
            delay(15_000)
            runCatching { container.ytDlpUpdater.autoUpdateWhenIdle() }
                .onFailure { Log.w(TAG, "yt-dlp update check failed; keeping the current version", it) }
            // App update check: optional, at most once a day, never re-offers a version the user
            // answered "Later" to.
            container.updateManager.autoCheckIfDue()
        }
    }

    companion object {
        private const val TAG = "YtdApplication"
    }
}
