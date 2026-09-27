package com.enoluca.ytd.radio

import com.fasterxml.jackson.databind.JsonNode
import java.util.Locale

/**
 * Hand-picked stations shipped with the app (assets/radio/stations.json). Every stream in it was
 * checked when the file was generated; at play time the directory is asked for the current URL,
 * so a moved stream keeps working without an app update.
 */
interface RadioCatalog {
    fun stations(): List<RadioStation>
}

/**
 * A live station directory. The Radio tab only talks to this interface, so the directory service
 * can be replaced without touching the UI or the player.
 */
interface RadioDirectory {
    /** Working stations of a country, most listened first. */
    suspend fun byCountry(countryCode: String, limit: Int = 80): List<RadioStation>

    /** Most popular working stations worldwide. */
    suspend fun top(limit: Int = 40): List<RadioStation>

    /** Name, country, city or genre. */
    suspend fun search(query: String, limit: Int = 40): List<RadioStation>

    /**
     * The stream URL to play now for [stationId] (and a play count for the directory's ranking),
     * or null if the directory doesn't know it / can't be reached.
     */
    suspend fun currentStreamUrl(stationId: String): String?
}

/** Parses the bundled catalog. */
object RadioCatalogParser {
    fun parse(root: JsonNode): List<RadioStation> = root.path("stations").mapNotNull { s ->
        val id = s.text("id") ?: return@mapNotNull null
        val name = s.text("name") ?: return@mapNotNull null
        val url = s.text("streamUrl")?.takeIf(RadioUrls::isPlayable) ?: return@mapNotNull null
        val country = s.text("countryCode")?.uppercase(Locale.ROOT) ?: return@mapNotNull null
        RadioStation(
            id = id,
            name = name,
            countryCode = country,
            city = s.text("city"),
            genre = s.text("genre"),
            category = RadioCategory.fromKey(s.text("category")),
            streamUrl = url,
            logoUrl = s.text("logoUrl")?.takeIf { it.startsWith("https://") },
            websiteUrl = s.text("websiteUrl"),
            codec = s.text("codec"),
            bitrate = s.path("bitrate").takeIf { it.isNumber }?.asInt()?.takeIf { it > 0 },
            isHls = s.path("hls").asBoolean(false) || url.contains(".m3u8", ignoreCase = true),
        )
    }
}

/** Parses Radio Browser (api.radio-browser.info) station lists. */
object RadioBrowserParser {
    /** Video codecs: TV streams listed as "stations" are left out of a radio app. */
    private val VIDEO_CODECS = listOf("H.264", "H.265", "HEVC", "VP8", "VP9", "AV1", "THEORA")
    private val NEWS_TAGS = listOf("news", "talk", "politic", "public radio")
    private val ENTERTAINMENT_TAGS = listOf("sport", "comedy", "entertainment", "drama", "kids", "children")

    fun stations(root: JsonNode): List<RadioStation> {
        val seen = HashSet<String>()
        return root.mapNotNull(::station).filter { seen.add(it.id) && seen.add("url:" + it.streamUrl) }
    }

    fun station(s: JsonNode): RadioStation? {
        if (s.path("lastcheckok").asInt(1) != 1) return null
        val codec = s.text("codec")
        if (codec != null && VIDEO_CODECS.any { codec.uppercase(Locale.ROOT).contains(it) }) return null
        val id = s.text("stationuuid") ?: return null
        val name = s.text("name")?.trim()?.replace(Regex("\\s+"), " ") ?: return null
        val url = (s.text("url_resolved") ?: s.text("url"))?.trim()?.takeIf(RadioUrls::isPlayable) ?: return null
        val tags = s.text("tags").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() && it.length <= 24 }
        return RadioStation(
            id = id,
            name = name,
            countryCode = s.text("countrycode")?.uppercase(Locale.ROOT) ?: "",
            city = s.text("state")?.trim()?.takeIf { it.isNotEmpty() && it.length <= 24 },
            genre = tags.take(2).joinToString(" · ") { tag -> tag.replaceFirstChar { it.titlecase(Locale.ROOT) } }.ifEmpty { null },
            category = categoryOf(tags),
            streamUrl = url,
            logoUrl = s.text("favicon")?.takeIf { it.startsWith("https://") },
            websiteUrl = s.text("homepage"),
            codec = codec?.takeIf { it != "UNKNOWN" },
            bitrate = s.path("bitrate").asInt(0).takeIf { it > 0 },
            isHls = s.path("hls").asInt(0) == 1 || url.contains(".m3u8", ignoreCase = true),
        )
    }

    fun categoryOf(tags: List<String>): RadioCategory {
        val lower = tags.map { it.lowercase(Locale.ROOT) }
        return when {
            lower.any { t -> NEWS_TAGS.any { t.contains(it) } } -> RadioCategory.NEWS
            lower.any { t -> ENTERTAINMENT_TAGS.any { t.contains(it) } } -> RadioCategory.ENTERTAINMENT
            else -> RadioCategory.MUSIC
        }
    }

    /** `/json/url/{uuid}` answer → the URL to play. */
    fun clickUrl(root: JsonNode): String? =
        root.takeIf { it.path("ok").asBoolean(false) }?.text("url")?.trim()?.takeIf(RadioUrls::isPlayable)
}

object RadioUrls {
    fun isPlayable(url: String): Boolean = url.startsWith("https://") || url.startsWith("http://")

    /**
     * Keeps a known HTTPS URL when the directory reports the same address over HTTP (the catalog
     * stores the HTTPS form of streams that were verified to work over it).
     */
    fun preferSecure(stored: String, fresh: String?): String {
        if (fresh == null) return stored
        return if (stored.startsWith("https://") && fresh.substringAfter("://") == stored.substringAfter("://")) stored else fresh
    }
}

private fun JsonNode.text(field: String): String? = path(field).takeIf { it.isTextual }?.asText()?.takeIf { it.isNotBlank() }
