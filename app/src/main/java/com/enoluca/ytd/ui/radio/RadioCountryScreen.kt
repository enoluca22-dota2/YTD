package com.enoluca.ytd.ui.radio

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.enoluca.ytd.playback.PlayerUiState
import com.enoluca.ytd.radio.RadioCategory
import com.enoluca.ytd.radio.RadioCountries
import com.enoluca.ytd.radio.RadioStation
import com.enoluca.ytd.ui.glass.GlassChip
import com.enoluca.ytd.ui.glass.GlassSnackbarHost
import kotlinx.coroutines.launch

/** All stations of one country: curated picks first, then the directory, filtered by category. */
@Composable
fun RadioCountryScreen(
    countryCode: String,
    viewModel: RadioViewModel,
    playerState: PlayerUiState,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onOpenPlayer: () -> Unit,
) {
    val country = RadioCountries.byCode(countryCode)
    val name = country?.name ?: RadioCountries.displayName(countryCode)
    val favoriteIds by viewModel.favoriteIds.collectAsStateWithLifecycle()
    val remote by viewModel.remote.collectAsStateWithLifecycle()
    var category by rememberSaveable { mutableStateOf<RadioCategory?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val playingId = playerState.current?.radioStationId
    val bottom = contentPadding.calculateBottomPadding()

    LaunchedEffect(countryCode) { viewModel.loadCountry(countryCode) }

    val featured = remember(countryCode) { viewModel.featured(countryCode) }
    val directory = remote["country:$countryCode"]
    val featuredIds = featured.map { it.id }.toSet()
    // Curated stations carry an editor's category; directory ones are classified from their tags.
    // "Popular" from the directory = its ranking (most voted first).
    fun List<RadioStation>.inCategory(c: RadioCategory?, ranked: Boolean) = when {
        c == null -> this
        c == RadioCategory.POPULAR && ranked -> take(15)
        else -> filter { it.category == c }
    }
    val shownFeatured = featured.inCategory(category, ranked = false)
    val shownDirectory = directory?.stations.orEmpty().filter { it.id !in featuredIds }.inCategory(category, ranked = true)

    fun play(station: RadioStation) {
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
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                Text("${RadioCountries.flagOf(countryCode)}  $name", style = MaterialTheme.typography.headlineSmall)
            }
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { GlassChip("All", category == null, onClick = { category = null }) }
                items(RadioCategory.entries) { c -> GlassChip(c.label, category == c, onClick = { category = c }) }
            }
            LazyColumn(
                contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 4.dp, bottom = bottom + 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (shownFeatured.isNotEmpty()) {
                    item(key = "featured") { SectionTitle("Featured") }
                    items(shownFeatured, key = { "f-${it.id}" }) { station ->
                        StationRow(station, station.id in favoriteIds, station.id == playingId, onPlay = { play(station) }, onToggleFavorite = { toggleFavorite(station) })
                    }
                }
                item(key = "more") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SectionTitle(if (shownFeatured.isEmpty()) "Stations" else "More stations", Modifier.weight(1f))
                        if (directory?.loading == true) CircularProgressIndicator(Modifier.size(16.dp).padding(end = 4.dp), strokeWidth = 2.dp)
                    }
                }
                directory?.error?.let { error -> item(key = "error") { DirectoryNotice(error, onRetry = { viewModel.loadCountry(countryCode, force = true) }) } }
                items(shownDirectory, key = { "d-${it.id}" }) { station ->
                    StationRow(station, station.id in favoriteIds, station.id == playingId, onPlay = { play(station) }, onToggleFavorite = { toggleFavorite(station) })
                }
                if (directory != null && !directory.loading && directory.error == null && shownDirectory.isEmpty()) {
                    item(key = "none") {
                        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                            Text("No more stations in this category.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier.padding(start = 12.dp, top = 12.dp, bottom = 4.dp))
}
