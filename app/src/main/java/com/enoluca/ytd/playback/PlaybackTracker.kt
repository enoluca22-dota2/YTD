package com.enoluca.ytd.playback

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import com.enoluca.ytd.library.LibraryRepository
import com.enoluca.ytd.radio.RadioRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Library bookkeeping for what the player does: resume positions (saved while playing, on pause,
 * on skip and when playback stops), play counts / "recently played", durations the scan didn't
 * know, and files that turn out to be missing (skipped instead of stopping the queue).
 *
 * Also: radio stations played (Recently played in the Radio tab) and the queue snapshot that lets
 * the app reopen with the same Now Playing.
 *
 * Runs on the player's thread ([mainScope]); database writes go to [ioScope], which outlives the
 * service so the last position is still saved while it shuts down.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackTracker(
    private val player: Player,
    private val library: LibraryRepository,
    private val radio: RadioRepository,
    private val snapshots: PlaybackSnapshotStore,
    mainScope: CoroutineScope,
    private val ioScope: CoroutineScope,
) : Player.Listener {

    /** Snapshot writes run one at a time, in order, so an older queue never overwrites a newer one. */
    private val snapshotWriter = Dispatchers.IO.limitedParallelism(1)

    /** Off until the service restored the saved queue, so the empty start-up player doesn't erase it. */
    var snapshotsEnabled = false

    private var currentId: Long? = PlaybackItems.libraryId(player.currentMediaItem)
    private var lastPositionMs = 0L
    private var lastDurationMs: Long? = null

    private val ticker: Job = mainScope.launch {
        var ticks = 0
        while (isActive) {
            delay(1_000)
            sample()
            if (player.isPlaying && ++ticks % SAVE_EVERY_TICKS == 0) {
                saveCurrent()
                saveSnapshot()
            }
        }
    }

    private fun sample() {
        if (PlaybackItems.libraryId(player.currentMediaItem) != currentId) return
        lastPositionMs = player.currentPosition
        lastDurationMs = player.duration.takeIf { it != C.TIME_UNSET && it > 0 }
    }

    private fun save(id: Long, positionMs: Long, durationMs: Long?) {
        ioScope.launch { runCatching { library.saveResumePosition(id, positionMs, durationMs) } }
    }

    private fun saveCurrent() {
        val id = currentId ?: return
        save(id, lastPositionMs, lastDurationMs)
    }

    /** Stores what's loaded now (or that nothing is). */
    fun saveSnapshot() {
        if (!snapshotsEnabled) return
        val snapshot = QueueSnapshot.of(player)
        ioScope.launch(snapshotWriter) { runCatching { snapshots.save(snapshot) }.onFailure { Log.w(TAG, "Couldn't save the queue", it) } }
    }

    override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
        if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) saveSnapshot()
    }

    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = saveSnapshot()

    override fun onRepeatModeChanged(repeatMode: Int) = saveSnapshot()

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        val previous = currentId
        val next = PlaybackItems.libraryId(mediaItem)
        if (previous != null && (previous != next || reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT)) {
            // Played to the end → start over next time; skipped → remember where it stopped.
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
                save(previous, 0, lastDurationMs)
            } else {
                save(previous, lastPositionMs, lastDurationMs)
            }
        }
        currentId = next
        lastPositionMs = player.currentPosition
        lastDurationMs = null
        if (next != null) ioScope.launch { runCatching { library.recordPlay(next) } }
        PlaybackItems.radioId(mediaItem)?.let { station -> ioScope.launch { runCatching { radio.recordPlayed(station) } } }
        saveSnapshot()
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (!isPlaying) {
            sample()
            saveCurrent()
            saveSnapshot()
        }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        when (playbackState) {
            Player.STATE_READY -> {
                sample()
                val id = currentId ?: return
                lastDurationMs?.let { duration -> ioScope.launch { runCatching { library.fillDuration(id, duration) } } }
            }
            Player.STATE_ENDED -> currentId?.let { save(it, 0, lastDurationMs) }
            else -> Unit
        }
    }

    override fun onPlayerError(error: PlaybackException) {
        PlaybackItems.radioId(player.currentMediaItem)?.let { station ->
            // Live radio: the UI says the station can't be reached; nothing to mark or skip.
            Log.w(TAG, "Station $station failed: ${error.errorCodeName}", error)
            return
        }
        val id = currentId ?: return
        Log.w(TAG, "Playback of $id failed: ${error.errorCodeName}", error)
        if (error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_NO_PERMISSION ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED
        ) {
            ioScope.launch { runCatching { library.markMissingIfGone(id) } }
        }
        // Don't let one broken file stop the rest of the queue.
        if (player.hasNextMediaItem()) {
            player.seekToNextMediaItem()
            player.prepare()
            player.play()
        }
    }

    /** The service is going away: keep the position of whatever was playing. */
    fun release() {
        ticker.cancel()
        sample()
        saveCurrent()
        saveSnapshot()
    }

    private companion object {
        const val TAG = "PlaybackTracker"
        const val SAVE_EVERY_TICKS = 10
    }
}
