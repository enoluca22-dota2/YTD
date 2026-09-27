package com.enoluca.ytd.ui.player

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import com.enoluca.ytd.playback.PlayerConnection
import com.enoluca.ytd.playback.PlayerUiState
import com.enoluca.ytd.playback.QueueEntry
import com.enoluca.ytd.ui.glass.GlassSnackbarHost
import com.enoluca.ytd.ui.glass.sheetContainerColor
import com.enoluca.ytd.ui.library.LibraryViewModel
import com.enoluca.ytd.ui.library.MediaArtwork
import com.enoluca.ytd.ui.library.MediaInteractionsHost
import com.enoluca.ytd.ui.library.rememberMediaInteractions
import kotlinx.coroutines.flow.flowOf

/** Full-screen music player: artwork, title, seek bar, transport, shuffle/repeat, queue, "More". */
@Composable
fun NowPlayingScreen(
    state: PlayerUiState,
    connection: PlayerConnection,
    viewModel: LibraryViewModel,
    contentPadding: PaddingValues,
    onClose: () -> Unit,
    onOpenVideo: () -> Unit,
    onOpenRadio: () -> Unit,
) {
    val current = state.current
    // Nothing left to play (queue cleared, item deleted): close.
    LaunchedEffect(state.connected, current) {
        if (state.connected && current == null) onClose()
    }
    // A radio station started (e.g. from the notification or Bluetooth): it has its own player.
    LaunchedEffect(current?.isRadio) {
        if (current?.isRadio == true) onOpenRadio()
    }
    val mediaFlow = remember(current?.mediaId) { current?.mediaId?.let(viewModel::observeMedia) ?: flowOf(null) }
    val media by mediaFlow.collectAsStateWithLifecycle(initialValue = null)
    val snackbar = remember { SnackbarHostState() }
    val interactions = rememberMediaInteractions()
    var queueOpen by rememberSaveable { mutableStateOf(false) }

    MediaInteractionsHost(interactions, viewModel, snackbar, onPlay = null)

    Scaffold(
        snackbarHost = { GlassSnackbarHost(snackbar) },
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
        contentWindowInsets = WindowInsets(0),
    ) { inner ->
        if (current == null) return@Scaffold
        Column(
            Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(top = contentPadding.calculateTopPadding())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Top bar
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Close player") }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "PLAYING FROM",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        state.contextTitle ?: "Your Library",
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = { media?.let { interactions.showActions(it, state.contextPlaylistId) } }, enabled = media != null) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "More options")
                }
            }

            // Artwork: as large as the space allows, square.
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val side = minOf(maxWidth, maxHeight) * 0.92f
                Crossfade(current.mediaId, label = "artwork") {
                    MediaArtwork(
                        model = current.artwork,
                        title = current.title,
                        isVideo = current.isVideo,
                        modifier = Modifier
                            .size(side)
                            .shadow(24.dp, RoundedCornerShape(28.dp)),
                        cornerRadius = 28.dp,
                    )
                }
            }

            // Title + favorite
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(current.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        current.subtitle ?: "Unknown artist",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                media?.let { m ->
                    IconButton(onClick = { viewModel.setFavorite(listOf(m.id), !m.favorite) }) {
                        Icon(
                            if (m.favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                            contentDescription = if (m.favorite) "Remove from Favorites" else "Add to Favorites",
                            tint = if (m.favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            SeekBar(state, connection, Modifier.padding(top = 8.dp))

            TransportRow(state, connection, Modifier.padding(vertical = 8.dp))

            VolumeSlider(Modifier.padding(horizontal = 8.dp))

            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { queueOpen = true }) {
                    Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    val upNext = state.queue.getOrNull(state.queue.indexOfFirst { it.index == state.currentIndex } + 1)
                    Text(upNext?.let { "Up next: ${it.title}" } ?: "Queue", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.weight(1f))
                if (current.isVideo) {
                    OutlinedButton(onClick = onOpenVideo) {
                        Icon(Icons.Filled.SmartDisplay, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Watch")
                    }
                }
            }
        }
    }

    if (queueOpen) {
        QueueSheet(
            state,
            connection,
            onDismiss = { queueOpen = false },
            onSaveAsPlaylist = { name -> viewModel.saveQueueAsPlaylist(state.queue.mapNotNull { it.mediaId }, name) },
        )
    }
}

/** Position slider with elapsed / total time. Dragging only seeks on release. */
@Composable
fun SeekBar(state: PlayerUiState, connection: PlayerConnection, modifier: Modifier = Modifier, textColor: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    val position by rememberPlaybackPosition(connection, state)
    var dragging by remember { mutableStateOf<Float?>(null) }
    val duration = state.durationMs
    val fraction = dragging ?: if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    Column(modifier.fillMaxWidth()) {
        Slider(
            value = fraction,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { connection.seekTo((it * duration).toLong()) }
                dragging = null
            },
            enabled = duration > 0,
            modifier = Modifier.semantics { contentDescription = "Seek" },
        )
        Row(Modifier.fillMaxWidth()) {
            Text(timeLabel(if (dragging != null) (fraction * duration).toLong() else position), style = MaterialTheme.typography.labelMedium, color = textColor)
            Spacer(Modifier.weight(1f))
            Text(if (duration > 0) timeLabel(duration) else "--:--", style = MaterialTheme.typography.labelMedium, color = textColor)
        }
    }
}

@Composable
private fun TransportRow(state: PlayerUiState, connection: PlayerConnection, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = { connection.setShuffle(!state.shuffle) },
            modifier = Modifier.semantics { stateDescription = if (state.shuffle) "On" else "Off" },
        ) {
            Icon(Icons.Filled.Shuffle, contentDescription = "Shuffle", tint = if (state.shuffle) scheme.primary else scheme.onSurfaceVariant)
        }
        IconButton(onClick = connection::previous, modifier = Modifier.size(56.dp)) {
            Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous", modifier = Modifier.size(36.dp))
        }
        val playing = state.playWhenReady && !state.ended
        Box(
            Modifier
                .size(76.dp)
                .shadow(16.dp, CircleShape)
                .background(scheme.primary, CircleShape)
                .clickable(onClickLabel = if (playing) "Pause" else "Play", onClick = connection::togglePlayPause),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (playing) "Pause" else "Play",
                tint = scheme.onPrimary,
                modifier = Modifier.size(40.dp),
            )
        }
        IconButton(onClick = connection::next, enabled = state.hasNext, modifier = Modifier.size(56.dp)) {
            Icon(Icons.Filled.SkipNext, contentDescription = "Next", modifier = Modifier.size(36.dp))
        }
        IconButton(
            onClick = connection::cycleRepeat,
            modifier = Modifier.semantics {
                stateDescription = when (state.repeatMode) {
                    Player.REPEAT_MODE_ALL -> "Repeat all"
                    Player.REPEAT_MODE_ONE -> "Repeat one"
                    else -> "Off"
                }
            },
        ) {
            Icon(
                if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                contentDescription = "Repeat",
                tint = if (state.repeatMode == Player.REPEAT_MODE_OFF) scheme.onSurfaceVariant else scheme.primary,
            )
        }
    }
}

