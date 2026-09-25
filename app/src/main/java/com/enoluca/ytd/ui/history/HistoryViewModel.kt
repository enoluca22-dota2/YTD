package com.enoluca.ytd.ui.history

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.enoluca.ytd.core.FileActions
import com.enoluca.ytd.data.local.db.HistoryEntity
import com.enoluca.ytd.data.repository.HistoryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HistoryViewModel(
    private val appContext: Context,
    private val repository: HistoryRepository,
) : ViewModel() {
    val history: StateFlow<List<HistoryEntity>> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Removes the entry; with [deleteFile] also deletes the downloaded file. Reports whether the file could be deleted. */
    fun delete(entry: HistoryEntity, deleteFile: Boolean, onResult: (fileDeleted: Boolean) -> Unit = {}) = viewModelScope.launch {
        val fileDeleted = if (deleteFile && entry.fileUri != null) {
            withContext(Dispatchers.IO) { FileActions.delete(appContext, entry.fileUri) }
        } else {
            false
        }
        repository.delete(entry.id)
        if (deleteFile) onResult(fileDeleted)
    }

    fun clearAll() = viewModelScope.launch { repository.clearAll() }
}
