package com.enoluca.ytd.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistRemove
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.enoluca.ytd.core.Formatting
import com.enoluca.ytd.data.local.db.LibraryMediaEntity
import com.enoluca.ytd.data.local.db.MediaType
import com.enoluca.ytd.data.local.db.PlaylistSummary
import com.enoluca.ytd.ui.glass.GlassSurface
import com.enoluca.ytd.ui.glass.sheetContainerColor
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs

// ---------------------------------------------------------------------------------------------
// Artwork
// ---------------------------------------------------------------------------------------------

/** A cached artwork path, a remote thumbnail URL, or null. */
internal fun artworkData(model: String?): Any? = when {
    model.isNullOrBlank() -> null
    model.startsWith("/") -> File(model)
    else -> model
}

/** ENAGELYUCA's own fallback artwork: a gradient picked from the title, with a note or film icon. */
private val placeholderPalettes = listOf(
    Color(0xFFFF6A3D) to Color(0xFF8E2DE2),
    Color(0xFF1FA2FF) to Color(0xFF12D8FA),
    Color(0xFFFF4E7A) to Color(0xFFFFB86B),
    Color(0xFF6A5AE0) to Color(0xFF00C6A7),
    Color(0xFF2B5876) to Color(0xFF4E4376),
    Color(0xFFF7971E) to Color(0xFFFF4E50),
)

@Composable
fun ArtworkPlaceholder(title: String, isVideo: Boolean, modifier: Modifier = Modifier) {
    val (a, b) = placeholderPalettes[abs(title.hashCode()) % placeholderPalettes.size]
    Box(
        modifier.background(Brush.linearGradient(listOf(a, b))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (isVideo) Icons.Filled.Movie else Icons.Filled.MusicNote,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.9f),
            modifier = Modifier.fillMaxSize(0.42f),
        )
    }
}

