package com.enoluca.ytd.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.enoluca.ytd.core.Formatting
import com.enoluca.ytd.playback.PlayerConnection
import com.enoluca.ytd.playback.PlayerUiState
import com.enoluca.ytd.ui.glass.GlassStyle
import com.enoluca.ytd.ui.glass.GlassSurface
import com.enoluca.ytd.ui.library.MediaArtwork
import kotlinx.coroutines.delay

/** Height reserved above the tab bar while the mini player is visible. */
val MiniPlayerHeight = 64.dp

/**
 * The playing item's position, refreshed while this composable is on screen (and faster while
 * playing). Positions aren't part of [PlayerUiState] so the rest of the UI doesn't recompose
 * several times a second.
 */
@Composable
fun rememberPlaybackPosition(connection: PlayerConnection, state: PlayerUiState, intervalMs: Long = 250): State<Long> {
    val position = remember { mutableLongStateOf(connection.positionMs()) }
    LaunchedEffect(connection, state.isPlaying, state.current?.mediaId, state.currentIndex) {
        while (true) {
            position.longValue = connection.positionMs()
            delay(if (state.isPlaying) intervalMs else 1_000)
        }
    }
    return position
}

fun timeLabel(ms: Long): String = Formatting.duration((ms.coerceAtLeast(0)) / 1000)

/** Compact player above the tab bar: artwork, title, play/pause, next. Tap opens the full player. */
@Composable
fun MiniPlayer(
    state: PlayerUiState,
    connection: PlayerConnection,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val current = state.current ?: return
    val position by rememberPlaybackPosition(connection, state, intervalMs = 500)
    val scheme = MaterialTheme.colorScheme
    GlassSurface(
        modifier.fillMaxWidth().height(MiniPlayerHeight),
        cornerRadius = 20.dp,
        style = GlassStyle.Regular,
        onClick = onOpen,
        onClickLabel = "Open player",
    ) {
        Box {
            Row(Modifier.fillMaxHeight().padding(start = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                MediaArtwork(current.artwork, current.title, current.isVideo, Modifier.size(44.dp), cornerRadius = 10.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(current.title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (current.isRadio) {
                            "LIVE · " + (state.liveTitle ?: current.subtitle ?: "Radio")
                        } else {
                            current.subtitle ?: state.contextTitle ?: if (current.isVideo) "Video" else "Music"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = connection::togglePlayPause) {
                    val playing = state.playWhenReady && !state.ended
                    Icon(
                        if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (playing) "Pause" else "Play",
                        tint = scheme.primary,
                    )
                }
                if (current.isRadio) {
                    // Live radio has nothing to skip to.
                    IconButton(onClick = connection::stop) { Icon(Icons.Filled.Stop, contentDescription = "Stop radio") }
                } else {
                    IconButton(onClick = connection::next, enabled = state.hasNext) {
                        Icon(Icons.Filled.SkipNext, contentDescription = "Next")
                    }
                }
            }
            if (current.isRadio) return@Box
            // Thin progress line along the bottom edge.
            val fraction = if (state.durationMs > 0) (position.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(horizontal = 14.dp)
                    .fillMaxWidth(fraction)
                    .height(2.dp)
                    .background(scheme.primary),
            )
        }
    }
}
