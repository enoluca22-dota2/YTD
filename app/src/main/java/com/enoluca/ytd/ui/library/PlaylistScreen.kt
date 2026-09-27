package com.enoluca.ytd.ui.library

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.enoluca.ytd.data.local.db.LibraryMediaEntity
import com.enoluca.ytd.data.local.db.LibraryPlaylistEntity
import com.enoluca.ytd.data.local.db.MediaType
import com.enoluca.ytd.data.local.db.PlaylistTrack
import com.enoluca.ytd.playback.PlayerUiState
import com.enoluca.ytd.ui.components.EmptyState
import com.enoluca.ytd.ui.glass.ActionState
import com.enoluca.ytd.ui.glass.GlassSnackbarHost
import com.enoluca.ytd.ui.glass.PrimaryActionButton

/** One Library playlist: play it in order or shuffled, reorder, remove, rename, delete. */
@Composable
fun PlaylistScreen(
    playlistId: Long,
    viewModel: LibraryViewModel,
    playerState: PlayerUiState,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onOpenVideoPlayer: () -> Unit,
) {
    val playlistFlow = remember(playlistId) { viewModel.observePlaylist(playlistId) }
    val tracksFlow = remember(playlistId) { viewModel.observePlaylistTracks(playlistId) }
    val playlist by playlistFlow.collectAsStateWithLifecycle(initialValue = null)
    val tracks by tracksFlow.collectAsStateWithLifecycle(initialValue = emptyList<PlaylistTrack>())
    var seenPlaylist by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val interactions = rememberMediaInteractions()
    var editing by rememberSaveable { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf<Boolean?>(null) } // true = with media

    // Deleted (here or elsewhere): leave.
    LaunchedEffect(playlist) {
        if (playlist != null) seenPlaylist = true else if (seenPlaylist) onBack()
    }

    val media = tracks.map { it.media }
    val current = playlist
    fun play(item: LibraryMediaEntity) {
        if (viewModel.play(media, item, current?.title, current?.id) == MediaType.VIDEO) onOpenVideoPlayer()
    }

    MediaInteractionsHost(interactions, viewModel, snackbar, onPlay = ::play)

    Scaffold(
        modifier = Modifier.padding(top = contentPadding.calculateTopPadding()),
        snackbarHost = { GlassSnackbarHost(snackbar, Modifier.padding(bottom = contentPadding.calculateBottomPadding())) },
        contentWindowInsets = WindowInsets(0),
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                Spacer(Modifier.weight(1f))
                if (editing) {
                    IconButton(onClick = { editing = false }) { Icon(Icons.Filled.Check, contentDescription = "Done editing") }
                } else if (current != null) {
                    PlaylistMenu(
                        playlist = current,
                        canEdit = tracks.size > 0,
                        onEdit = { editing = true },
                        onRename = { renaming = true },
                        onFavorite = { viewModel.setPlaylistFavorite(current.id, !current.favorite) },
                        onDelete = { confirmDelete = false },
                        onDeleteWithMedia = { confirmDelete = true },
                    )
                }
            }
            if (current == null) return@Column

            LazyColumn(
                contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = contentPadding.calculateBottomPadding() + 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                item(key = "header") {
                    PlaylistHeader(
                        playlist = current,
                        tracks = tracks,
                        onPlay = { if (viewModel.playAll(media, current.title, shuffle = false, contextPlaylistId = current.id) == MediaType.VIDEO) onOpenVideoPlayer() },
                        onShuffle = { if (viewModel.playAll(media, current.title, shuffle = true, contextPlaylistId = current.id) == MediaType.VIDEO) onOpenVideoPlayer() },
                    )
                }
                if (tracks.isEmpty()) {
                    item(key = "empty") {
                        EmptyState(
                            title = "This playlist is empty",
                            message = if (current.sourceUrl != null) {
                                "Its items appear here as their downloads complete."
                            } else {
                                "Add songs or videos from the Library with \"Add to playlist\"."
                            },
                            modifier = Modifier.height(260.dp),
                        )
                    }
                }
                itemsIndexed(tracks, key = { _, t -> t.media.id }) { index, track ->
                    val item = track.media
                    MediaRow(
                        item = item,
                        onClick = { if (!editing) { if (item.isAvailable) play(item) else interactions.showActions(item, current.id) } },
                        onMore = if (editing) null else ({ interactions.showActions(item, current.id) }),
                        playing = playerState.current?.mediaId == item.id,
                        leading = (index + 1).toString().padStart(2, '0'),
                        modifier = Modifier.animateItem(),
                        trailing = if (editing) {
                            {
                                Row {
                                    IconButton(onClick = { viewModel.movePlaylistItem(current.id, index, index - 1) }, enabled = index > 0) {
                                        Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move ${item.title} up")
                                    }
                                    IconButton(onClick = { viewModel.movePlaylistItem(current.id, index, index + 1) }, enabled = index < tracks.lastIndex) {
                                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move ${item.title} down")
                                    }
                                    IconButton(onClick = { viewModel.removeFromPlaylist(current.id, item.id) }) {
                                        Icon(Icons.Filled.RemoveCircleOutline, contentDescription = "Remove ${item.title} from playlist", tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }

    if (renaming && current != null) {
        NameDialog("Rename playlist", current.title, "Rename", onDismiss = { renaming = false }) { name ->
            renaming = false
            viewModel.renamePlaylist(current.id, name)
        }
    }
    confirmDelete?.let { withMedia ->
        if (current == null) return@let
        ConfirmDialog(
            title = if (withMedia) "Delete playlist and media?" else "Delete playlist?",
            message = if (withMedia) {
                "\"${current.title}\" and the ${tracks.size} file(s) in it are deleted from your device. This can't be undone."
            } else {
                "Only the playlist is deleted. Its songs and videos stay in your Library."
            },
            confirmLabel = if (withMedia) "Delete all" else "Delete playlist",
            onDismiss = { confirmDelete = null },
            onConfirm = { viewModel.deletePlaylist(current.id, withMedia) },
        )
    }
}

@Composable
private fun PlaylistHeader(playlist: LibraryPlaylistEntity, tracks: List<PlaylistTrack>, onPlay: () -> Unit, onShuffle: () -> Unit) {
    val available = tracks.filter { it.media.isAvailable }
    val cover = playlist.thumbnailUrl ?: tracks.firstOrNull { it.media.artworkModel != null }?.media?.artworkModel
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        MediaArtwork(cover, playlist.title, isVideo = false, modifier = Modifier.size(196.dp), cornerRadius = 28.dp)
        Spacer(Modifier.height(16.dp))
        Text(playlist.title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            listOfNotNull(
                if (available.size == 1) "1 item" else "${available.size} items",
                available.sumOf { it.media.durationMs ?: 0 }.takeIf { it > 0 }?.let(::totalDurationLabel),
                playlist.sourcePlatform?.let { "from $it" },
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (tracks.size > available.size) {
            Text("${tracks.size - available.size} file(s) missing", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            PrimaryActionButton(
                text = "Play",
                icon = Icons.Filled.PlayArrow,
                onClick = onPlay,
                enabled = available.isNotEmpty(),
                state = ActionState.Idle,
                modifier = Modifier.width(150.dp),
            )
            OutlinedButton(onClick = onShuffle, enabled = available.isNotEmpty(), modifier = Modifier.height(52.dp)) {
                Icon(Icons.Filled.Shuffle, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Shuffle")
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun PlaylistMenu(
    playlist: LibraryPlaylistEntity,
    canEdit: Boolean,
    onEdit: () -> Unit,
    onRename: () -> Unit,
    onFavorite: () -> Unit,
    onDelete: () -> Unit,
    onDeleteWithMedia: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Playlist options") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (canEdit) DropdownMenuItem(text = { Text("Edit order") }, onClick = { open = false; onEdit() })
            DropdownMenuItem(text = { Text("Rename") }, onClick = { open = false; onRename() })
            DropdownMenuItem(
                text = { Text(if (playlist.favorite) "Remove from Favorites" else "Add to Favorites") },
                onClick = { open = false; onFavorite() },
            )
            DropdownMenuItem(text = { Text("Delete playlist") }, onClick = { open = false; onDelete() })
            DropdownMenuItem(
                text = { Text("Delete playlist and media", color = MaterialTheme.colorScheme.error) },
                onClick = { open = false; onDeleteWithMedia() },
            )
        }
    }
}
