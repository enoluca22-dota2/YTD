package com.enoluca.ytd.ui.formats

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.enoluca.ytd.core.Formatting
import com.enoluca.ytd.data.model.PlaylistEntry
import com.enoluca.ytd.data.model.PlaylistInfo
import com.enoluca.ytd.data.model.QuickFormat
import com.enoluca.ytd.data.platform.ContentKind
import com.enoluca.ytd.data.platform.DetectedPlatform
import com.enoluca.ytd.ui.components.PlatformBadge
import com.enoluca.ytd.ui.components.ThumbnailImage
import com.enoluca.ytd.ui.glass.ActionState
import com.enoluca.ytd.ui.glass.GlassChip
import com.enoluca.ytd.ui.glass.GlassSnackbarHost
import com.enoluca.ytd.ui.glass.GlassStyle
import com.enoluca.ytd.ui.glass.GlassSurface
import com.enoluca.ytd.ui.glass.PrimaryActionButton
import com.enoluca.ytd.ui.glass.ToastKind
import com.enoluca.ytd.ui.glass.showToast
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Playlist / album / channel / multi-item post. The user picks items and one quality; every
 * selected item then goes into the normal download queue as its own job (no separate system).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionScreen(
    playlist: PlaylistInfo,
    detected: DetectedPlatform,
    /** Entry the link pointed at (`watch?v=…&list=…`): highlighted and scrolled into view. */
    focusedEntry: Int? = null,
    findDownloaded: suspend (List<String>) -> Set<String>,
    onBack: () -> Unit,
    onDownload: suspend (entries: List<PlaylistEntry>, choice: QuickFormat) -> Int,
    onQueued: () -> Unit,
) {
    val entries = playlist.entries
    val available = remember(entries) { entries.indices.filter { entries[it].isAvailable } }
    val unavailableCount = entries.size - available.size
    // Selection by index (stable, saveable). Everything available starts selected except items
    // already downloaded; private/deleted items can't be selected.
    var selected by rememberSaveable { mutableStateOf(available) }
    val listState = rememberLazyListState()
    var downloaded by remember { mutableStateOf(emptySet<String>()) }
    var choiceIndex by rememberSaveable { mutableIntStateOf(0) }
    var submitState by remember { mutableStateOf(ActionState.Idle) }
    val scope = rememberCoroutineScope()
    val toasts = remember { SnackbarHostState() }

    BackHandler(onBack = onBack)

    LaunchedEffect(playlist) {
        downloaded = findDownloaded(entries.mapNotNull { it.url })
        if (downloaded.isNotEmpty()) {
            selected = selected.filter { entries[it].url !in downloaded }
        }
    }
    LaunchedEffect(focusedEntry) {
        focusedEntry?.let { listState.scrollToItem(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (detected.kind == ContentKind.PLAYLIST) "Playlist" else "Choose items") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
        snackbarHost = { GlassSnackbarHost(toasts) },
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            GlassSurface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), cornerRadius = 24.dp, style = GlassStyle.Clear) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    PlatformBadge(detected)
                    Text(playlist.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(
                            playlist.uploader,
                            "${entries.size} item${if (entries.size == 1) "" else "s"}",
                            unavailableCount.takeIf { it > 0 }?.let { "$it unavailable" },
                        ).joinToString(" • "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${selected.size} selected", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f).padding(start = 8.dp))
                TextButton(onClick = { selected = available }) { Text("Select all") }
                TextButton(onClick = { selected = emptyList() }) { Text("Deselect all") }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(entries, key = { i, _ -> i }) { i, entry ->
                    val checked = i in selected
                    val focused = i == focusedEntry
                    GlassSurface(Modifier.fillMaxWidth(), cornerRadius = 16.dp, selected = checked || focused) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .toggleable(
                                    value = checked,
                                    enabled = entry.isAvailable,
                                    role = Role.Checkbox,
                                    onValueChange = { on -> selected = if (on) (selected + i).sorted() else selected - i },
                                )
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = checked, onCheckedChange = null, enabled = entry.isAvailable)
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "${entry.index}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(28.dp),
                                maxLines = 1,
                            )
                            ThumbnailImage(entry.thumbnailUrl, null, Modifier.size(width = 72.dp, height = 40.dp), cornerRadius = 8.dp)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(entry.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    listOfNotNull(
                                        if (focused) "Opened video" else null,
                                        entry.unavailableReason,
                                        entry.durationSeconds?.let { Formatting.duration(it) },
                                        if (entry.url != null && entry.url in downloaded) "Already downloaded" else null,
                                    ).joinToString(" • "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (entry.isAvailable) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }

            GlassSurface(Modifier.fillMaxWidth().padding(12.dp), cornerRadius = 24.dp) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Quality", style = MaterialTheme.typography.labelLarge)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(QuickFormat.choices.size) { i ->
                            GlassChip(QuickFormat.choices[i].label, selected = choiceIndex == i, onClick = { choiceIndex = i })
                        }
                    }
                    Text(
                        "Each item gets the best stream up to this quality that it really offers.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    PrimaryActionButton(
                        text = "Download selected (${selected.size})",
                        icon = Icons.Filled.Download,
                        enabled = selected.isNotEmpty(),
                        state = submitState,
                        loadingText = "Adding…",
                        successText = "Added to downloads",
                        onClick = {
                            submitState = ActionState.Loading
                            scope.launch {
                                try {
                                    onDownload(selected.map { entries[it] }, QuickFormat.choices[choiceIndex])
                                    submitState = ActionState.Success
                                    delay(450)
                                    onQueued()
                                } catch (e: Exception) {
                                    submitState = ActionState.Error
                                    toasts.showToast("Couldn't add the downloads. Please try again.", ToastKind.Error)
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
