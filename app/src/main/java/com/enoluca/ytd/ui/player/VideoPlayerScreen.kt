package com.enoluca.ytd.ui.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.enoluca.ytd.playback.PlayerConnection
import com.enoluca.ytd.playback.PlayerUiState
import com.enoluca.ytd.playback.TrackOption
import com.enoluca.ytd.ui.glass.GlassStyle
import com.enoluca.ytd.ui.glass.GlassSurface
import com.enoluca.ytd.ui.glass.sheetContainerColor
import com.enoluca.ytd.ui.library.MediaArtwork
import kotlinx.coroutines.delay

private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

/**
 * ENAGELYUCA's video player: full screen, our own glass controls over Media3's video surface.
 * Tap shows/hides the controls, double-tap left/right skips 10 s. The same player (and queue)
 * as music, so next/previous follow the playlist, and the position is remembered.
 */
@Composable
fun VideoPlayerScreen(state: PlayerUiState, connection: PlayerConnection, onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val view = LocalView.current
    val controller by connection.player.collectAsStateWithLifecycle()
    val resumePrompt by connection.resumePrompt.collectAsStateWithLifecycle()
    var controlsVisible by remember { mutableStateOf(true) }
    var interaction by remember { mutableIntStateOf(0) }
    var zoom by rememberSaveable { mutableStateOf(false) }
    var speedDialog by remember { mutableStateOf(false) }
    var tracksDialog by remember { mutableStateOf(false) }
    // null = follow the video's shape; otherwise the user's choice.
    var landscapeLock by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var videoLandscape by remember { mutableStateOf<Boolean?>(null) }
    var seekHint by remember { mutableStateOf<String?>(null) }
    var seekHintKey by remember { mutableLongStateOf(0L) }

    // Nothing to show anymore (queue cleared): leave.
    LaunchedEffect(state.connected, state.current) {
        if (state.connected && state.current == null) onBack()
    }

    // Immersive full screen while this screen is shown.
    DisposableEffect(activity) {
        val window = activity?.window
        val insets = window?.let { WindowCompat.getInsetsController(it, view) }
        insets?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insets?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            insets?.show(WindowInsetsCompat.Type.systemBars())
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // Orientation: landscape videos go landscape, unless the user locked it the other way.
    LaunchedEffect(landscapeLock, videoLandscape) {
        val landscape = landscapeLock ?: videoLandscape ?: return@LaunchedEffect
        activity?.requestedOrientation = if (landscape) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else if (landscapeLock == false) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    DisposableEffect(controller) {
        val player = controller ?: return@DisposableEffect onDispose { }
        fun update(size: VideoSize) {
            if (size.width > 0 && size.height > 0) videoLandscape = size.width * size.pixelWidthHeightRatio > size.height
        }
        update(player.videoSize)
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) = update(videoSize)
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // A video doesn't keep playing (unseen) in the background: pause when the app is left.
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentIsVideo by rememberUpdatedState(state.current?.isVideo == true)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && currentIsVideo) connection.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (currentIsVideo) connection.pause()
        }
    }

    // Controls hide themselves while playing.
    LaunchedEffect(controlsVisible, state.isPlaying, interaction, resumePrompt) {
        if (controlsVisible && state.isPlaying && resumePrompt == null) {
            delay(3_500)
            controlsVisible = false
        }
    }
    LaunchedEffect(seekHintKey) {
        if (seekHint != null) {
            delay(700)
            seekHint = null
        }
    }

    BackHandler(onBack = onBack)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    setKeepContentOnPlayerReset(true)
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                }
            },
            update = { playerView ->
                playerView.player = controller
                playerView.resizeMode = if (zoom) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
                playerView.keepScreenOn = state.isPlaying
            },
            onRelease = { it.player = null },
            modifier = Modifier.fillMaxSize(),
        )

        // An audio item in the same queue: show its artwork instead of a black screen.
        val current = state.current
        if (current != null && !current.isVideo) {
            MediaArtwork(current.artwork, current.title, false, Modifier.align(Alignment.Center).size(240.dp), cornerRadius = 28.dp)
        }

        // Gestures: tap toggles the controls, double-tap on either half skips 10 s.
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            controlsVisible = !controlsVisible
                            interaction++
                        },
                        onDoubleTap = { offset ->
                            val forward = offset.x > size.width / 2f
                            connection.seekBy(if (forward) 10_000 else -10_000)
                            seekHint = if (forward) "+10 s" else "−10 s"
                            seekHintKey = System.nanoTime()
                        },
                    )
                },
        )

        seekHint?.let {
            Text(
                it,
                color = Color.White,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }

        AnimatedVisibility(controlsVisible || !state.isPlaying, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            VideoControls(
                state = state,
                connection = connection,
                zoom = zoom,
                onBack = onBack,
                onToggleZoom = { zoom = !zoom; interaction++ },
                onRotate = {
                    val landscapeNow = landscapeLock ?: videoLandscape ?: false
                    landscapeLock = !landscapeNow
                    interaction++
                },
                onSpeed = { speedDialog = true },
                onTracks = { tracksDialog = true },
                onInteract = { interaction++ },
            )
        }

        resumePrompt?.takeIf { it.mediaId == state.current?.mediaId }?.let { prompt ->
            GlassSurface(
                Modifier.align(Alignment.Center).padding(24.dp).widthIn(max = 420.dp),
                cornerRadius = 26.dp,
                style = GlassStyle.Regular,
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text("Continue watching?", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "You stopped at ${timeLabel(prompt.positionMs)}.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                        OutlinedButton(onClick = { connection.answerResumePrompt(resume = false) }) { Text("Start over") }
                        Button(onClick = { connection.answerResumePrompt(resume = true) }) {
                            Text("Resume from ${timeLabel(prompt.positionMs)}")
                        }
                    }
                }
            }
        }
    }

    if (speedDialog) {
        ChoiceDialog(
            title = "Playback speed",
            options = SPEEDS.map { (if (it == 1f) "Normal" else "${it}×") to (it == state.speed) },
            onPick = { connection.setSpeed(SPEEDS[it]); speedDialog = false },
            onDismiss = { speedDialog = false },
        )
    }
    if (tracksDialog) {
        TracksDialog(state, connection, onDismiss = { tracksDialog = false })
    }
}

