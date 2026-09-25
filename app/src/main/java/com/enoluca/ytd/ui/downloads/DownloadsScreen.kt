package com.enoluca.ytd.ui.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.Downloading
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.enoluca.ytd.data.local.db.DownloadEntity
import com.enoluca.ytd.data.model.DownloadStatus
import com.enoluca.ytd.data.model.FormatKind
import com.enoluca.ytd.data.platform.Platforms
import com.enoluca.ytd.download.ProgressDisplay
import com.enoluca.ytd.ui.components.EmptyState
import com.enoluca.ytd.ui.components.ScreenHeader
import com.enoluca.ytd.ui.components.ThumbnailImage
import com.enoluca.ytd.ui.glass.GlassProgressBar
import com.enoluca.ytd.ui.glass.GlassSnackbarHost
import com.enoluca.ytd.ui.glass.GlassSurface
import com.enoluca.ytd.ui.glass.ToastKind
import com.enoluca.ytd.ui.glass.sheetContainerColor
import com.enoluca.ytd.ui.glass.showToast
import com.enoluca.ytd.ui.theme.GalacticAuroraGreen
import com.enoluca.ytd.ui.theme.LocalResolvedTheme
import com.enoluca.ytd.ui.theme.TierBest
import com.enoluca.ytd.ui.theme.VisualStyle
import kotlinx.coroutines.launch

