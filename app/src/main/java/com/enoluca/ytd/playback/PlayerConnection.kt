package com.enoluca.ytd.playback

import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.enoluca.ytd.data.local.db.LibraryMediaEntity
import com.enoluca.ytd.data.local.db.MediaType
import com.enoluca.ytd.library.ResumePolicy
import com.enoluca.ytd.radio.RadioStation
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** One entry of the play queue, as the UI shows it. */
data class QueueEntry(
    val index: Int,
    val mediaId: Long?,
    val title: String,
    val subtitle: String?,
    val artwork: String?,
    val isVideo: Boolean,
    /** Set for live radio ("radio:<id>" items). */
    val radioStationId: String? = null,
) {
    val isRadio: Boolean get() = radioStationId != null
}

/** Everything the mini player, full player and video player draw (the position is polled separately). */
data class PlayerUiState(
    val connected: Boolean = false,
    val current: QueueEntry? = null,
    val contextTitle: String? = null,
    /** The Library playlist being played, if the queue came from one. */
    val contextPlaylistId: Long? = null,
    val isPlaying: Boolean = false,
    val playWhenReady: Boolean = false,
    val buffering: Boolean = false,
    val ended: Boolean = false,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val speed: Float = 1f,
    /** The queue in play order (shuffled order while shuffle is on). */
    val queue: List<QueueEntry> = emptyList(),
    val currentIndex: Int = -1,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
    val audioTracks: List<TrackOption> = emptyList(),
    val textTracks: List<TrackOption> = emptyList(),
    val subtitlesOff: Boolean = true,
    val error: String? = null,
    /** Live radio: the song/show the stream reports (ICY / HLS metadata), when it does. */
    val liveTitle: String? = null,
    val liveArtist: String? = null,
    val radioGenre: String? = null,
) {
    val hasMedia: Boolean get() = current != null
    val isRadio: Boolean get() = current?.isRadio == true
}

/** A selectable audio or subtitle track of the current file. */
data class TrackOption(val groupIndex: Int, val trackIndex: Int, val label: String, val selected: Boolean)

/** A video with a remembered position: the player asks "Resume / Start over" before playing. */
data class ResumePrompt(val mediaId: Long, val positionMs: Long)

/** Repeat off → all → one → off. */
fun nextRepeatMode(mode: Int): Int = when (mode) {
    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
    else -> Player.REPEAT_MODE_OFF
}

/**
 * The app's side of playback: a [MediaController] connected to [PlaybackService] while the UI is
 * visible (MainActivity start/stop). The service keeps playing in the background without it.
 * Every command waits briefly for the connection, so a tap right after launch still works.
 */
