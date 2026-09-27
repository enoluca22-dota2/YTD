package com.enoluca.ytd.playback

import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.enoluca.ytd.library.LibraryRepository
import com.enoluca.ytd.radio.RadioRepository
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.guava.future

/**
 * Turns the media ids controllers send (the app's UI, the notification, Bluetooth, the lock
 * screen) into playable items: Library files and radio stations. Ids that aren't in the Library
 * (or whose file is missing) or unknown stations are dropped instead of failing the whole queue.
 */
class PlaybackSessionCallback(
    private val library: LibraryRepository,
    private val radio: RadioRepository,
    private val snapshots: PlaybackSnapshotStore,
    private val scope: CoroutineScope,
    /** Nothing loaded and a controller (e.g. the app leaving the screen) went away: stop the service. */
    private val onIdleDisconnect: () -> Unit = {},
) : MediaSession.Callback {

    override fun onDisconnected(session: MediaSession, controller: MediaSession.ControllerInfo) {
        if (session.player.mediaItemCount == 0) onIdleDisconnect()
    }

    override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
        val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon().add(STOP_COMMAND).build()
        return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
            .setAvailableSessionCommands(commands)
            .build()
    }

    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle,
    ): ListenableFuture<SessionResult> {
        if (customCommand.customAction != STOP_ACTION) return super.onCustomCommand(session, controller, customCommand, args)
        // "Stop" (live radio): end playback and let the service and its notification go away.
        session.player.stop()
        session.player.clearMediaItems()
        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
    }

    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
    ): ListenableFuture<MutableList<MediaItem>> = scope.future {
        resolve(mediaItems).map { it.second }.toMutableList()
    }

    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<MediaItemsWithStartPosition> = scope.future {
        val resolved = resolve(mediaItems)
        // Keep pointing at the item the user tapped, even if items before it were dropped.
        val newStart = when (startIndex) {
            C.INDEX_UNSET -> C.INDEX_UNSET
            else -> resolved.indexOfFirst { it.first == startIndex }.takeIf { it >= 0 } ?: 0
        }
        MediaItemsWithStartPosition(resolved.map { it.second }, newStart, startPositionMs)
    }

    /**
     * Headset/Bluetooth "play" after the app was closed: continue the saved queue where it
     * stopped, else the last played Library item.
     */
    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        isForPlayback: Boolean,
    ): ListenableFuture<MediaItemsWithStartPosition> = scope.future {
        snapshots.restore(::resolve)?.let { return@future MediaItemsWithStartPosition(it.items, it.index, it.positionMs) }
        val last = library.lastPlayed() ?: throw UnsupportedOperationException("Nothing played yet")
        MediaItemsWithStartPosition(listOf(PlaybackItems.playable(last, null)), 0, last.resumePositionMs)
    }

    /** (original index, playable item) for every request that is an available Library item or a known station. */
    suspend fun resolve(requests: List<MediaItem>): List<Pair<Int, MediaItem>> {
        val ids = requests.mapNotNull { PlaybackItems.libraryId(it) }
        val byId = library.getMedia(ids).associateBy { it.id }
        return requests.mapIndexedNotNull { index, request ->
            PlaybackItems.radioId(request)?.let { stationId ->
                val station = radio.resolveForPlayback(stationId) ?: return@mapIndexedNotNull null
                return@mapIndexedNotNull index to PlaybackItems.radioPlayable(station)
            }
            val media = PlaybackItems.libraryId(request)?.let(byId::get)?.takeIf { it.isAvailable } ?: return@mapIndexedNotNull null
            index to PlaybackItems.playable(media, PlaybackItems.contextOf(request), PlaybackItems.contextPlaylistOf(request))
        }
    }

    companion object {
        const val STOP_ACTION = "enagelyuca.STOP"
        val STOP_COMMAND = SessionCommand(STOP_ACTION, Bundle.EMPTY)

        /** Notification/lock-screen button for live radio, in place of "next" (there is no next on live radio). */
        val STOP_BUTTON: CommandButton = CommandButton.Builder(CommandButton.ICON_STOP)
            .setDisplayName("Stop")
            .setSessionCommand(STOP_COMMAND)
            .setSlots(CommandButton.SLOT_FORWARD)
            .build()
    }
}
