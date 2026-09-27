package com.enoluca.ytd.radio

import com.enoluca.ytd.data.local.db.RadioDao
import com.enoluca.ytd.data.local.db.RadioStationEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Stations for the Radio tab and the player: the bundled [catalog] (instant, offline), the live
 * [directory] (browse/search, fresh stream URLs) and the user's saved/recent stations ([dao]).
 */
class RadioRepository(
    private val catalog: RadioCatalog,
    private val directory: RadioDirectory,
    private val dao: RadioDao,
    private val now: () -> Long = System::currentTimeMillis,
) {
    // --- Saved & recent -------------------------------------------------------------------------

    val favorites: Flow<List<RadioStation>> = dao.observeFavorites().map { rows -> rows.map { it.toStation() } }
    val recent: Flow<List<RadioStation>> = dao.observeRecent(RECENT_LIMIT).map { rows -> rows.map { it.toStation() } }
    val favoriteIds: Flow<Set<String>> = dao.observeAll().map { rows -> rows.filter { it.favorite }.map { it.id }.toSet() }

    suspend fun setFavorite(station: RadioStation, favorite: Boolean) {
        remember(station)
        dao.setFavorite(station.id, favorite, now())
        if (!favorite) dao.deleteUnused()
    }

    /** Called when a station starts playing: it moves to the top of Recently played. */
    suspend fun recordPlayed(stationId: String) {
        val known = dao.get(stationId) ?: find(stationId)?.let { remember(it); dao.get(stationId) } ?: return
        dao.recordPlay(known.id, now())
        dao.trimRecent(RECENT_LIMIT)
        dao.deleteUnused()
    }

    suspend fun clearRecent() {
        dao.clearRecent()
        dao.deleteUnused()
    }

    /** Stores (or refreshes) a station's details, keeping its favorite/recent state. */
    suspend fun remember(station: RadioStation) = lock.withLock {
        val existing = dao.get(station.id)
        dao.upsert(
            RadioStationEntity(
                id = station.id,
                name = station.name,
                countryCode = station.countryCode,
                city = station.city,
                genre = station.genre,
                category = station.category.key,
                streamUrl = station.streamUrl,
                logoUrl = station.logoUrl,
                websiteUrl = station.websiteUrl,
                codec = station.codec,
                bitrate = station.bitrate,
                hls = station.isHls,
                favorite = existing?.favorite ?: false,
                favoritedAt = existing?.favoritedAt,
                lastPlayedAt = existing?.lastPlayedAt,
                playCount = existing?.playCount ?: 0,
                updatedAt = now(),
            ),
        )
    }

    // --- Browse & search ------------------------------------------------------------------------

    /** Hand-picked stations of a country (bundled; works offline). */
    fun featured(countryCode: String): List<RadioStation> = catalog.stations().filter { it.countryCode.equals(countryCode, ignoreCase = true) }

    /** Directory stations of a country (throws if the directory can't be reached), cached for a while. */
    suspend fun countryStations(countryCode: String): List<RadioStation> =
        cached("country:$countryCode") { directory.byCountry(countryCode) }

    /** Popular stations worldwide (throws if the directory can't be reached). */
    suspend fun international(): List<RadioStation> = cached("top") { directory.top() }

    /** Instant matches among bundled and saved stations. */
    suspend fun searchLocal(query: String, saved: List<RadioStation>): List<RadioStation> =
        RadioSearch.merge(saved, catalog.stations()).filter { RadioSearch.matches(it, query) }

    /** Directory matches (throws if the directory can't be reached). */
    suspend fun searchDirectory(query: String): List<RadioStation> = directory.search(query)

    // --- Playback -------------------------------------------------------------------------------

    /** Everything we know about [stationId] locally: saved row, then the bundled catalog. */
    suspend fun find(stationId: String): RadioStation? =
        dao.get(stationId)?.toStation() ?: catalog.stations().firstOrNull { it.id == stationId }

    /**
     * The station ready to play: its known details with the stream URL the directory reports
     * right now (stations move their streams), falling back to the last known URL if the
     * directory is slow or unreachable.
     */
    suspend fun resolveForPlayback(stationId: String): RadioStation? {
        val known = find(stationId) ?: return null
        val fresh = withTimeoutOrNull(FRESH_URL_TIMEOUT_MS) { directory.currentStreamUrl(stationId) }
        val url = RadioUrls.preferSecure(known.streamUrl, fresh)
        if (url != known.streamUrl && dao.get(stationId) != null) remember(known.copy(streamUrl = url))
        return known.copy(streamUrl = url)
    }

    // --- helpers --------------------------------------------------------------------------------

    private val lock = Mutex()
    private val cacheLock = Mutex()
    private val cache = HashMap<String, Pair<Long, List<RadioStation>>>()

    private suspend fun cached(key: String, load: suspend () -> List<RadioStation>): List<RadioStation> {
        cacheLock.withLock { cache[key]?.takeIf { now() - it.first < CACHE_MS }?.let { return it.second } }
        val fresh = load()
        cacheLock.withLock { cache[key] = now() to fresh }
        return fresh
    }

    companion object {
        const val RECENT_LIMIT = 20
        private const val CACHE_MS = 30 * 60 * 1000L
        private const val FRESH_URL_TIMEOUT_MS = 3_500L

        fun RadioStationEntity.toStation() = RadioStation(
            id = id,
            name = name,
            countryCode = countryCode,
            city = city,
            genre = genre,
            category = RadioCategory.fromKey(category),
            streamUrl = streamUrl,
            logoUrl = logoUrl,
            websiteUrl = websiteUrl,
            codec = codec,
            bitrate = bitrate,
            isHls = hls,
            isFavorite = favorite,
            lastPlayedAt = lastPlayedAt,
        )
    }
}
