package com.enoluca.ytd.ui.radio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.enoluca.ytd.playback.PlayerConnection
import com.enoluca.ytd.radio.RadioCountries
import com.enoluca.ytd.radio.RadioCountry
import com.enoluca.ytd.radio.RadioRepository
import com.enoluca.ytd.radio.RadioStation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A list loaded from the station directory. */
data class RemoteStations(
    val loading: Boolean = false,
    val stations: List<RadioStation> = emptyList(),
    /** Set when the directory couldn't be reached (the curated stations still work). */
    val error: String? = null,
)

data class RadioSearchState(
    val query: String = "",
    val local: List<RadioStation> = emptyList(),
    val remote: RemoteStations = RemoteStations(),
) {
    val active: Boolean get() = query.isNotBlank()
}

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class RadioViewModel(
    private val repository: RadioRepository,
    private val player: PlayerConnection,
) : ViewModel() {

    val countries: List<RadioCountry> = RadioCountries.all

    /** Station ids the user saved, for the ♥ on every card. */
    val favoriteIds: StateFlow<Set<String>> =
        repository.favoriteIds.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val favorites: StateFlow<List<RadioStation>> =
        repository.favorites.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val recent: StateFlow<List<RadioStation>> =
        repository.recent.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Directory lists by key ("country:AL", "top"). */
    private val _remote = MutableStateFlow<Map<String, RemoteStations>>(emptyMap())
    val remote: StateFlow<Map<String, RemoteStations>> = _remote.asStateFlow()

    val query = MutableStateFlow("")
    private val _search = MutableStateFlow(RadioSearchState())
    val search: StateFlow<RadioSearchState> = _search.asStateFlow()

    init {
        viewModelScope.launch {
            // Local matches right away, the directory after a short pause in typing.
            combine(query, favorites) { q, saved -> q to saved }.collectLatest { (q, saved) ->
                if (q.isBlank()) {
                    _search.value = RadioSearchState()
                    return@collectLatest
                }
                val local = repository.searchLocal(q, saved)
                _search.update { old ->
                    // Same words (e.g. a ♥ changed the saved list): keep the directory results.
                    val remote = if (old.query.trim() == q.trim()) old.remote else RemoteStations(loading = q.trim().length >= 2)
                    RadioSearchState(q, local, remote)
                }
            }
        }
        viewModelScope.launch {
            query.map { it.trim() }.distinctUntilChanged().debounce(450).collectLatest { q -> searchDirectory(q) }
        }
    }

    private suspend fun searchDirectory(q: String) {
        if (q.length < 2) return
        val result = try {
            RemoteStations(stations = repository.searchDirectory(q))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            RemoteStations(error = DIRECTORY_ERROR)
        }
        _search.update { if (it.query.trim() == q) it.copy(remote = result) else it }
    }

    fun retrySearch() {
        val q = query.value.trim()
        _search.update { it.copy(remote = RemoteStations(loading = true)) }
        viewModelScope.launch { searchDirectory(q) }
    }

    fun featured(countryCode: String): List<RadioStation> = repository.featured(countryCode)

    fun loadCountry(countryCode: String, force: Boolean = false) = load("country:$countryCode", force) { repository.countryStations(countryCode) }

    fun loadInternational(force: Boolean = false) = load("top", force) { repository.international() }

    private fun load(key: String, force: Boolean, block: suspend () -> List<RadioStation>) {
        val current = _remote.value[key]
        if (!force && current != null && (current.loading || current.error == null)) return
        _remote.update { it + (key to RemoteStations(loading = true, stations = current?.stations.orEmpty())) }
        viewModelScope.launch {
            val result = try {
                RemoteStations(stations = block())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                RemoteStations(error = DIRECTORY_ERROR, stations = current?.stations.orEmpty())
            }
            _remote.update { it + (key to result) }
        }
    }

    fun play(station: RadioStation) {
        viewModelScope.launch {
            // Saved first so the player can resolve directory stations too.
            safely("save ${station.id}") { repository.remember(station) }
            player.playRadio(station)
        }
    }

    fun toggleFavorite(station: RadioStation) {
        val favorite = station.id !in favoriteIds.value
        viewModelScope.launch { safely("favorite ${station.id}") { repository.setFavorite(station, favorite) } }
    }

    /** The station behind the player's current radio item (for the radio player screen). */
    suspend fun station(id: String): RadioStation? = repository.find(id)

    fun clearRecent() {
        viewModelScope.launch { safely("clear recent") { repository.clearRecent() } }
    }

    /** Database writes from the UI: a failure is logged, never a crash. */
    private suspend fun safely(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("RadioViewModel", "Couldn't $what", e)
        }
    }

    companion object {
        const val DIRECTORY_ERROR = "Can't reach the station directory right now."
    }
}
