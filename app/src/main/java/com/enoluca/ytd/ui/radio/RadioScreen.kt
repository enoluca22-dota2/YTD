package com.enoluca.ytd.ui.radio

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.enoluca.ytd.playback.PlayerUiState
import com.enoluca.ytd.radio.RadioStation
import com.enoluca.ytd.ui.components.EmptyState
import com.enoluca.ytd.ui.components.ScreenHeader
import com.enoluca.ytd.ui.glass.GlassSnackbarHost
import kotlinx.coroutines.launch

/**
 * Radio home: search, My Radio, Recently played, one shelf per country (curated stations, which
 * work even when the directory is unreachable) and popular stations worldwide.
 */
@Composable
fun RadioScreen(
    viewModel: RadioViewModel,
    playerState: PlayerUiState,
    contentPadding: PaddingValues,
    onOpenCountry: (String) -> Unit,
    onOpenPlayer: () -> Unit,
) {
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()
    val recent by viewModel.recent.collectAsStateWithLifecycle()
    val favoriteIds by viewModel.favoriteIds.collectAsStateWithLifecycle()
    val remote by viewModel.remote.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val search by viewModel.search.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val bottom = contentPadding.calculateBottomPadding()
    val playingId = playerState.current?.radioStationId

    LaunchedEffect(Unit) { viewModel.loadInternational() }
    BackHandler(enabled = query.isNotEmpty()) { viewModel.query.value = "" }

    fun play(station: RadioStation) {
        focus.clearFocus()
        if (station.id == playingId) onOpenPlayer() else viewModel.play(station)
    }

    fun toggleFavorite(station: RadioStation) {
        val adding = station.id !in favoriteIds
        viewModel.toggleFavorite(station)
        scope.launch { snackbar.showSnackbar(if (adding) "Added ${station.name} to My Radio" else "Removed ${station.name} from My Radio") }
    }

    Scaffold(
        modifier = Modifier.padding(top = contentPadding.calculateTopPadding()),
        snackbarHost = { GlassSnackbarHost(snackbar, Modifier.padding(bottom = bottom)) },
        contentWindowInsets = WindowInsets(0),
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner)) {
            ScreenHeader(title = "Radio", subtitle = "Live stations from ${viewModel.countries.joinToString { it.name }} and more")
            OutlinedTextField(
                value = query,
                onValueChange = { viewModel.query.value = it },
                placeholder = { Text("Stations, countries, genres", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { viewModel.query.value = "" }) { Icon(Icons.Filled.Close, contentDescription = "Clear search") }
                },
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )

            if (search.active) {
                SearchResults(search, favoriteIds, playingId, bottom, ::play, ::toggleFavorite, onRetry = viewModel::retrySearch)
            } else {
                LazyColumn(contentPadding = PaddingValues(top = 8.dp, bottom = bottom + 8.dp)) {
                    if (favorites.isNotEmpty()) {
                        item(key = "favorites") { StationShelf("My Radio", favorites, playingId, ::play, ::toggleFavorite) }
                    }
                    if (recent.isNotEmpty()) {
                        item(key = "recent") {
                            StationShelf("Recently played", recent, playingId, ::play, ::toggleFavorite, action = "Clear", onAction = viewModel::clearRecent)
                        }
                    }
                    items(viewModel.countries, key = { "country-${it.code}" }) { country ->
                        val featured = remember(country.code) { viewModel.featured(country.code) }
                        StationShelf(
                            title = "${country.flag}  ${country.name}",
                            stations = featured.sortedBy { it.category.ordinal },
                            playingId = playingId,
                            onPlay = ::play,
                            onLongPress = ::toggleFavorite,
                            action = "See all",
                            onAction = { onOpenCountry(country.code) },
                        )
                    }
                    item(key = "international") {
                        val top = remote["top"]
                        when {
                            top == null || (top.loading && top.stations.isEmpty()) -> Loading("🌍  International")
                            top.stations.isNotEmpty() -> StationShelf("🌍  International", top.stations, playingId, ::play, ::toggleFavorite)
                            top.error != null -> Column {
                                Text("🌍  International", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 20.dp, top = 4.dp))
                                DirectoryNotice(top.error, onRetry = { viewModel.loadInternational(force = true) })
                            }
                        }
                    }
                    item(key = "hint") {
                        Text(
                            "Long-press a station to add it to My Radio. Radio plays live over the internet; it isn't saved to your Library.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResults(
    search: RadioSearchState,
    favoriteIds: Set<String>,
    playingId: String?,
    bottom: androidx.compose.ui.unit.Dp,
    onPlay: (RadioStation) -> Unit,
    onToggleFavorite: (RadioStation) -> Unit,
    onRetry: () -> Unit,
) {
    val localIds = search.local.map { it.id }.toSet()
    val remote = search.remote.stations.filter { it.id !in localIds }
    if (search.local.isEmpty() && remote.isEmpty() && !search.remote.loading && search.remote.error == null) {
        EmptyState("No stations found", "Nothing matches \"${search.query.trim()}\". Try a station name, a country (Albania, Italy), a city (London) or a genre (rock, news).", Modifier.padding(bottom = bottom), icon = Icons.Filled.SearchOff)
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = bottom + 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(search.local, key = { "l-${it.id}" }) { station ->
            StationRow(station, station.id in favoriteIds, station.id == playingId, onPlay = { onPlay(station) }, onToggleFavorite = { onToggleFavorite(station) })
        }
        item(key = "directory-header") {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("From the station directory", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (search.remote.loading) CircularProgressIndicator(Modifier.size(16.dp).padding(end = 4.dp), strokeWidth = 2.dp)
            }
        }
        search.remote.error?.let { error -> item(key = "directory-error") { DirectoryNotice(error, onRetry) } }
        items(remote, key = { "r-${it.id}" }) { station ->
            StationRow(station, station.id in favoriteIds, station.id == playingId, onPlay = { onPlay(station) }, onToggleFavorite = { onToggleFavorite(station) })
        }
    }
}

@Composable
private fun Loading(title: String) {
    Column(Modifier.padding(bottom = 10.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 20.dp, top = 4.dp, bottom = 8.dp))
        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
        }
    }
}