class PlayerConnection(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private var future: ListenableFuture<MediaController>? = null
    private val controllerFlow = MutableStateFlow<MediaController?>(null)
    private var users = 0

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private val _resumePrompt = MutableStateFlow<ResumePrompt?>(null)
    val resumePrompt: StateFlow<ResumePrompt?> = _resumePrompt.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = publish()
    }

    /** Call from the main thread (Activity onStart). */
    fun connect() {
        users++
        if (future != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val f = MediaController.Builder(context, token).buildAsync()
        future = f
        f.addListener({
            val controller = runCatching { f.get() }.getOrNull()
            if (controller == null || future !== f) {
                controller?.release()
                return@addListener
            }
            controller.addListener(listener)
            controllerFlow.value = controller
            publish()
        }, MoreExecutors.directExecutor())
    }

    /** Call from the main thread (Activity onStop). Playback itself continues in the service. */
    fun disconnect() {
        users = (users - 1).coerceAtLeast(0)
        if (users > 0) return
        future?.let(MediaController::releaseFuture)
        future = null
        controllerFlow.value?.removeListener(listener)
        controllerFlow.value = null
        _state.value = _state.value.copy(connected = false)
    }

    private suspend fun controller(): MediaController? =
        controllerFlow.value ?: withTimeoutOrNull(CONNECT_TIMEOUT_MS) { controllerFlow.filterNotNull().first() }

    private fun command(block: (MediaController) -> Unit) {
        scope.launch {
            val controller = controller()
            if (controller == null) {
                Log.w(TAG, "Player not connected; command dropped")
                return@launch
            }
            runCatching { block(controller) }.onFailure { Log.w(TAG, "Player command failed", it) }
        }
    }

    /** Current position of the playing item (polled by the UI while it's visible). */
    fun positionMs(): Long = controllerFlow.value?.currentPosition ?: 0L
    fun bufferedMs(): Long = controllerFlow.value?.bufferedPosition ?: 0L

    /** The controller, for PlayerView (video surface). */
    val player: StateFlow<MediaController?> get() = controllerFlow

    // --- Starting playback --------------------------------------------------------------------

    /**
     * Plays [items] from [startIndex] (the queue follows the list order). Long music continues
     * where it stopped; a video with a remembered position waits for "Resume / Start over".
     */
    fun play(
        items: List<LibraryMediaEntity>,
        startIndex: Int = 0,
        contextTitle: String? = null,
        shuffle: Boolean = false,
        contextPlaylistId: Long? = null,
    ) {
        val playable = items.filter { it.isAvailable }
        if (playable.isEmpty()) return
        val start = items.getOrNull(startIndex)?.let { tapped -> playable.indexOfFirst { it.id == tapped.id } }?.takeIf { it >= 0 }
            ?: if (shuffle) playable.indices.random() else 0
        val first = playable[start]
        val askResume = ResumePolicy.shouldOfferResume(first.mediaType, first.resumePositionMs, first.durationMs)
        val startPosition = when {
            askResume -> first.resumePositionMs
            else -> ResumePolicy.autoResumePosition(first.mediaType, first.resumePositionMs, first.durationMs)
        }
        _resumePrompt.value = if (askResume) ResumePrompt(first.id, first.resumePositionMs) else null
        command { c ->
            c.shuffleModeEnabled = shuffle
            c.setMediaItems(playable.map { PlaybackItems.request(it, contextTitle, contextPlaylistId) }, start, startPosition)
            c.prepare()
            // A video waiting for "Resume / Start over" starts paused.
            c.playWhenReady = !askResume
        }
    }

    /** Starts a live station (replaces the queue: live radio isn't queued with files). */
    fun playRadio(station: RadioStation) {
        _resumePrompt.value = null
        command { c ->
            c.shuffleModeEnabled = false
            c.setMediaItems(listOf(PlaybackItems.radioRequest(station)), 0, C.TIME_UNSET)
            c.prepare()
            c.play()
        }
    }

    /** Stops playback and empties the queue (the radio "Stop" button). */
    fun stop() = command { c ->
        c.stop()
        c.clearMediaItems()
    }

    fun answerResumePrompt(resume: Boolean) {
        _resumePrompt.value = null
        command { c ->
            if (!resume) c.seekTo(0)
            c.play()
        }
    }

    fun playNext(items: List<LibraryMediaEntity>) {
        val playable = items.filter { it.isAvailable }
        if (playable.isEmpty()) return
        command { c ->
            val requests = playable.map { PlaybackItems.request(it, null) }
            // Nothing loaded, or live radio (which never ends): these start a new queue.
            if (c.mediaItemCount == 0 || PlaybackItems.isRadio(c.currentMediaItem)) {
                c.setMediaItems(requests)
                c.prepare()
                c.play()
            } else {
                c.addMediaItems(c.currentMediaItemIndex + 1, requests)
            }
        }
    }

    fun addToQueue(items: List<LibraryMediaEntity>) {
        val playable = items.filter { it.isAvailable }
        if (playable.isEmpty()) return
        command { c ->
            val requests = playable.map { PlaybackItems.request(it, null) }
            // Nothing loaded, or live radio (which never ends): these start a new queue.
            if (c.mediaItemCount == 0 || PlaybackItems.isRadio(c.currentMediaItem)) {
                c.setMediaItems(requests)
                c.prepare()
                c.play()
            } else {
                c.addMediaItems(requests)
            }
        }
    }

    // --- Transport ----------------------------------------------------------------------------

    fun togglePlayPause() = command { c ->
        when {
            // After an error (e.g. a station that dropped): Play retries.
            c.playerError != null -> {
                c.prepare()
                c.play()
            }
            c.playbackState == Player.STATE_ENDED -> {
                c.seekToDefaultPosition(0)
                c.play()
            }
            c.isPlaying || c.playWhenReady -> c.pause()
            else -> {
                if (c.playbackState == Player.STATE_IDLE) c.prepare()
                c.play()
            }
        }
    }

    fun play() = command { c ->
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
        c.play()
    }

    fun pause() = command { it.pause() }
    fun next() = command { it.seekToNext() }
    fun previous() = command { it.seekToPrevious() }
    fun seekTo(positionMs: Long) = command { it.seekTo(positionMs.coerceAtLeast(0)) }
    fun seekBy(deltaMs: Long) = command { it.seekTo((it.currentPosition + deltaMs).coerceIn(0, it.duration.takeIf { d -> d > 0 } ?: Long.MAX_VALUE)) }
    fun setShuffle(enabled: Boolean) = command { it.shuffleModeEnabled = enabled }
    fun cycleRepeat() = command { it.repeatMode = nextRepeatMode(it.repeatMode) }
    fun setSpeed(speed: Float) = command { it.setPlaybackSpeed(speed) }

    // --- Queue --------------------------------------------------------------------------------

    fun playQueueItem(index: Int) = command { c ->
        if (index in 0 until c.mediaItemCount) {
            c.seekToDefaultPosition(index)
            c.play()
        }
    }

    fun removeFromQueue(index: Int) = command { c -> if (index in 0 until c.mediaItemCount) c.removeMediaItem(index) }

    fun moveInQueue(from: Int, to: Int) = command { c ->
        if (from in 0 until c.mediaItemCount && to in 0 until c.mediaItemCount) c.moveMediaItem(from, to)
    }

    fun clearQueue() = command { c ->
        c.stop()
        c.clearMediaItems()
    }

    /** Media deleted from the Library must also leave the queue. */
    fun removeMedia(ids: Collection<Long>) = command { c ->
        for (i in c.mediaItemCount - 1 downTo 0) {
            if (PlaybackItems.libraryId(c.getMediaItemAt(i)) in ids) c.removeMediaItem(i)
        }
    }

    // --- Tracks (video) -----------------------------------------------------------------------

    fun selectTrack(type: Int, option: TrackOption) = command { c ->
        val group = c.currentTracks.groups.getOrNull(option.groupIndex) ?: return@command
        c.trackSelectionParameters = c.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(type, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, option.trackIndex))
            .build()
    }

    fun disableSubtitles() = command { c ->
        c.trackSelectionParameters = c.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
    }

    // --- State --------------------------------------------------------------------------------

    private fun publish() {
        val c = controllerFlow.value ?: return
        val queue = playOrder(c).map { entryFor(it, c.getMediaItemAt(it)) }
        val index = c.currentMediaItemIndex
        val current = c.currentMediaItem?.let { entryFor(index, it) }
        val tracks = c.currentTracks
        _state.value = PlayerUiState(
            connected = true,
            current = current,
            contextTitle = PlaybackItems.contextOf(c.currentMediaItem),
            contextPlaylistId = PlaybackItems.contextPlaylistOf(c.currentMediaItem),
            isPlaying = c.isPlaying,
            playWhenReady = c.playWhenReady,
            buffering = c.playbackState == Player.STATE_BUFFERING,
            ended = c.playbackState == Player.STATE_ENDED,
            durationMs = c.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L,
            shuffle = c.shuffleModeEnabled,
            repeatMode = c.repeatMode,
            speed = c.playbackParameters.speed,
            queue = queue,
            currentIndex = index,
            hasNext = c.hasNextMediaItem(),
            hasPrevious = c.hasPreviousMediaItem(),
            audioTracks = trackOptions(tracks, C.TRACK_TYPE_AUDIO),
            textTracks = trackOptions(tracks, C.TRACK_TYPE_TEXT),
            subtitlesOff = C.TRACK_TYPE_TEXT in c.trackSelectionParameters.disabledTrackTypes ||
                tracks.groups.none { it.type == C.TRACK_TYPE_TEXT && it.isSelected },
            error = c.playerError?.let {
                if (PlaybackItems.isRadio(c.currentMediaItem)) "This station can't be reached right now." else "This file can't be played."
            },
            liveTitle = liveTitle(c),
            liveArtist = liveArtist(c),
            radioGenre = c.currentMediaItem?.mediaMetadata?.extras?.getString(PlaybackItems.EXTRA_RADIO_GENRE),
        )
    }

    /** For radio, the title the stream itself reports, if it differs from the station name. */
    private fun liveTitle(c: Player): String? {
        val item = c.currentMediaItem ?: return null
        if (!PlaybackItems.isRadio(item)) return null
        val live = c.mediaMetadata.title?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return live.takeIf { it != item.mediaMetadata.title?.toString() && it != item.mediaMetadata.station?.toString() }
    }

    private fun liveArtist(c: Player): String? {
        val item = c.currentMediaItem ?: return null
        if (!PlaybackItems.isRadio(item)) return null
        val live = c.mediaMetadata.artist?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return live.takeIf { it != item.mediaMetadata.artist?.toString() }
    }

    /** Item indices in the order they will play (the timeline's shuffle order while shuffling). */
    private fun playOrder(c: Player): List<Int> {
        val timeline = c.currentTimeline
        if (timeline.isEmpty) return emptyList()
        if (!c.shuffleModeEnabled) return (0 until timeline.windowCount).toList()
        val order = ArrayList<Int>(timeline.windowCount)
        var index = timeline.getFirstWindowIndex(true)
        while (index != C.INDEX_UNSET && order.size < timeline.windowCount) {
            order += index
            index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, true)
        }
        return order
    }

    private fun entryFor(index: Int, item: MediaItem): QueueEntry {
        val m = item.mediaMetadata
        return QueueEntry(
            index = index,
            mediaId = PlaybackItems.libraryId(item),
            title = m.title?.toString() ?: "Untitled",
            subtitle = m.artist?.toString(),
            artwork = m.artworkUri?.let { uri -> if (uri.scheme == "file") uri.path else uri.toString() },
            isVideo = PlaybackItems.isVideo(item),
            radioStationId = PlaybackItems.radioId(item),
        )
    }

    private fun trackOptions(tracks: Tracks, type: Int): List<TrackOption> =
        tracks.groups.withIndex().filter { it.value.type == type && it.value.isSupported }.flatMap { (groupIndex, group) ->
            (0 until group.length).map { trackIndex ->
                val format = group.getTrackFormat(trackIndex)
                val label = listOfNotNull(
                    format.label,
                    format.language?.let { java.util.Locale.forLanguageTag(it).displayLanguage.takeIf { l -> l.isNotBlank() } },
                    format.channelCount.takeIf { it > 0 && type == C.TRACK_TYPE_AUDIO }?.let { if (it >= 6) "Surround" else if (it == 2) "Stereo" else "Mono" },
                ).distinct().joinToString(" · ").ifBlank { "Track ${trackIndex + 1}" }
                TrackOption(groupIndex, trackIndex, label, group.isTrackSelected(trackIndex))
            }
        }

    /** True if [media] is what the player currently has loaded. */
    fun isCurrent(media: LibraryMediaEntity): Boolean = _state.value.current?.mediaId == media.id

    companion object {
        private const val TAG = "PlayerConnection"
        private const val CONNECT_TIMEOUT_MS = 5_000L

        /** Media type of the current item, for choosing the audio or video player screen. */
        fun typeOf(entry: QueueEntry?): MediaType? = entry?.let { if (it.isVideo) MediaType.VIDEO else MediaType.AUDIO }
    }
}
