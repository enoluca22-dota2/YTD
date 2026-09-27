package com.enoluca.ytd.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.enoluca.ytd.MainActivity
import com.enoluca.ytd.R
import com.enoluca.ytd.YtdApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Plays Library media and live radio: one ExoPlayer in a MediaSessionService. Media3 turns the session into the
 * media notification, lock-screen controls and Bluetooth/headset button handling, and keeps this
 * service in the foreground only while something is playing — the UI process isn't kept alive.
 *
 * The app's screens talk to it through [PlayerConnection] (a MediaController), exactly like the
 * system UI does.
 */
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private var tracker: PlaybackTracker? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        val container = (application as YtdApplication).container
        val player = ExoPlayer.Builder(this)
            // Pauses for calls/other apps, ducks for notifications.
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                /* handleAudioFocus = */ true,
            )
            // Headphones unplugged / Bluetooth disconnected → pause instead of blaring from the speaker.
            .setHandleAudioBecomingNoisy(true)
            // Keeps the CPU awake for local files while the screen is off (radio switches to
            // WAKE_MODE_NETWORK, which also holds Wi-Fi, in [onItemChanged]).
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setSeekBackIncrementMs(SEEK_INCREMENT_MS)
            .setSeekForwardIncrementMs(SEEK_INCREMENT_MS)
            .build()

        val openPlayer = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .setAction(MainActivity.ACTION_OPEN_PLAYER)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // Stopped from inside the app: the app's controller is still bound when the queue empties,
        // so the service is stopped again once it disconnects (stopSelf never ends a bound service
        // early; it only ends the "started" state so nothing lingers after the app leaves).
        val callback = PlaybackSessionCallback(
            container.libraryRepository, container.radioRepository, container.playbackSnapshots, scope,
            onIdleDisconnect = { stopSelf() },
        )
        // Controllers (the app, notification, Bluetooth…) see the player through SessionPlayer,
        // which keeps radio stream metadata human-readable.
        session = MediaSession.Builder(this, SessionPlayer(player))
            .setCallback(callback)
            .setSessionActivity(openPlayer)
            .build()
        val tracker = PlaybackTracker(
            player, container.libraryRepository, container.radioRepository, container.playbackSnapshots, scope, container.applicationScope,
        ).also(player::addListener)
        this.tracker = tracker
        player.addListener(object : Player.Listener {
            private var pausedAt = 0L

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = onItemChanged(player, mediaItem)

            // Stopped / queue cleared (e.g. radio "Stop"): nothing left to play, so don't stay
            // started in the background. A later play from the app starts the service again.
            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                if (timeline.isEmpty) stopSelf()
            }

            // Live radio resumed after a real pause (from any controller: app, notification,
            // headset): rejoin the live broadcast instead of playing stale buffered audio.
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!PlaybackItems.isRadio(player.currentMediaItem)) return
                if (!playWhenReady) {
                    pausedAt = android.os.SystemClock.elapsedRealtime()
                } else if (pausedAt > 0 && android.os.SystemClock.elapsedRealtime() - pausedAt > RADIO_REJOIN_AFTER_MS) {
                    pausedAt = 0
                    player.seekToDefaultPosition()
                    if (player.playbackState == Player.STATE_IDLE) player.prepare()
                }
            }
        })
        onItemChanged(player, player.currentMediaItem)

        // Reopen with the queue the user left (paused, same place). Skipped if a controller has
        // already started something in the meantime.
        scope.launch {
            val restored = runCatching { container.playbackSnapshots.restore(callback::resolve) }.getOrNull()
            if (restored != null && player.mediaItemCount == 0) {
                player.shuffleModeEnabled = restored.shuffle
                player.repeatMode = restored.repeatMode
                player.setMediaItems(restored.items, restored.index, restored.positionMs)
            }
            tracker.snapshotsEnabled = true
        }
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().apply { setSmallIcon(R.drawable.ic_notification_music) },
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /**
     * Live radio vs files: radio holds Wi-Fi while playing and offers Stop instead of skip
     * buttons (a single live station has no next/previous, and Media3 only shows commands the
     * player really supports).
     */
    private fun onItemChanged(player: ExoPlayer, item: MediaItem?) {
        val radio = PlaybackItems.isRadio(item)
        player.setWakeMode(if (radio) C.WAKE_MODE_NETWORK else C.WAKE_MODE_LOCAL)
        session?.setMediaButtonPreferences(if (radio) listOf(PlaybackSessionCallback.STOP_BUTTON) else emptyList())
    }

    /** App swiped away: keep playing if music is playing, otherwise there's no reason to stay. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0 || player.playbackState == Player.STATE_ENDED) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        tracker?.release()
        session?.run {
            player.release()
            release()
        }
        session = null
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val SEEK_INCREMENT_MS = 10_000L
        const val RADIO_REJOIN_AFTER_MS = 15_000L
    }
}