/** The play queue: tap to jump, move up/down, remove, clear, save as a playlist. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(
    state: PlayerUiState,
    connection: PlayerConnection,
    onDismiss: () -> Unit,
    onSaveAsPlaylist: ((String) -> Unit)? = null,
) {
    var naming by remember { mutableStateOf(false) }
    if (naming && onSaveAsPlaylist != null) {
        com.enoluca.ytd.ui.library.NameDialog(
            title = "Save queue as playlist",
            initial = state.contextTitle?.let { "$it (queue)" } ?: "My queue",
            confirmLabel = "Save",
            onDismiss = { naming = false },
            onConfirm = { name ->
                naming = false
                onSaveAsPlaylist(name)
            },
        )
    }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val listState = rememberLazyListState()
    val currentPos = state.queue.indexOfFirst { it.index == state.currentIndex }
    LaunchedEffect(Unit) { if (currentPos > 0) listState.scrollToItem(currentPos) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = sheetContainerColor()) {
        Column(Modifier.navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Queue", style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (state.shuffle) "Shuffled · turn shuffle off to reorder" else "${state.queue.size} items",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (onSaveAsPlaylist != null) {
                    TextButton(onClick = { naming = true }, enabled = state.queue.any { it.mediaId != null }) { Text("Save") }
                }
                TextButton(onClick = { connection.clearQueue(); onDismiss() }, enabled = state.queue.isNotEmpty()) { Text("Clear") }
            }
            HorizontalDivider(Modifier.padding(top = 8.dp))
            LazyColumn(state = listState, modifier = Modifier.heightIn(max = 520.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                items(state.queue, key = { "${it.index}-${it.mediaId}" }) { entry ->
                    QueueRow(
                        entry = entry,
                        isCurrent = entry.index == state.currentIndex,
                        canMoveUp = !state.shuffle && entry.index > 0,
                        canMoveDown = !state.shuffle && entry.index < state.queue.lastIndex,
                        onPlay = { connection.playQueueItem(entry.index) },
                        onUp = { connection.moveInQueue(entry.index, entry.index - 1) },
                        onDown = { connection.moveInQueue(entry.index, entry.index + 1) },
                        onRemove = { connection.removeFromQueue(entry.index) },
                        reorderable = !state.shuffle,
                    )
                }
            }
        }
    }
}

@Composable
private fun QueueRow(
    entry: QueueEntry,
    isCurrent: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    reorderable: Boolean,
    onPlay: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onRemove: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (isCurrent) scheme.primary.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(onClick = onPlay)
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MediaArtwork(entry.artwork, entry.title, entry.isVideo, Modifier.size(44.dp), cornerRadius = 8.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isCurrent) scheme.primary else scheme.onSurface,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (isCurrent) "Now playing" else entry.subtitle ?: "",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        if (reorderable) {
            IconButton(onClick = onUp, enabled = canMoveUp) { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move ${entry.title} up") }
            IconButton(onClick = onDown, enabled = canMoveDown) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move ${entry.title} down") }
        }
        if (!isCurrent) {
            IconButton(onClick = onRemove) { Icon(Icons.Filled.Close, contentDescription = "Remove ${entry.title} from queue") }
        }
    }
}

