package com.enoluca.ytd.ui.library

import android.content.IntentSender
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.enoluca.ytd.data.local.db.LibraryMediaEntity
import com.enoluca.ytd.data.local.db.LibraryStats
import com.enoluca.ytd.data.local.db.MediaType
import com.enoluca.ytd.data.local.db.PlaylistSummary
import com.enoluca.ytd.library.LibraryFilter
import com.enoluca.ytd.library.LibraryIndexer
import com.enoluca.ytd.library.LibraryRepository
import com.enoluca.ytd.playback.PlayerConnection
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One-off things for the screen to show. */
sealed interface LibraryEvent {
    data class Message(val text: String, val error: Boolean = false) : LibraryEvent

    /** Android must confirm deleting files that belong to other apps. */
    data class ConfirmDelete(val intentSender: IntentSender, val ids: List<Long>) : LibraryEvent
}

/**
 * Library screens, playlist screens and the players' "More" menus all act through this. Filters
 * run on the in-memory list; search runs in the database.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class LibraryViewModel(
    private val repository: LibraryRepository,
    private val indexer: LibraryIndexer,
    private val player: PlayerConnection,
) : ViewModel() {

    val filter = MutableStateFlow(LibraryFilter.ALL)
    val query = MutableStateFlow("")

    private val source: Flow<List<LibraryMediaEntity>> = query.debounce(150).flatMapLatest { q ->
        if (q.isBlank()) repository.observeAllMedia() else repository.search(q)
    }

    val media: StateFlow<List<LibraryMediaEntity>> = combine(source, filter) { list, f -> f.apply(list) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val playlists: StateFlow<List<PlaylistSummary>> = repository.observePlaylists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val stats: StateFlow<LibraryStats> = repository.observeStats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryStats(0, 0))

    /** False until the first database emission, so the empty state doesn't flash on open. */
    val loaded: StateFlow<Boolean> = repository.observeAllMedia().map { true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val scanState: StateFlow<LibraryIndexer.ScanState> = indexer.state

    private val _events = Channel<LibraryEvent>(Channel.BUFFERED)
    val events: Flow<LibraryEvent> = _events.receiveAsFlow()

    private fun message(text: String, error: Boolean = false) {
        _events.trySend(LibraryEvent.Message(text, error))
    }

    fun observePlaylist(id: Long) = repository.observePlaylist(id)
    fun observePlaylistTracks(id: Long) = repository.observePlaylistTracks(id)
    fun observeMedia(id: Long) = repository.observeMedia(id)

    // --- Playback -----------------------------------------------------------------------------

    /**
     * Plays [item] with the rest of [list] as the queue (same media type only, so a song never
     * switches into a video). Returns the type, so the caller can open the video player.
     */
    fun play(list: List<LibraryMediaEntity>, item: LibraryMediaEntity, contextTitle: String? = null, contextPlaylistId: Long? = null): MediaType? {
        if (!item.isAvailable) {
            message("This file is missing. It may have been moved or deleted.", error = true)
            return null
        }
        val queue = list.filter { it.mediaType == item.mediaType && it.isAvailable }
        player.play(queue, queue.indexOfFirst { it.id == item.id }.coerceAtLeast(0), contextTitle, contextPlaylistId = contextPlaylistId)
        return item.mediaType
    }

    /** Plays a whole playlist in its order (or shuffled). Returns the first item's type. */
    fun playAll(items: List<LibraryMediaEntity>, contextTitle: String?, shuffle: Boolean, contextPlaylistId: Long? = null): MediaType? {
        val playable = items.filter { it.isAvailable }
        if (playable.isEmpty()) {
            message("Nothing here can be played right now.", error = true)
            return null
        }
        player.play(playable, if (shuffle) -1 else 0, contextTitle, shuffle = shuffle, contextPlaylistId = contextPlaylistId)
        // Shuffle starts anywhere: only a single-type playlist tells which player to open.
        return if (!shuffle || playable.all { it.mediaType == playable.first().mediaType }) playable.first().mediaType else null
    }

    fun playNext(items: List<LibraryMediaEntity>) {
        player.playNext(items)
        message(if (items.size == 1) "Plays next" else "${items.size} items play next")
    }

    fun addToQueue(items: List<LibraryMediaEntity>) {
        player.addToQueue(items)
        message(if (items.size == 1) "Added to queue" else "${items.size} items added to queue")
    }

    // --- Media --------------------------------------------------------------------------------

    fun setFavorite(ids: List<Long>, favorite: Boolean) = viewModelScope.launch {
        repository.setFavorite(ids, favorite)
        message(if (favorite) "Added to Favorites" else "Removed from Favorites")
    }

    fun delete(ids: List<Long>) = viewModelScope.launch {
        val result = repository.deleteMedia(ids)
        if (result is LibraryRepository.DeleteResult.Done && result.deleted > 0) player.removeMedia(ids)
        handleDelete(result)
    }

    private fun handleDelete(result: LibraryRepository.DeleteResult?) {
        when (result) {
            null -> Unit
            is LibraryRepository.DeleteResult.Done -> {
                if (result.deleted > 0) message(if (result.deleted == 1) "Deleted" else "${result.deleted} items deleted")
                if (result.failed > 0) message("${result.failed} file(s) couldn't be deleted.", error = true)
            }
            is LibraryRepository.DeleteResult.NeedsConsent ->
                _events.trySend(LibraryEvent.ConfirmDelete(result.intentSender, result.pendingIds))
        }
    }

    /** The system delete dialog returned: [approved] if the user allowed it. */
    fun onDeleteConsent(ids: List<Long>, approved: Boolean) = viewModelScope.launch {
        if (!approved) return@launch
        val gone = repository.confirmDeleted(ids)
        player.removeMedia(ids)
        if (gone > 0) message(if (gone == 1) "Deleted" else "$gone items deleted")
    }

    fun forgetMissing() = viewModelScope.launch {
        val removed = repository.forgetMissing()
        message(if (removed == 0) "No missing files" else "Removed $removed missing item(s) from the Library")
    }

    fun forget(ids: List<Long>) = viewModelScope.launch {
        repository.forget(ids)
        message("Removed from the Library")
    }

    fun rescan() {
        indexer.requestRescan()
    }

    // --- Playlists ----------------------------------------------------------------------------

    fun createPlaylist(title: String, addIds: List<Long> = emptyList()) = viewModelScope.launch {
        repository.createPlaylistWith(title, addIds)
        message(if (addIds.isEmpty()) "Playlist created" else "Added to \"${title.trim()}\"")
    }

    /** Now Playing → Queue → Save: a new playlist with the queue's Library items, in play order. */
    fun saveQueueAsPlaylist(mediaIds: List<Long>, title: String) = viewModelScope.launch {
        val ids = mediaIds.distinct()
        if (ids.isEmpty()) return@launch
        val (_, added) = repository.createPlaylistWith(title, ids)
        message("Saved $added ${if (added == 1) "item" else "items"} to \"${title.trim()}\"")
    }

    fun addToPlaylist(playlist: PlaylistSummary, ids: List<Long>) = viewModelScope.launch {
        val added = repository.addToPlaylist(playlist.playlist.id, ids)
        val name = playlist.playlist.title
        message(
            when {
                added == 0 -> "Already in \"$name\""
                ids.size == 1 -> "Added to \"$name\""
                else -> "$added items added to \"$name\""
            }
        )
    }

    fun removeFromPlaylist(playlistId: Long, mediaId: Long) = viewModelScope.launch {
        repository.removeFromPlaylist(playlistId, mediaId)
        message("Removed from playlist. The file is still in your Library.")
    }

    fun movePlaylistItem(playlistId: Long, from: Int, to: Int) = viewModelScope.launch {
        repository.movePlaylistItem(playlistId, from, to)
    }

    fun renamePlaylist(id: Long, title: String) = viewModelScope.launch { repository.renamePlaylist(id, title) }

    fun setPlaylistFavorite(id: Long, favorite: Boolean) = viewModelScope.launch { repository.setPlaylistFavorite(id, favorite) }

    fun deletePlaylist(id: Long, withMedia: Boolean) = viewModelScope.launch {
        val result = repository.deletePlaylist(id, withMedia)
        if (withMedia) handleDelete(result) else message("Playlist deleted. Its media is still in your Library.")
    }
}
