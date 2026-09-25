package com.enoluca.ytd

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.enoluca.ytd.core.SharedLinkParser
import com.enoluca.ytd.download.DownloadNotifications
import com.enoluca.ytd.download.DownloadService
import com.enoluca.ytd.ui.navigation.YtdNavHost
import com.enoluca.ytd.ui.theme.YtdTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var pendingSharedUrl by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Only a fresh launch consumes the share intent; after rotation it was already handled.
        if (savedInstanceState == null) pendingSharedUrl = extractSharedUrl(intent)

        val container = (application as YtdApplication).container
        setContent {
            YtdTheme(settingsDataStore = container.settingsDataStore) {
                YtdNavHost(
                    container = container,
                    pendingSharedUrl = pendingSharedUrl,
                    onSharedUrlConsumed = { pendingSharedUrl = null },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val container = (application as YtdApplication).container
        container.downloadNotifications.cancel(DownloadNotifications.RESUME_AFTER_BOOT_NOTIFICATION_ID)
        // Opening the app resumes the persistent queue (after a reboot or a killed process) and,
        // while we're in the foreground, makes sure running work is covered by the service.
        val engine = container.downloadEngine
        lifecycleScope.launch {
            if (engine.hasRunnableWork()) DownloadService.start(this@MainActivity)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractSharedUrl(intent)?.let { pendingSharedUrl = it }
    }

    /**
     * Share → app (ACTION_SEND) and "Open with" (ACTION_VIEW on a YouTube/TikTok link). The
     * parsing lives in [SharedLinkParser]; an empty/malformed share becomes "" so Home can say
     * that nothing usable was shared instead of silently doing nothing.
     */
    private fun extractSharedUrl(intent: Intent?): String? {
        if (intent == null) return null
        val result = SharedLinkParser.parse(
            action = intent.action,
            mimeType = intent.type,
            extraText = intent.getCharSequenceExtra(Intent.EXTRA_TEXT),
            extraSubject = intent.getStringExtra(Intent.EXTRA_SUBJECT),
            dataString = intent.dataString,
        ) ?: return null
        return when (result) {
            is SharedLinkParser.Result.Links -> result.text
            is SharedLinkParser.Result.NoLink -> ""
        }
    }
}
