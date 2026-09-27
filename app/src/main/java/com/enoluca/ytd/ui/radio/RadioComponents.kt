package com.enoluca.ytd.ui.radio

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.enoluca.ytd.radio.RadioStation
import com.enoluca.ytd.ui.glass.GlassStyle
import com.enoluca.ytd.ui.glass.GlassSurface
import kotlin.math.abs

private val stationPalettes = listOf(
    Color(0xFF0F2027) to Color(0xFF2C5364),
    Color(0xFFB24592) to Color(0xFFF15F79),
    Color(0xFF1D976C) to Color(0xFF93F9B9),
    Color(0xFFEB5757) to Color(0xFF000000),
    Color(0xFF4568DC) to Color(0xFFB06AB3),
    Color(0xFFFF8008) to Color(0xFFFFC837),
)

/**
 * Station logo on a white tile (logos are made for light backgrounds), or ENAGELYUCA's generated
 * placeholder — initials on a gradient — while it loads or when the station has none.
 */
@Composable
fun StationArtwork(station: RadioStation, modifier: Modifier = Modifier, cornerRadius: Dp = 16.dp) {
    var loaded by remember(station.logoUrl) { mutableStateOf(false) }
    Box(modifier.clip(RoundedCornerShape(cornerRadius))) {
        val (a, b) = stationPalettes[abs(station.name.hashCode()) % stationPalettes.size]
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(a, b))), contentAlignment = Alignment.Center) {
            if (!loaded) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Radio, contentDescription = null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(22.dp))
                    Text(
                        initials(station.name),
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                }
            }
        }
        if (station.logoUrl != null) {
            Box(Modifier.fillMaxSize().then(if (loaded) Modifier.background(Color.White) else Modifier)) {
                AsyncImage(
                    model = station.logoUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    onSuccess = { loaded = true },
                    onError = { loaded = false },
                    modifier = Modifier.fillMaxSize().padding(10.dp).alpha(if (loaded) 1f else 0f),
                )
            }
        }
    }
}

private fun initials(name: String): String =
    name.split(Regex("[\\s.·-]+")).filter { it.isNotEmpty() && it.first().isLetterOrDigit() }.take(2)
        .joinToString("") { it.first().uppercase() }.ifEmpty { "FM" }

/** Red "LIVE" pill; the dot pulses while [animated]. */
@Composable
fun LiveBadge(modifier: Modifier = Modifier, animated: Boolean = true) {
    val pulse = rememberInfiniteTransition(label = "live")
    val alpha by pulse.animateFloat(1f, if (animated) 0.25f else 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "liveDot")
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(Color(0xFFE5383B))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).alpha(alpha).background(Color.White, CircleShape))
        Spacer(Modifier.width(5.dp))
        Text("LIVE", color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
    }
}

/** Square card for horizontal rows: logo, name, country/genre and a play button. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StationCard(
    station: RadioStation,
    playing: Boolean,
    onPlay: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier
            .width(136.dp)
            .clip(RoundedCornerShape(18.dp))
            .combinedClickable(role = Role.Button, onClickLabel = "Play ${station.name}", onLongClickLabel = "Station options", onLongClick = onLongPress, onClick = onPlay)
            .padding(4.dp),
    ) {
        Box {
            StationArtwork(station, Modifier.fillMaxWidth().aspectRatio(1f))
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .size(34.dp)
                    .background(scheme.primary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (playing) Icons.Filled.GraphicEq else Icons.Filled.PlayArrow, contentDescription = null, tint = scheme.onPrimary, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            station.name,
            style = MaterialTheme.typography.labelLarge,
            color = if (playing) scheme.primary else scheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "${station.country?.flag ?: ""} ${station.genre ?: station.countryName}".trim(),
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** List row: logo, name, "Tirana · News & Talk", ♥. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StationRow(
    station: RadioStation,
    favorite: Boolean,
    playing: Boolean,
    onPlay: () -> Unit,
    onToggleFavorite: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .combinedClickable(role = Role.Button, onClickLabel = "Play ${station.name}", onClick = onPlay)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StationArtwork(station, Modifier.size(54.dp), cornerRadius = 12.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                station.name,
                style = MaterialTheme.typography.titleSmall,
                color = if (playing) scheme.primary else scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(station.country?.flag, station.subtitle).joinToString(" "),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (playing) Icon(Icons.Filled.GraphicEq, contentDescription = "Playing", tint = scheme.primary, modifier = Modifier.padding(horizontal = 4.dp))
        IconButton(onClick = onToggleFavorite) {
            Icon(
                if (favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                contentDescription = if (favorite) "Remove ${station.name} from My Radio" else "Add ${station.name} to My Radio",
                tint = if (favorite) scheme.primary else scheme.onSurfaceVariant,
            )
        }
    }
}

/** A titled horizontal row of station cards. */
@Composable
fun StationShelf(
    title: String,
    stations: List<RadioStation>,
    playingId: String?,
    onPlay: (RadioStation) -> Unit,
    onLongPress: (RadioStation) -> Unit,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
) {
    Column(modifier.padding(bottom = 10.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (action != null && onAction != null) TextButton(onClick = onAction) { Text(action) }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(stations, key = { it.id }) { station ->
                StationCard(station, playing = station.id == playingId, onPlay = { onPlay(station) }, onLongPress = { onLongPress(station) })
            }
        }
        footer?.invoke()
    }
}

/** Glass notice for directory errors, with Retry. */
@Composable
fun DirectoryNotice(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    GlassSurface(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), cornerRadius = 16.dp, style = GlassStyle.Regular) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onRetry) { Text("Retry") }
        }
    }
}