/** Artwork (cached file / thumbnail) over the generated placeholder, which shows while loading or if there's none. */
@Composable
fun MediaArtwork(
    model: String?,
    title: String,
    isVideo: Boolean,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 12.dp,
) {
    val shape = RoundedCornerShape(cornerRadius)
    Box(modifier.clip(shape)) {
        ArtworkPlaceholder(title, isVideo, Modifier.fillMaxSize())
        val data = artworkData(model)
        if (data != null) {
            AsyncImage(model = data, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Rows & cards
// ---------------------------------------------------------------------------------------------

internal fun LibraryMediaEntity.durationLabel(): String? = durationMs?.takeIf { it > 0 }?.let { Formatting.duration(it / 1000) }

/** "Artist · 3:45"; [withDuration] false for videos, whose thumbnail already shows it. */
internal fun LibraryMediaEntity.subtitleLabel(withDuration: Boolean = true): String = listOfNotNull(
    artist ?: sourcePlatform ?: location,
    durationLabel()?.takeIf { withDuration },
).joinToString(" · ").ifEmpty { if (mediaType == MediaType.VIDEO) "Video" else "Audio" }

/**
 * One Library item. Videos get a 16:9 thumbnail, music a square cover. Missing files are dimmed
 * and say so. [leading] is used for playlist numbering ("01").
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaRow(
    item: LibraryMediaEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    onMore: (() -> Unit)? = null,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    playing: Boolean = false,
    leading: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val isVideo = item.mediaType == MediaType.VIDEO
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) scheme.primary.copy(alpha = 0.14f) else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = "Select")
            .semantics { if (selectionMode) stateDescription = if (selected) "Selected" else "Not selected" }
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .alpha(if (item.isAvailable) 1f else 0.5f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionMode) {
            Icon(
                if (selected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (selected) scheme.primary else scheme.outline,
                modifier = Modifier.padding(end = 10.dp),
            )
        }
        if (leading != null) {
            Text(
                leading,
                style = MaterialTheme.typography.labelLarge,
                color = if (playing) scheme.primary else scheme.onSurfaceVariant,
                modifier = Modifier.width(28.dp),
            )
        }
        Box {
            MediaArtwork(
                model = item.artworkModel,
                title = item.title,
                isVideo = isVideo,
                modifier = if (isVideo) Modifier.size(width = 96.dp, height = 54.dp) else Modifier.size(54.dp),
                cornerRadius = 10.dp,
            )
            if (isVideo) {
                item.durationLabel()?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(3.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color.Black.copy(alpha = 0.65f))
                            .padding(horizontal = 4.dp),
                    )
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (playing) FontWeight.Bold else FontWeight.Medium,
                color = if (playing) scheme.primary else scheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (item.favorite) {
                    Icon(Icons.Filled.Favorite, contentDescription = "Favorite", tint = scheme.primary, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(4.dp))
                }
                if (!item.isAvailable) {
                    Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = scheme.error, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    if (item.isAvailable) item.subtitleLabel(withDuration = !isVideo) else "File missing",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (item.isAvailable) scheme.onSurfaceVariant else scheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing?.invoke()
        if (onMore != null && !selectionMode) {
            IconButton(onClick = onMore) { Icon(Icons.Filled.MoreVert, contentDescription = "More options for ${item.title}") }
        }
    }
}

/** Playlist tile for the grid: cover, title, "12 items · 48 min". */
@Composable
fun PlaylistCard(summary: PlaylistSummary, onClick: () -> Unit, modifier: Modifier = Modifier) {
    GlassSurface(modifier.fillMaxWidth(), cornerRadius = 22.dp, onClick = onClick, onClickLabel = "Open playlist") {
        Column(Modifier.padding(10.dp)) {
            Box {
                MediaArtwork(
                    model = summary.coverModel,
                    title = summary.playlist.title,
                    isVideo = false,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                    cornerRadius = 16.dp,
                )
                if (summary.playlist.favorite) {
                    Icon(
                        Icons.Filled.Favorite,
                        contentDescription = "Favorite playlist",
                        tint = Color.White,
                        modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(18.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(summary.playlist.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                playlistSubtitle(summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

internal fun playlistSubtitle(summary: PlaylistSummary): String = listOfNotNull(
    if (summary.itemCount == 1) "1 item" else "${summary.itemCount} items",
    summary.totalDurationMs.takeIf { it > 0 }?.let { totalDurationLabel(it) },
    summary.playlist.sourcePlatform,
).joinToString(" · ")

internal fun totalDurationLabel(ms: Long): String {
    val minutes = ms / 60_000
    return if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "${minutes.coerceAtLeast(1)} min"
}

@Composable
fun NewPlaylistCard(onClick: () -> Unit, modifier: Modifier = Modifier) {
    GlassSurface(modifier.fillMaxWidth(), cornerRadius = 22.dp, onClick = onClick, onClickLabel = "Create playlist") {
        Column(Modifier.padding(10.dp)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
            }
            Spacer(Modifier.height(8.dp))
            Text("New playlist", style = MaterialTheme.typography.titleSmall)
            Text("Collect your media", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Sheets & dialogs
// ---------------------------------------------------------------------------------------------

/** What can be done with one item. Optional actions are hidden when their callback is null. */
data class MediaActions(
    val onPlay: (() -> Unit)? = null,
    val onPlayNext: (() -> Unit)? = null,
    val onAddToQueue: (() -> Unit)? = null,
    val onAddToPlaylist: (() -> Unit)? = null,
    val onToggleFavorite: (() -> Unit)? = null,
    val onRemoveFromPlaylist: (() -> Unit)? = null,
    val onInfo: (() -> Unit)? = null,
    val onShare: (() -> Unit)? = null,
    val onDelete: (() -> Unit)? = null,
    val onForget: (() -> Unit)? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaActionsSheet(item: LibraryMediaEntity, actions: MediaActions, onDismiss: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = sheetContainerColor()) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                MediaArtwork(
                    item.artworkModel, item.title, item.mediaType == MediaType.VIDEO,
                    Modifier.size(56.dp), cornerRadius = 12.dp,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(item.subtitleLabel(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            fun act(block: () -> Unit): () -> Unit = { onDismiss(); block() }
            if (item.isAvailable) {
                actions.onPlay?.let { SheetAction(Icons.Filled.PlayArrow, "Play", act(it)) }
                actions.onPlayNext?.let { SheetAction(Icons.AutoMirrored.Filled.PlaylistPlay, "Play next", act(it)) }
                actions.onAddToQueue?.let { SheetAction(Icons.AutoMirrored.Filled.QueueMusic, "Add to queue", act(it)) }
                actions.onAddToPlaylist?.let { SheetAction(Icons.AutoMirrored.Filled.PlaylistAdd, "Add to playlist", act(it)) }
            }
            actions.onToggleFavorite?.let {
                SheetAction(
                    if (item.favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    if (item.favorite) "Remove from Favorites" else "Add to Favorites",
                    act(it),
                )
            }
            actions.onRemoveFromPlaylist?.let { SheetAction(Icons.Filled.PlaylistRemove, "Remove from this playlist", act(it)) }
            actions.onInfo?.let { SheetAction(Icons.Filled.Info, "File information", act(it)) }
            if (item.isAvailable) actions.onShare?.let { SheetAction(Icons.Filled.Share, "Share", act(it)) }
            if (!item.isAvailable) actions.onForget?.let { SheetAction(Icons.Filled.Delete, "Remove from Library", act(it)) }
            if (item.isAvailable) actions.onDelete?.let { SheetAction(Icons.Filled.Delete, "Delete from device", act(it), destructive = true) }
        }
    }
}

@Composable
private fun SheetAction(icon: ImageVector, label: String, onClick: () -> Unit, destructive: Boolean = false) {
    val color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = color)
        Spacer(Modifier.width(18.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = color)
    }
}

/** Pick a playlist for [count] item(s), or create a new one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToPlaylistSheet(
    playlists: List<PlaylistSummary>,
    count: Int,
    onPick: (PlaylistSummary) -> Unit,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var naming by rememberSaveable { mutableStateOf(false) }
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = sheetContainerColor()) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            Text(
                if (count == 1) "Add to playlist" else "Add $count items to playlist",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            SheetAction(Icons.Filled.Add, "New playlist", { naming = true })
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(playlists, key = { it.playlist.id }) { summary ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(summary); onDismiss() }
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MediaArtwork(summary.coverModel, summary.playlist.title, false, Modifier.size(48.dp), cornerRadius = 10.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(summary.playlist.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(playlistSubtitle(summary), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
    if (naming) {
        NameDialog(
            title = "New playlist",
            initial = "",
            confirmLabel = "Create",
            onDismiss = { naming = false },
            onConfirm = { name ->
                naming = false
                onCreate(name)
                onDismiss()
            },
        )
    }
}

/** Create / rename a playlist. */
@Composable
fun NameDialog(title: String, initial: String, confirmLabel: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = sheetContainerColor(),
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(120) },
                singleLine = true,
                label = { Text("Name") },
                modifier = Modifier.semantics { contentDescription = "Playlist name" },
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(text.trim()) }, enabled = text.isNotBlank()) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ConfirmDialog(title: String, message: String, confirmLabel: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = sheetContainerColor(),
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = { onDismiss(); onConfirm() }) { Text(confirmLabel, color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** "File information" for a Library item. */
@Composable
fun FileInfoDialog(item: LibraryMediaEntity, onDismiss: () -> Unit) {
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    val rows = listOfNotNull(
        "Title" to item.title,
        item.artist?.let { "Artist" to it },
        item.album?.let { "Album" to it },
        "Type" to listOfNotNull(if (item.mediaType == MediaType.VIDEO) "Video" else "Audio", item.mimeType).joinToString(" · "),
        item.durationLabel()?.let { "Duration" to it },
        item.sizeBytes?.let { "Size" to Formatting.bytes(it) },
        item.fileName?.let { "File" to it },
        item.location?.let { "Location" to it },
        "Added" to dateFormat.format(Date(item.dateAdded)),
        item.sourcePlatform?.let { "Source" to it },
        item.sourceUrl?.let { "Link" to it },
        "Played" to if (item.playCount == 1) "Once" else "${item.playCount} times",
        item.lastPlayedAt?.let { "Last played" to dateFormat.format(Date(it)) },
        if (!item.isAvailable) "Status" to "File missing" else null,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = sheetContainerColor(),
        title = { Text("File information") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(rows) { (label, value) ->
                    Column {
                        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text(value, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