@Composable
fun DownloadsScreen(
    viewModel: DownloadsViewModel,
    contentPadding: PaddingValues,
    onGoHome: () -> Unit,
) {
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val waitingForWifi by viewModel.waitingForWifi.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun toast(message: String) = scope.launch { snackbar.showToast(message, ToastKind.Error) }

    // Success / failure toasts for downloads that finish while this screen is open. Only real
    // transitions (active → COMPLETED/FAILED) count, not rows that were already finished.
    val lastStatuses = remember { mutableMapOf<Long, DownloadStatus>() }
    LaunchedEffect(downloads) {
        downloads.forEach { entity ->
            val previous = lastStatuses[entity.id]
            if (previous?.isActive == true && entity.status == DownloadStatus.COMPLETED) {
                launch { snackbar.showToast("Download completed: ${entity.title}", ToastKind.Success) }
            } else if (previous?.isActive == true && entity.status == DownloadStatus.FAILED) {
                launch { snackbar.showToast("Download failed: ${entity.title}", ToastKind.Error) }
            }
            lastStatuses[entity.id] = entity.status
        }
    }

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
            ScreenHeader(
                title = "Downloads",
                subtitle = queueSummary(downloads),
                actions = { if (downloads.isNotEmpty()) QueueMenu(downloads, viewModel) },
            )

            if (downloads.isEmpty()) {
                EmptyState(
                    title = "No downloads yet",
                    message = "Paste a link on the Home tab to start your first download.",
                    icon = Icons.Filled.DownloadForOffline,
                    modifier = Modifier.padding(bottom = contentPadding.calculateBottomPadding()),
                    action = { FilledTonalButton(onClick = onGoHome) { Text("Go to Home") } },
                )
                return@Column
            }

            val sections = listOf(
                "In progress" to downloads.filter { it.status.isActive },
                // Waiting/Paused follow the real queue order (which the user can change).
                "Waiting" to downloads.filter { it.status == DownloadStatus.QUEUED }.sortedBy { it.effectiveQueuePosition },
                "Paused" to downloads.filter { it.status == DownloadStatus.PAUSED }.sortedBy { it.effectiveQueuePosition },
                "Failed" to downloads.filter { it.status == DownloadStatus.FAILED },
                "Cancelled" to downloads.filter { it.status == DownloadStatus.CANCELLED },
                "Completed" to downloads.filter { it.status == DownloadStatus.COMPLETED },
            ).filter { it.second.isNotEmpty() }

            LazyColumn(
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp, top = 4.dp,
                    bottom = contentPadding.calculateBottomPadding() + 8.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                sections.forEach { (label, items) ->
                    item(key = "header_$label") {
                        Text(
                            "$label (${items.size})",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                        )
                    }
                    items(items, key = { it.id }) { entity ->
                        DownloadCard(
                            entity = entity,
                            waitingForWifi = waitingForWifi,
                            onPause = { viewModel.pause(entity.id) },
                            onResume = { viewModel.resume(entity.id) },
                            onCancel = {
                                viewModel.cancel(entity.id)
                                scope.launch { snackbar.showToast("Download cancelled") }
                            },
                            onMoveUp = if (entity.status == DownloadStatus.QUEUED && items.firstOrNull() != entity) {
                                { viewModel.move(entity.id, up = true) }
                            } else {
                                null
                            },
                            onMoveDown = if (entity.status == DownloadStatus.QUEUED && items.lastOrNull() != entity) {
                                { viewModel.move(entity.id, up = false) }
                            } else {
                                null
                            },
                            onRetry = { viewModel.retry(entity.id) },
                            onDelete = { viewModel.deleteRecord(entity.id) },
                            onOpen = {
                                val uri = entity.fileUri
                                if (uri == null || !FileActions.open(context, uri, "${entity.fileBaseName}.${entity.container}")) {
                                    toast("No app on this device can open this file, or it was moved.")
                                }
                            },
                            onShare = {
                                val uri = entity.fileUri
                                if (uri == null || !FileActions.share(context, uri, "${entity.fileBaseName}.${entity.container}", entity.title)) {
                                    toast("Couldn't share this file.")
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadCard(
    entity: DownloadEntity,
    waitingForWifi: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null,
) {
    GlassSurface(modifier = Modifier.fillMaxWidth(), cornerRadius = 22.dp) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ThumbnailImage(
                    url = entity.thumbnailUrl,
                    contentDescription = null,
                    modifier = Modifier.size(width = 96.dp, height = 54.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(entity.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                    Text(
                        listOfNotNull(
                            Platforms.detect(entity.webpageUrl).displayName,
                            playlistLabel(entity),
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusChip(entity.status)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            qualityLabel(entity),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            }

            ProgressSection(entity, waitingForWifi)

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                // Priority: move waiting items up/down the queue.
                onMoveUp?.let { IconButton(onClick = it) { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move up in queue") } }
                onMoveDown?.let { IconButton(onClick = it) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move down in queue") } }
                Spacer(Modifier.weight(1f))
                DownloadActions(entity, onPause, onResume, onCancel, onRetry, onDelete, onOpen, onShare)
            }
        }
    }
}

@Composable
private fun ProgressSection(entity: DownloadEntity, waitingForWifi: Boolean) {
    when (entity.status) {
        DownloadStatus.FETCHING_INFO, DownloadStatus.DOWNLOADING, DownloadStatus.PROCESSING -> {
            Spacer(Modifier.height(10.dp))
            val retrying = entity.errorMessage != null
            // Determinate only with a real total from yt-dlp; indeterminate while fetching info,
            // processing, retrying or when the size isn't known yet.
            GlassProgressBar(progress = ProgressDisplay.fraction(entity))
            Spacer(Modifier.height(6.dp))
            Text(
                ProgressDisplay.text(entity),
                style = MaterialTheme.typography.bodySmall,
                color = if (retrying) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DownloadStatus.QUEUED -> {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (waitingForWifi) {
                    Icon(Icons.Filled.Wifi, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    if (waitingForWifi) "Waiting for Wi-Fi (Settings › Network)" else "Waiting for a free download slot",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        DownloadStatus.PAUSED -> {
            Spacer(Modifier.height(10.dp))
            GlassProgressBar(progress = entity.progressPercent / 100f, muted = true)
            Spacer(Modifier.height(6.dp))
            Text(
                if (entity.progressPercent > 0f) "Paused at ${Formatting.percent(entity.progressPercent)}" else "Saved for later",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DownloadStatus.FAILED -> {
            Spacer(Modifier.height(8.dp))
            Text(
                entity.errorMessage ?: "Download failed. You can retry this item.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        DownloadStatus.COMPLETED -> {
            Spacer(Modifier.height(6.dp))
            Text(
                listOfNotNull(
                    entity.totalBytes?.let { Formatting.bytes(it) },
                    Formatting.relativeTime(entity.completedAt ?: entity.updatedAt),
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DownloadStatus.CANCELLED -> {
            Spacer(Modifier.height(8.dp))
            Text("Cancelled. You can retry this item.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** "Playlist 4/18 · My mix" for items that came from a playlist. */
private fun playlistLabel(entity: DownloadEntity): String? {
    if (entity.batchId == null) return null
    val position = entity.batchIndex?.let { index -> entity.batchSize?.let { "$index/$it" } ?: "#$index" }
    return listOfNotNull("Playlist", position, entity.batchTitle).joinToString(" ")
}

@Composable
private fun StatusChip(status: DownloadStatus) {
    val galactic = LocalResolvedTheme.current.style == VisualStyle.GALACTIC
    val scheme = MaterialTheme.colorScheme
    // Icon + label for every state, so status never depends on color alone.
    val (label, color, icon) = when (status) {
        DownloadStatus.FETCHING_INFO -> Triple("Fetching info", scheme.primary, Icons.Filled.Schedule)
        DownloadStatus.DOWNLOADING -> Triple("Downloading", scheme.primary, Icons.Filled.Downloading)
        DownloadStatus.PROCESSING -> Triple("Processing", scheme.primary, Icons.Filled.Downloading)
        DownloadStatus.QUEUED -> Triple("Pending", scheme.outline, Icons.Filled.Schedule)
        DownloadStatus.PAUSED -> Triple("Paused", scheme.outline, Icons.Filled.Pause)
        DownloadStatus.FAILED -> Triple("Failed", scheme.error, Icons.Filled.ErrorOutline)
        DownloadStatus.COMPLETED -> Triple("Done", if (galactic) GalacticAuroraGreen else TierBest, Icons.Filled.CheckCircle)
        DownloadStatus.CANCELLED -> Triple("Cancelled", scheme.outline, Icons.Filled.Close)
    }
    val labelColor = if (status == DownloadStatus.QUEUED || status == DownloadStatus.PAUSED) scheme.onSurfaceVariant else color
    Surface(shape = RoundedCornerShape(6.dp), color = color.copy(alpha = 0.15f)) {
        Row(Modifier.padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = labelColor, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(4.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = labelColor)
        }
    }
}

/** "2 downloading · 3 waiting" — or null when nothing is pending. */
private fun queueSummary(downloads: List<DownloadEntity>): String? {
    val running = downloads.count { it.status.isActive }
    val waiting = downloads.count { it.status == DownloadStatus.QUEUED }
    val paused = downloads.count { it.status == DownloadStatus.PAUSED }
    return listOfNotNull(
        running.takeIf { it > 0 }?.let { "$it downloading" },
        waiting.takeIf { it > 0 }?.let { "$it waiting" },
        paused.takeIf { it > 0 }?.let { "$it paused" },
    ).joinToString(" · ").ifEmpty { null }
}

/** Queue-wide actions, kept out of the way in an overflow menu. */
@Composable
private fun QueueMenu(downloads: List<DownloadEntity>, viewModel: DownloadsViewModel) {
    var open by remember { mutableStateOf(false) }
    val hasActive = downloads.any { it.status.isActive || it.status == DownloadStatus.QUEUED }
    val hasPaused = downloads.any { it.status == DownloadStatus.PAUSED }
    val hasFailed = downloads.any { it.status == DownloadStatus.FAILED }
    val hasCancelled = downloads.any { it.status == DownloadStatus.CANCELLED }
    val hasPending = hasActive || hasPaused
    val hasCompleted = downloads.any { it.status == DownloadStatus.COMPLETED }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Queue actions") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = sheetContainerColor()) {
            @Composable
            fun entry(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean, action: () -> Unit) =
                DropdownMenuItem(
                    text = { Text(label) },
                    leadingIcon = { Icon(icon, null) },
                    enabled = enabled,
                    onClick = {
                        open = false
                        action()
                    },
                )
            entry("Pause all", Icons.Filled.Pause, hasActive) { viewModel.pauseAll() }
            entry("Resume all", Icons.Filled.PlayArrow, hasPaused) { viewModel.resumeAll() }
            entry("Retry failed", Icons.Filled.Refresh, hasFailed) { viewModel.retryAllFailed() }
            entry("Cancel all", Icons.Filled.Close, hasPending) { viewModel.cancelAll() }
            entry("Delete completed", Icons.Filled.Delete, hasCompleted) { viewModel.clearFinished() }
            entry("Remove cancelled", Icons.Filled.Delete, hasCancelled) { viewModel.clearCancelled() }
        }
    }
}

private fun qualityLabel(entity: DownloadEntity): String = when (entity.formatKind) {
    FormatKind.AUDIO -> when {
        !entity.requiresAudioExtraction -> entity.container?.uppercase() ?: "Audio"
        else -> entity.audioBitrateKbps?.let { "MP3 · $it kbps" } ?: "MP3 · VBR"
    }
    FormatKind.VIDEO -> listOfNotNull(entity.resolutionLabel, entity.container?.uppercase()).joinToString(" · ")
}

@Composable
private fun DownloadActions(
    entity: DownloadEntity,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
) {
    when (entity.status) {
        DownloadStatus.FETCHING_INFO, DownloadStatus.DOWNLOADING -> {
            TextButton(onClick = onPause) { Icon(Icons.Filled.Pause, null); Spacer(Modifier.width(6.dp)); Text("Pause") }
            TextButton(onClick = onCancel) { Icon(Icons.Filled.Close, null); Spacer(Modifier.width(6.dp)); Text("Cancel") }
        }
        DownloadStatus.PROCESSING -> {
            TextButton(onClick = onCancel) { Icon(Icons.Filled.Close, null); Spacer(Modifier.width(6.dp)); Text("Cancel") }
        }
        DownloadStatus.QUEUED -> {
            TextButton(onClick = onCancel) { Icon(Icons.Filled.Close, null); Spacer(Modifier.width(6.dp)); Text("Cancel") }
        }
        DownloadStatus.PAUSED -> {
            TextButton(onClick = onResume) { Icon(Icons.Filled.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Resume") }
            TextButton(onClick = onCancel) { Icon(Icons.Filled.Close, null); Spacer(Modifier.width(6.dp)); Text("Cancel") }
        }
        DownloadStatus.FAILED -> {
            TextButton(onClick = onRetry) { Icon(Icons.Filled.Refresh, null); Spacer(Modifier.width(6.dp)); Text("Retry") }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Remove from list") }
        }
        DownloadStatus.COMPLETED -> {
            TextButton(onClick = onOpen) { Text("Open") }
            IconButton(onClick = onShare) { Icon(Icons.Filled.Share, contentDescription = "Share") }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Remove from list") }
        }
        DownloadStatus.CANCELLED -> {
            TextButton(onClick = onRetry) { Icon(Icons.Filled.Refresh, null); Spacer(Modifier.width(6.dp)); Text("Retry") }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Remove from list") }
        }
    }
}
