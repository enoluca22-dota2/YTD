package com.enoluca.ytd.radio

import android.content.Context
import android.util.Log
import com.enoluca.ytd.BuildConfig
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * [RadioDirectory] backed by Radio Browser (https://www.radio-browser.info), a free, public,
 * community-maintained station directory that re-checks every stream regularly. Only stations
 * whose last check succeeded are used (`hidebroken=true` + [RadioBrowserParser]).
 */
class RadioBrowserDirectory(
    private val mapper: ObjectMapper = ObjectMapper(),
) : RadioDirectory {

    override suspend fun byCountry(countryCode: String, limit: Int): List<RadioStation> =
        list("/json/stations/bycountrycodeexact/${enc(countryCode)}?hidebroken=true&order=votes&reverse=true&limit=$limit")

    override suspend fun top(limit: Int): List<RadioStation> =
        list("/json/stations/search?hidebroken=true&order=votes&reverse=true&limit=$limit")

    override suspend fun search(query: String, limit: Int): List<RadioStation> = coroutineScope {
        val q = enc(query.trim())
        val common = "hidebroken=true&order=votes&reverse=true&limit=$limit"
        // One query per field; the directory ANDs parameters, the user means "any of these".
        val byName = async { runCatching { list("/json/stations/search?name=$q&$common") }.getOrDefault(emptyList()) }
        val byTag = async { runCatching { list("/json/stations/search?tag=$q&$common") }.getOrDefault(emptyList()) }
        val byCountry = async { runCatching { list("/json/stations/search?country=$q&$common") }.getOrDefault(emptyList()) }
        val byCity = async { runCatching { list("/json/stations/search?state=$q&$common") }.getOrDefault(emptyList()) }
        val results = listOf(byName.await(), byCountry.await(), byCity.await(), byTag.await())
        if (results.all { it.isEmpty() }) {
            // Distinguish "no match" from "directory unreachable": probe once, throwing if offline.
            list("/json/stations/search?name=$q&$common")
        } else {
            RadioSearch.merge(*results.toTypedArray())
        }
    }

    override suspend fun currentStreamUrl(stationId: String): String? =
        runCatching { RadioBrowserParser.clickUrl(get("/json/url/${enc(stationId)}")) }
            .onFailure { Log.i(TAG, "Couldn't refresh stream URL for $stationId: ${it.message}") }
            .getOrNull()

    private suspend fun list(path: String): List<RadioStation> = RadioBrowserParser.stations(get(path))

    /** GET on the first mirror that answers. */
    private suspend fun get(path: String): JsonNode = withContext(Dispatchers.IO) {
        var last: Exception? = null
        for (host in MIRRORS) {
            val connection = URL("https://$host$path").openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 8_000
                connection.readTimeout = 12_000
                connection.setRequestProperty("User-Agent", USER_AGENT)
                connection.setRequestProperty("Accept", "application/json")
                val code = connection.responseCode
                if (code !in 200..299) throw IOException("HTTP $code from $host")
                return@withContext connection.inputStream.use { mapper.readTree(it) }
            } catch (e: Exception) {
                last = e
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Radio directory unavailable", last)
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private companion object {
        const val TAG = "RadioDirectory"
        /** Radio Browser asks clients to identify themselves. */
        val USER_AGENT = "ENAGELYUCA/${BuildConfig.VERSION_NAME} (Android)"
        val MIRRORS = listOf("de1.api.radio-browser.info", "de2.api.radio-browser.info", "all.api.radio-browser.info")
    }
}

/** [RadioCatalog] read once from assets/radio/stations.json. */
class AssetRadioCatalog(
    private val context: Context,
    private val mapper: ObjectMapper = ObjectMapper(),
) : RadioCatalog {
    private val stations: List<RadioStation> by lazy {
        runCatching { context.assets.open(ASSET).use { RadioCatalogParser.parse(mapper.readTree(it)) } }
            .onFailure { Log.e("RadioCatalog", "Couldn't read $ASSET", it) }
            .getOrDefault(emptyList())
    }

    override fun stations(): List<RadioStation> = stations

    companion object {
        const val ASSET = "radio/stations.json"
    }
}
