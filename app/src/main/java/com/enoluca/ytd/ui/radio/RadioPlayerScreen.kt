package com.enoluca.ytd.ui.radio

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.enoluca.ytd.playback.PlayerConnection
import com.enoluca.ytd.playback.PlayerUiState
import com.enoluca.ytd.radio.RadioStation
import com.enoluca.ytd.ui.glass.GlassStyle
import com.enoluca.ytd.ui.glass.GlassSurface
import com.enoluca.ytd.ui.player.VolumeSlider

/** Now Playing for live radio. Transport is play/pause and stop only: live radio can't skip or seek. */
@Composable
fun RadioPlayerScreen(
    state: PlayerUiState,
    connection: PlayerConnection,
    viewModel: RadioViewModel,
    contentPadding: PaddingValues,
    onClose: () -> Unit,
    onOpenMusicPlayer: () -> Unit,
) {
    val current = state.current
    LaunchedEffect(state.connected, current) {
        if (state.connected && current == null) onClose()
    }
    // Something else took over the player (a song from the Library): show its player instead.
    LaunchedEffect(current?.isRadio) {
        if (current != null && !current.isRadio) onOpenMusicPlayer()
    }
    val stationId = current?.radioStationId ?: return
    var station by remember(stationId) { mutableStateOf<RadioStation?>(null) }
    LaunchedEffect(stationId) { station = viewModel.station(stationId) }
    val favoriteIds by viewModel.favoriteIds.collectAsStateWithLifecycle()
    val favorite = stationId in favoriteIds
    val scheme = MaterialTheme.colorScheme

    Column(
        Modifier
            .fillMaxSize()
            .padding(top = contentPadding.calculateTopPadding())
            .navigationBarsPadding()
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Close player") }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("LIVE RADIO", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                Text(
                    station?.let { "${it.country?.flag ?: ""} ${it.countryName}".trim() } ?: state.contextTitle.orEmpty(),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = { station?.let(viewModel::toggleFavorite) }, enabled = station != null) {
                Icon(
                    if (favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = if (favorite) "Remove from My Radio" else "Add to My Radio",
                    tint = if (favorite) scheme.primary else scheme.onSurfaceVariant,
                )
            }
        }

        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val side = minOf(maxWidth, maxHeight) * 0.8f
            Box(contentAlignment = Alignment.TopStart) {
                val shown = station
                Crossfade(shown?.id, label = "stationArt") {
                    if (shown != null) {
                        StationArtwork(shown, Modifier.size(side).shadow(24.dp, RoundedCornerShape(28.dp)), cornerRadius = 28.dp)
                    } else {
                        Box(Modifier.size(side))
                    }
                }
                LiveBadge(Modifier.padding(14.dp), animated = state.isPlaying)
            }
        }

        Text(
            current.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            listOfNotNull(station?.city, state.radioGenre ?: station?.genre, station?.qualityLabel).joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        // What's on air, when the stream says; plain "LIVE RADIO" otherwise.
        GlassSurface(Modifier.fillMaxWidth().padding(top = 16.dp), cornerRadius = 18.dp, style = GlassStyle.Regular) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.MusicNote, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("ON AIR", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                    Text(
                        state.liveTitle ?: "LIVE RADIO",
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    state.liveArtist?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }

        state.error?.let {
            Text(it, color = scheme.error, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.dp))
        }

        Row(
            Modifier.fillMaxWidth().padding(vertical = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = connection::stop, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Filled.Stop, contentDescription = "Stop", modifier = Modifier.size(32.dp))
            }
            val playing = state.playWhenReady && state.error == null
            Box(
                Modifier
                    .size(76.dp)
                    .shadow(16.dp, CircleShape)
                    .background(scheme.primary, CircleShape)
                    .clickable(onClickLabel = if (playing) "Pause" else "Play", onClick = connection::togglePlayPause),
                contentAlignment = Alignment.Center,
            ) {
                if (state.buffering && playing) {
                    CircularProgressIndicator(Modifier.size(36.dp), color = scheme.onPrimary, strokeWidth = 3.dp)
                } else {
                    Icon(
                        if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (playing) "Pause" else "Play",
                        tint = scheme.onPrimary,
                        modifier = Modifier.size(40.dp),
                    )
                }
            }
            // Keeps the row centered on the play button.
            Spacer(Modifier.size(56.dp))
        }

        VolumeSlider(Modifier.padding(horizontal = 8.dp))
        Spacer(Modifier.height(12.dp))
    }
}
