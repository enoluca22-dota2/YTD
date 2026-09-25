package com.enoluca.ytd.ui.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.enoluca.ytd.data.local.db.DownloadEntity
import com.enoluca.ytd.data.model.DownloadStatus
import com.enoluca.ytd.data.repository.DownloadRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DownloadsViewModel(private val repository: DownloadRepository) : ViewModel() {

    val downloads: StateFlow<List<DownloadEntity>> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val waitingForWifi: StateFlow<Boolean> = repository.waitingForWifi

    fun pause(id: Long) = viewModelScope.launch { repository.pause(id) }
    fun resume(id: Long) = viewModelScope.launch { repository.resume(id) }
    fun cancel(id: Long) = viewModelScope.launch { repository.cancel(id) }
    fun retry(id: Long) = viewModelScope.launch { repository.retry(id) }
    fun deleteRecord(id: Long) = viewModelScope.launch { repository.deleteRecord(id) }

    fun pauseAll() = viewModelScope.launch { repository.pauseAll() }
    fun resumeAll() = viewModelScope.launch { repository.resumeAll() }
    fun cancelAll() = viewModelScope.launch { repository.cancelAll() }
    fun retryAllFailed() = viewModelScope.launch { repository.retryAllFailed() }
    fun move(id: Long, up: Boolean) = viewModelScope.launch { repository.move(id, up) }

    /** Removes completed entries from this list; the files and History stay untouched. */
    fun clearFinished() = viewModelScope.launch {
        downloads.value.filter { it.status == DownloadStatus.COMPLETED }.forEach { repository.deleteRecord(it.id) }
    }

    fun clearCancelled() = viewModelScope.launch {
        downloads.value.filter { it.status == DownloadStatus.CANCELLED }.forEach { repository.deleteRecord(it.id) }
    }
}
