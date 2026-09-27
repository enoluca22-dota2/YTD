package com.enoluca.ytd.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.enoluca.ytd.core.Formatting
import com.enoluca.ytd.data.local.db.LibraryMediaEntity
import com.enoluca.ytd.data.local.db.MediaType
import com.enoluca.ytd.data.local.db.PlaylistSummary
import com.enoluca.ytd.library.LibraryFilter
import com.enoluca.ytd.playback.PlayerUiState
import com.enoluca.ytd.ui.components.EmptyState
import com.enoluca.ytd.ui.components.ScreenHeader
import com.enoluca.ytd.ui.glass.GlassChip
import com.enoluca.ytd.ui.glass.GlassSnackbarHost
import com.enoluca.ytd.ui.glass.GlassStyle
import com.enoluca.ytd.ui.glass.GlassSurface
import java.text.NumberFormat

@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    playerState: PlayerUiState,
    contentPadding: PaddingValues,
    onOpenPlaylist: (Long) -> Unit,
    onOpenVideoPlayer: () -> Unit,
    onGoHome: () -> Unit,
) {
    val media by viewModel.media.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val loaded by viewModel.loaded.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val scan by viewModel.scanState.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }
    val interactions = rememberMediaInteractions()
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var selection by remember { mutableStateOf<List<Long>>(emptyList()) }
    var newPlaylist by rememberSaveable { mutableStateOf(false) }
    val bottom = contentPadding.calculateBottomPadding()

    fun play(item: LibraryMediaEntity) {
        if (viewModel.play(media, item) == MediaType.VIDEO) onOpenVideoPlayer()
    }

    fun toggle(item: LibraryMediaEntity) {
        selection = if (item.id in selection) selection - item.id else selection + item.id
    }

    BackHandler(enabled = selection.isNotEmpty()) { selection = emptyList() }
    BackHandler(enabled = selection.isEmpty() && searchOpen) {
        searchOpen = false
        viewModel.query.value = ""
    }

    MediaInteractionsHost(interactions, viewModel, snackbar, onPlay = ::play)

    Scaffold(
        modifier = Modifier.padding(top = contentPadding.calculateTopPadding()),
        snackbarHost = { GlassSnackbarHost(snackbar, Modifier.padding(bottom = bottom)) },
        contentWindowInsets = WindowInsets(0),
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner)) {
            if (selection.isNotEmpty()) {
                val selected = media.filter { it.id in selection }
                SelectionBar(
                    count = selection.size,
                    onClose = { selection = emptyList() },
                    onPlay = {
                        if (viewModel.playAll(selected, null, shuffle = false) == MediaType.VIDEO) onOpenVideoPlayer()
                        selection = emptyList()
                    },
                    onAddToPlaylist = { interactions.addToPlaylist = selection },
                    onQueue = {
                        viewModel.addToQueue(selected)
                        selection = emptyList()
                    },
                    onFavorite = {
                        viewModel.setFavorite(selection, !selected.all { it.favorite })
                        selection = emptyList()
                    },
                    allFavorite = selected.isNotEmpty() && selected.all { it.favorite },
                    onDelete = { interactions.confirmDelete = selection },
                )
            } else {
                var menuOpen by remember { mutableStateOf(false) }
                ScreenHeader(
                    title = "Library",
                    subtitle = if (stats.count > 0) "${NumberFormat.getIntegerInstance().format(stats.count)} items · ${Formatting.bytes(stats.totalBytes)}" else null,
                ) {
                    IconButton(onClick = {
                        searchOpen = !searchOpen
                        if (!searchOpen) viewModel.query.value = ""
                    }) { Icon(if (searchOpen) Icons.Filled.Close else Icons.Filled.Search, contentDescription = if (searchOpen) "Close search" else "Search library") }
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Library options") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text("New playlist") }, onClick = { menuOpen = false; newPlaylist = true })
                            DropdownMenuItem(text = { Text("Rescan library") }, onClick = { menuOpen = false; viewModel.rescan() })
                            if (scan.lastResult?.missing?.let { it > 0 } == true || media.any { !it.isAvailable }) {
                                DropdownMenuItem(text = { Text("Remove missing items") }, onClick = { menuOpen = false; viewModel.forgetMissing() })
                            }
                        }
                    }
                }
            }
            if (scan.running) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(2.dp))

            AnimatedVisibility(searchOpen) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { viewModel.query.value = it },
                    placeholder = { Text("Title, artist, album, playlist or file") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(18.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(LibraryFilter.entries) { f ->
                    GlassChip(label = f.label, selected = f == filter, onClick = { viewModel.filter.value = f; selection = emptyList() })
                }
            }

            if (filter == LibraryFilter.PLAYLISTS) {
                PlaylistGrid(
                    playlists = playlists.filter { query.isBlank() || it.playlist.title.contains(query.trim(), ignoreCase = true) },
                    bottomPadding = bottom,
                    onOpen = onOpenPlaylist,
                    onCreate = { newPlaylist = true },
                )
            } else if (loaded && media.isEmpty()) {
                LibraryEmptyState(filter, query, Modifier.padding(bottom = bottom), onGoHome)
            } else {
                val listState = rememberLazyListState()
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 4.dp, bottom = bottom + 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (filter == LibraryFilter.ALL && query.isBlank() && playlists.isNotEmpty() && selection.isEmpty()) {
                        item(key = "playlists") {
                            PlaylistStrip(playlists, onOpen = onOpenPlaylist, onSeeAll = { viewModel.filter.value = LibraryFilter.PLAYLISTS })
                        }
                    }
                    items(media, key = { it.id }) { item ->
                        MediaRow(
                            item = item,
                            onClick = {
                                when {
                                    selection.isNotEmpty() -> toggle(item)
                                    item.isAvailable -> play(item)
                                    else -> interactions.showActions(item)
                                }
                            },
                            onLongClick = { toggle(item) },
                            onMore = { interactions.showActions(item) },
                            selectionMode = selection.isNotEmpty(),
                            selected = item.id in selection,
                            playing = playerState.current?.mediaId == item.id,
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }
    }

    if (newPlaylist) {
        NameDialog(
            title = "New playlist",
            initial = "",
            confirmLabel = "Create",
            onDismiss = { newPlaylist = false },
            onConfirm = { name ->
                newPlaylist = false
                viewModel.createPlaylist(name)
            },
        )
    }
}

@Composable
private fun SelectionBar(
    count: Int,
    onClose: () -> Unit,
    onPlay: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onQueue: () -> Unit,
    onFavorite: () -> Unit,
    allFavorite: Boolean,
    onDelete: () -> Unit,
) {
    GlassSurface(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), cornerRadius = 22.dp, style = GlassStyle.Accent) {
        Row(Modifier.padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Clear selection") }
            Text("$count selected", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = onPlay) { Icon(Icons.Filled.PlayArrow, contentDescription = "Play selected") }
            IconButton(onClick = onAddToPlaylist) { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = "Add selected to playlist") }
            IconButton(onClick = onQueue) { Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = "Add selected to queue") }
            IconButton(onClick = onFavorite) {
                Icon(if (allFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, contentDescription = if (allFavorite) "Remove selected from Favorites" else "Add selected to Favorites")
            }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Delete selected") }
        }
    }
}