@Composable
private fun VideoControls(
    state: PlayerUiState,
    connection: PlayerConnection,
    zoom: Boolean,
    onBack: () -> Unit,
    onToggleZoom: () -> Unit,
    onRotate: () -> Unit,
    onSpeed: () -> Unit,
    onTracks: () -> Unit,
    onInteract: () -> Unit,
) {
    val white = Color.White
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.6f), 0.25f to Color.Transparent, 0.7f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.7f))),
    ) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 8.dp, vertical = 4.dp)) {
            // Top: back, title, speed, tracks, rotate
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close video", tint = white) }
                Column(Modifier.weight(1f)) {
                    Text(state.current?.title.orEmpty(), color = white, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    (state.contextTitle ?: state.current?.subtitle)?.let {
                        Text(it, color = white.copy(alpha = 0.75f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                IconButton(onClick = { onInteract(); onSpeed() }) { Icon(Icons.Filled.Speed, contentDescription = "Playback speed (${state.speed}×)", tint = white) }
                if (state.textTracks.isNotEmpty() || state.audioTracks.size > 1) {
                    IconButton(onClick = { onInteract(); onTracks() }) { Icon(Icons.Filled.ClosedCaption, contentDescription = "Audio and subtitles", tint = white) }
                }
                IconButton(onClick = onRotate) { Icon(Icons.Filled.ScreenRotation, contentDescription = "Rotate", tint = white) }
            }

            Spacer(Modifier.weight(1f))

            // Center: previous, −10, play/pause, +10, next
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onInteract(); connection.previous() }, enabled = state.hasPrevious || state.durationMs > 0) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous", tint = white, modifier = Modifier.size(34.dp))
                }
                IconButton(onClick = { onInteract(); connection.seekBy(-10_000) }) {
                    Icon(Icons.Filled.Replay10, contentDescription = "Back 10 seconds", tint = white, modifier = Modifier.size(34.dp))
                }
                val playing = state.playWhenReady && !state.ended
                Box(
                    Modifier
                        .size(72.dp)
                        .background(Color.White.copy(alpha = 0.18f), CircleShape)
                        .clickable(onClickLabel = if (playing) "Pause" else "Play") { onInteract(); connection.togglePlayPause() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = if (playing) "Pause" else "Play", tint = white, modifier = Modifier.size(44.dp))
                }
                IconButton(onClick = { onInteract(); connection.seekBy(10_000) }) {
                    Icon(Icons.Filled.Forward10, contentDescription = "Forward 10 seconds", tint = white, modifier = Modifier.size(34.dp))
                }
                IconButton(onClick = { onInteract(); connection.next() }, enabled = state.hasNext) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "Next", tint = if (state.hasNext) white else white.copy(alpha = 0.35f), modifier = Modifier.size(34.dp))
                }
            }

            Spacer(Modifier.weight(1f))

            // Bottom: volume, seek bar + fit/zoom
            VolumeSlider(Modifier.fillMaxWidth(0.6f).padding(horizontal = 16.dp), tint = white)
            Row(verticalAlignment = Alignment.CenterVertically) {
                SeekBar(state, connection, Modifier.weight(1f).padding(horizontal = 8.dp), textColor = white)
                IconButton(onClick = onToggleZoom) {
                    Icon(Icons.Filled.AspectRatio, contentDescription = if (zoom) "Fit to screen" else "Fill screen", tint = if (zoom) MaterialTheme.colorScheme.primary else white)
                }
            }
        }
    }
}

@Composable
private fun TracksDialog(state: PlayerUiState, connection: PlayerConnection, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = sheetContainerColor(),
        title = { Text("Audio & subtitles") },
        text = {
            LazyColumn {
                if (state.audioTracks.size > 1) {
                    item { Text("Audio", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 6.dp)) }
                    items(state.audioTracks) { option ->
                        ChoiceRow(option.label, option.selected) { connection.selectTrack(C.TRACK_TYPE_AUDIO, option) }
                    }
                }
                if (state.textTracks.isNotEmpty()) {
                    item { Text("Subtitles", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 6.dp)) }
                    item { ChoiceRow("Off", state.subtitlesOff) { connection.disableSubtitles() } }
                    items(state.textTracks) { option: TrackOption ->
                        ChoiceRow(option.label, option.selected && !state.subtitlesOff) { connection.selectTrack(C.TRACK_TYPE_TEXT, option) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun ChoiceDialog(title: String, options: List<Pair<String, Boolean>>, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = sheetContainerColor(),
        title = { Text(title) },
        text = {
            Column {
                options.forEachIndexed { index, (label, selected) -> ChoiceRow(label, selected) { onPick(index) } }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onClick).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
