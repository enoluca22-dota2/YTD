package com.enoluca.ytd.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.enoluca.ytd.core.FileActions
import com.enoluca.ytd.core.Formatting
import com.enoluca.ytd.data.local.db.HistoryEntity
import com.enoluca.ytd.data.model.DownloadCategory
import com.enoluca.ytd.data.model.DownloadStatus
import com.enoluca.ytd.data.platform.Platforms
import com.enoluca.ytd.ui.components.EmptyState
import com.enoluca.ytd.ui.components.ScreenHeader
import com.enoluca.ytd.ui.components.ThumbnailImage
import com.enoluca.ytd.ui.glass.GlassSnackbarHost
import com.enoluca.ytd.ui.glass.GlassSurface
import com.enoluca.ytd.ui.glass.ToastKind
import com.enoluca.ytd.ui.glass.sheetContainerColor
import com.enoluca.ytd.ui.glass.showToast
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

@Composable
fun HistoryScreen(viewModel: HistoryViewModel, contentPadding: PaddingValues, onRedownload: (String) -> Unit) {
    val history by viewModel.history.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var pendingDelete by remember { mutableStateOf<HistoryEntity?>(null) }
    fun toast(message: String, kind: ToastKind = ToastKind.Error) = scope.launch { snackbar.showToast(message, kind) }

    Scaffold(
        // Only the top inset here: lists pad the bottom themselves so they scroll under the
        // floating glass bar. Transparent so the glass background shows through.
        modifier = Modifier.padding(top = contentPadding.calculateTopPadding()),
        snackbarHost = { GlassSnackbarHost(snackbar, Modifier.padding(bottom = contentPadding.calculateBottomPadding())) },
        contentWindowInsets = WindowInsets(0),
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner)) {
            ScreenHeader(title = "History", subtitle = if (history.isEmpty()) null else historySummary(history))

            if (history.isEmpty()) {
                EmptyState(
                    title = "No history yet",
                    message = "Completed, failed and cancelled downloads show up here so you can open, share or retry them later.",
                    icon = Icons.Filled.History,
                    modifier = Modifier.padding(bottom = contentPadding.calculateBottomPadding()),
                )
                return@Column
            }

            LazyColumn(
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp, top = 4.dp,
                    bottom = contentPadding.calculateBottomPadding() + 8.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(history, key = { it.id }) { entry ->
                    HistoryCard(
                        entry = entry,
                        onOpen = {
                            val uri = entry.fileUri
                            if (uri == null || !FileActions.open(context, uri, entry.filename)) {
                                toast("No app on this device can open this file, or it was moved.")
                            }
                        },
                        onShare = {
                            val uri = entry.fileUri
                            if (uri == null || !FileActions.share(context, uri, entry.filename, entry.title)) {
                                toast("Couldn't share this file.")
                            }
                        },
                        onDelete = { pendingDelete = entry },
                        onRedownload = { onRedownload(entry.webpageUrl.ifBlank { entry.sourceUrl }) },
                    )
                }
            }
        }
    }

    pendingDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            containerColor = sheetContainerColor(),
            title = { Text("Remove \"${entry.title}\"?") },
            text = { Text("You can remove it from History only, or also delete the downloaded file from your device.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    viewModel.delete(entry, deleteFile = true) { deleted ->
                        if (deleted) {
                            toast("File deleted.", ToastKind.Success)
                        } else {
                            toast("Removed from History. The file was already gone or couldn't be deleted.")
                        }
                    }
                }) { Text("Delete file too") }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    viewModel.delete(entry, deleteFile = false)
                }) { Text("Remove from History") }
            },
        )
    }
}

@Composable
private fun HistoryCard(
    entry: HistoryEntity,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onRedownload: () -> Unit,
) {
    GlassSurface(modifier = Modifier.fillMaxWidth(), cornerRadius = 22.dp) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ThumbnailImage(
                    url = entry.thumbnailUrl,
                    contentDescription = null,
                    modifier = Modifier.size(width = 96.dp, height = 54.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(entry.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                    Text(
                        Platforms.detect(entry.webpageUrl.ifBlank { entry.sourceUrl }).displayName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (entry.category == DownloadCategory.MUSIC) Icons.Filled.MusicNote else Icons.Filled.Movie,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            listOfNotNull(entry.resolutionLabel, entry.formatLabel, entry.fileSizeBytes?.let { Formatting.bytes(it) })
                                .joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(entry.completedAt)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    when (entry.status) {
                        DownloadStatus.COMPLETED -> entry.location?.let {
                            Text(
                                "Saved in $it · ${entry.filename}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        DownloadStatus.FAILED -> Text(
                            "Failed" + (entry.errorMessage?.let { " · $it" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        DownloadStatus.CANCELLED -> Text(
                            "Cancelled",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        else -> Unit
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                if (entry.fileUri != null) {
                    TextButton(onClick = onOpen) { Text("Open") }
                    IconButton(onClick = onShare) { Icon(Icons.Filled.Share, contentDescription = "Share") }
                }
                IconButton(onClick = onRedownload) { Icon(Icons.Filled.Refresh, contentDescription = "Download again") }
                IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Delete") }
            }
        }
    }
}

/** "12 completed · 1 failed · 2 cancelled" */
private fun historySummary(history: List<HistoryEntity>): String {
    val completed = history.count { it.status == DownloadStatus.COMPLETED }
    val failed = history.count { it.status == DownloadStatus.FAILED }
    val cancelled = history.count { it.status == DownloadStatus.CANCELLED }
    return listOfNotNull(
        "$completed completed",
        failed.takeIf { it > 0 }?.let { "$it failed" },
        cancelled.takeIf { it > 0 }?.let { "$it cancelled" },
    ).joinToString(" · ")
}
