package com.enoluca.ytd.radio

import java.util.Locale

/**
 * A live internet radio station. [id] is the station's Radio Browser UUID for both curated and
 * directory stations, so a favorite made from either source is the same station.
 */
data class RadioStation(
    val id: String,
    val name: String,
    /** ISO 3166-1 alpha-2 ("AL", "GB", "IT"). */
    val countryCode: String,
    val city: String? = null,
    /** Short display genre ("News & Talk", "Pop"). */
    val genre: String? = null,
    val category: RadioCategory = RadioCategory.MUSIC,
    val streamUrl: String,
    /** HTTPS logo, or null → generated placeholder. */
    val logoUrl: String? = null,
    val websiteUrl: String? = null,
    val codec: String? = null,
    val bitrate: Int? = null,
    /** HLS (.m3u8) stream rather than a plain Icecast/Shoutcast one. */
    val isHls: Boolean = false,
    val isFavorite: Boolean = false,
    val lastPlayedAt: Long? = null,
) {
    val country: RadioCountry? get() = RadioCountries.byCode(countryCode)
    val countryName: String get() = country?.name ?: RadioCountries.displayName(countryCode)

    /** "Tirana · News & Talk", "UK · Pop" — the second line on cards. */
    val subtitle: String get() = listOfNotNull(city?.takeIf { it.isNotBlank() } ?: countryName, genre).joinToString(" · ")

    /** "MP3 · 128 kbps" when known. */
    val qualityLabel: String?
        get() = listOfNotNull(codec?.takeIf { it.isNotBlank() && it != "UNKNOWN" }, bitrate?.takeIf { it > 0 }?.let { "$it kbps" })
            .joinToString(" · ").ifEmpty { null }
}

/** How stations are grouped inside a country (matches the curated catalog's "category"). */
enum class RadioCategory(val key: String, val label: String) {
    POPULAR("popular", "Popular"),
    MUSIC("music", "Music"),
    NEWS("news", "News & Talk"),
    ENTERTAINMENT("entertainment", "Entertainment"),
    LOCAL("local", "Local"),
    ;

    companion object {
        fun fromKey(key: String?): RadioCategory = entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: MUSIC
    }
}

/** A country section of the Radio tab. Adding a country = one line in [RadioCountries.all]. */
data class RadioCountry(val code: String, val name: String) {
    /** Regional-indicator flag emoji from the ISO code ("AL" → 🇦🇱). */
    val flag: String get() = RadioCountries.flagOf(code)
}

object RadioCountries {
    /** Countries shown on the Radio home, in order. Their stations come from the catalog + directory. */
    val all: List<RadioCountry> = listOf(
        RadioCountry("AL", "Albania"),
        RadioCountry("GB", "United Kingdom"),
        RadioCountry("IT", "Italy"),
    )

    fun byCode(code: String): RadioCountry? = all.firstOrNull { it.code.equals(code, ignoreCase = true) }

    fun displayName(code: String): String {
        val cc = code.uppercase(Locale.ROOT)
        return runCatching { Locale.Builder().setRegion(cc).build().getDisplayCountry(Locale.ENGLISH) }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: cc
    }

    fun flagOf(code: String): String {
        val cc = code.uppercase(Locale.ROOT)
        if (cc.length != 2 || !cc.all { it in 'A'..'Z' }) return "🌍"
        return String(Character.toChars(0x1F1E6 + (cc[0] - 'A'))) + String(Character.toChars(0x1F1E6 + (cc[1] - 'A')))
    }
}

/** Local station search (name, country, city, genre), used instantly while the directory is queried. */
object RadioSearch {
    fun matches(station: RadioStation, query: String): Boolean {
        val words = query.trim().lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return false
        val haystack = listOfNotNull(
            station.name, station.countryName, station.countryCode, station.city, station.genre, station.category.label,
        ).joinToString(" ").lowercase(Locale.ROOT)
        return words.all { it in haystack }
    }

    /** Merges result lists, first occurrence of an id wins (curated before directory). */
    fun merge(vararg lists: List<RadioStation>): List<RadioStation> {
        val seen = HashSet<String>()
        return lists.flatMap { it }.filter { seen.add(it.id) }
    }
}
