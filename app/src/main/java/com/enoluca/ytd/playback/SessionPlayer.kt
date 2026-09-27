package com.enoluca.ytd.playback

import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.Player

/**
 * The player as the media session (notification, lock screen, Bluetooth, cars) and the app see
 * it. Some stations embed machine data in their stream metadata (RTL 102.5 sends a JSON
 * schedule); for live radio such text is dropped and the station's own details are shown instead.
 * Library files are passed through unchanged.
 */
class SessionPlayer(player: Player) : ForwardingSimpleBasePlayer(player) {

    override fun getState(): State {
        val state = super.getState()
        val item = player.currentMediaItem
        if (!PlaybackItems.isRadio(item)) return state
        var builder: State.Builder? = null
        // Timed stream metadata (ICY / ID3): drop only the entries that carry machine data, so a
        // real "Artist - Song" still comes through.
        val timed = state.timedMetadata
        val kept = (0 until timed.length()).map(timed::get).filterNot { entry ->
            RadioMetadataText.hasMachineData(MediaMetadata.Builder().also(entry::populateMediaMetadata).build())
        }
        if (kept.size != timed.length()) builder = state.buildUpon().setTimedMetadata(Metadata(timed.presentationTimeUs, kept))
        val cleaned = RadioMetadataText.clean(state.currentMetadata, item!!.mediaMetadata)
        if (cleaned !== state.currentMetadata) builder = (builder ?: state.buildUpon()).setPlaylist(state.timeline, state.currentTracks, cleaned)
        return builder?.build() ?: state
    }
}

/** Stream metadata that is meant for machines, not people. */
object RadioMetadataText {
    fun isMachineData(text: CharSequence?): Boolean {
        val t = text?.trim() ?: return false
        if (t.isEmpty()) return false
        return t.first() in "{[<" || t.length > MAX_LENGTH || "\":\"" in t
    }

    /** Every text field stream metadata can fill (lock screens show several of them). */
    private fun texts(m: MediaMetadata): List<CharSequence?> = listOf(
        m.title, m.artist, m.albumTitle, m.albumArtist, m.displayTitle, m.subtitle, m.description, m.station,
        m.writer, m.composer, m.conductor, m.genre, m.compilation,
    )

    fun hasMachineData(m: MediaMetadata): Boolean = texts(m).any(::isMachineData)

    /** [live] with every machine-data text replaced by the station's value (or removed); the same instance if nothing changed. */
    fun clean(live: MediaMetadata, station: MediaMetadata): MediaMetadata {
        if (!hasMachineData(live)) return live
        return live.buildUpon()
            .setTitle(live.title.takeUnless(::isMachineData) ?: station.title)
            .setArtist(live.artist.takeUnless(::isMachineData) ?: station.artist)
            .setAlbumTitle(live.albumTitle.takeUnless(::isMachineData) ?: station.albumTitle)
            .setAlbumArtist(live.albumArtist.takeUnless(::isMachineData))
            .setDisplayTitle(live.displayTitle.takeUnless(::isMachineData))
            .setSubtitle(live.subtitle.takeUnless(::isMachineData))
            .setDescription(live.description.takeUnless(::isMachineData))
            .setStation(live.station.takeUnless(::isMachineData) ?: station.station)
            .setWriter(live.writer.takeUnless(::isMachineData))
            .setComposer(live.composer.takeUnless(::isMachineData))
            .setConductor(live.conductor.takeUnless(::isMachineData))
            .setGenre(live.genre.takeUnless(::isMachineData) ?: station.genre)
            .setCompilation(live.compilation.takeUnless(::isMachineData))
            .build()
    }

    private const val MAX_LENGTH = 200
}