/** "Playlists" row at the top of All. */
@Composable
private fun PlaylistStrip(playlists: List<PlaylistSummary>, onOpen: (Long) -> Unit, onSeeAll: () -> Unit) {
    Column(Modifier.padding(bottom = 8.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Playlists", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onSeeAll) { Text("See all") }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(playlists.take(12), key = { it.playlist.id }) { summary ->
                PlaylistCard(summary, onClick = { onOpen(summary.playlist.id) }, modifier = Modifier.width(150.dp))
            }
        }
        Text(
            "All media",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 12.dp, top = 14.dp, bottom = 2.dp),
        )
    }
}

@Composable
private fun PlaylistGrid(playlists: List<PlaylistSummary>, bottomPadding: Dp, onOpen: (Long) -> Unit, onCreate: () -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = bottomPadding + 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "new") { NewPlaylistCard(onClick = onCreate) }
        items(playlists, key = { it.playlist.id }) { summary ->
            PlaylistCard(summary, onClick = { onOpen(summary.playlist.id) })
        }
        if (playlists.isEmpty()) {
            item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.padding(vertical = 24.dp, horizontal = 8.dp)) {
                    Text("No playlists yet", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Your downloaded playlists will appear here. You can also create your own.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun LibraryEmptyState(filter: LibraryFilter, query: String, modifier: Modifier, onGoHome: () -> Unit) {
    if (query.isNotBlank()) {
        EmptyState("No results", "Nothing in your Library matches \"${query.trim()}\".", modifier, icon = Icons.Filled.SearchOff)
        return
    }
    val (title, message, icon) = when (filter) {
        LibraryFilter.MUSIC -> Triple("No music yet", "Download a song and it will appear here.", Icons.Filled.MusicNote)
        LibraryFilter.VIDEOS -> Triple("No videos yet", "Your downloaded videos will appear here.", Icons.Filled.Movie)
        LibraryFilter.FAVORITES -> Triple("No favorites yet", "Tap ♥ on a song or video to keep it here.", Icons.Filled.FavoriteBorder)
        LibraryFilter.RECENTLY_PLAYED -> Triple("Nothing played yet", "What you play in ENAGELYUCA shows up here.", Icons.Filled.History)
        LibraryFilter.RECENTLY_ADDED -> Triple("Nothing added yet", "New downloads show up here first.", Icons.Filled.History)
        else -> Triple("Your Library is empty", "Download a video or song and it will appear here, ready to play.", Icons.Filled.LibraryMusic)
    }
    EmptyState(
        title = title,
        message = message,
        modifier = modifier,
        icon = icon,
        action = if (filter == LibraryFilter.ALL || filter == LibraryFilter.MUSIC || filter == LibraryFilter.VIDEOS) {
            { OutlinedButton(onClick = onGoHome) { Icon(Icons.Filled.Download, null); Spacer(Modifier.width(8.dp)); Text("Download something") } }
        } else {
            null
        },
    )
}
