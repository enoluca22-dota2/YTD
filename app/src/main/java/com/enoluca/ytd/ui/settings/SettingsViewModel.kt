package com.enoluca.ytd.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.enoluca.ytd.data.local.datastore.AppSettings
import com.enoluca.ytd.data.local.datastore.AppThemeMode
import com.enoluca.ytd.data.local.datastore.NetworkPolicy
import com.enoluca.ytd.data.local.datastore.SettingsDataStore
import com.enoluca.ytd.data.local.datastore.SpeedLimit
import com.enoluca.ytd.data.provider.YtDlpUpdater
import com.enoluca.ytd.data.repository.HistoryRepository
import com.enoluca.ytd.download.AudioConverter
import com.enoluca.ytd.update.UpdateManager
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class ProviderUpdateState { IDLE, CHECKING, UPDATED, ALREADY_CURRENT, BUSY, OFFLINE, FAILED }

class SettingsViewModel(
    private val appContext: Context,
    private val settingsDataStore: SettingsDataStore,
    private val historyRepository: HistoryRepository,
    private val ytDlpUpdater: YtDlpUpdater,
    val updateManager: UpdateManager,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsDataStore.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    private val _providerUpdateState = MutableStateFlow(ProviderUpdateState.IDLE)
    val providerUpdateState: StateFlow<ProviderUpdateState> = _providerUpdateState.asStateFlow()

    fun setThemeMode(mode: AppThemeMode) = viewModelScope.launch { settingsDataStore.setThemeMode(mode) }
    fun setClipboardDetection(value: Boolean) = viewModelScope.launch { settingsDataStore.setClipboardDetection(value) }
    fun setSpeedLimit(limit: SpeedLimit) = viewModelScope.launch { settingsDataStore.setSpeedLimit(limit) }
    fun setConcurrentDownloads(count: Int) = viewModelScope.launch { settingsDataStore.setConcurrentDownloads(count) }
    fun setAskBeforeDownloading(value: Boolean) = viewModelScope.launch { settingsDataStore.setAskBeforeDownloading(value) }
    fun setNotificationsEnabled(value: Boolean) = viewModelScope.launch { settingsDataStore.setNotificationsEnabled(value) }
    fun setNotifyOnCompletion(value: Boolean) = viewModelScope.launch { settingsDataStore.setNotifyOnCompletion(value) }
    fun setNotifyOnError(value: Boolean) = viewModelScope.launch { settingsDataStore.setNotifyOnError(value) }
    fun setNetworkPolicy(policy: NetworkPolicy) = viewModelScope.launch { settingsDataStore.setNetworkPolicy(policy) }
    fun setCustomDownloadTreeUri(uri: String?) = viewModelScope.launch { settingsDataStore.setCustomDownloadTreeUri(uri) }
    fun setDebugLoggingEnabled(value: Boolean) = viewModelScope.launch { settingsDataStore.setDebugLoggingEnabled(value) }

    fun clearHistory() = viewModelScope.launch { historyRepository.clearAll() }

    fun clearTempFiles() = viewModelScope.launch {
        withContext(Dispatchers.IO) {
            File(appContext.cacheDir, "ytd_downloads").deleteRecursively()
        }
    }

    fun providerVersion(): String = YoutubeDL.version(appContext) ?: "Unknown"

    /** Version line of the bundled FFmpeg (merging + MP3), or why it can't run on this device. */
    suspend fun ffmpegVersion(): String = AudioConverter.version(appContext)

    fun updateProvider() {
        if (_providerUpdateState.value == ProviderUpdateState.CHECKING) return
        _providerUpdateState.value = ProviderUpdateState.CHECKING
        viewModelScope.launch {
            _providerUpdateState.value = when (ytDlpUpdater.update()) {
                YtDlpUpdater.Outcome.UPDATED -> ProviderUpdateState.UPDATED
                YtDlpUpdater.Outcome.ALREADY_CURRENT -> ProviderUpdateState.ALREADY_CURRENT
                YtDlpUpdater.Outcome.BUSY -> ProviderUpdateState.BUSY
                YtDlpUpdater.Outcome.OFFLINE -> ProviderUpdateState.OFFLINE
                YtDlpUpdater.Outcome.FAILED -> ProviderUpdateState.FAILED
            }
        }
    }
}
