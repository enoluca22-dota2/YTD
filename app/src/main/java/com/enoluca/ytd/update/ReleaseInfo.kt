package com.enoluca.ytd.update

import com.fasterxml.jackson.databind.JsonNode

data class ReleaseAsset(val name: String, val downloadUrl: String, val sizeBytes: Long?, val contentType: String?)

/** A published GitHub release, as read from the Releases API. Nothing here is invented. */
data class ReleaseInfo(
    val tag: String,
    val version: AppVersion,
    val name: String,
    /** The release body (Markdown) exactly as published; may be empty. */
    val notes: String,
    val htmlUrl: String?,
    val publishedAt: String?,
    val apk: ReleaseAsset,
    val checksums: ReleaseAsset?,
)

object ReleaseParser {

    sealed interface Result {
        data class Ok(val release: ReleaseInfo) : Result
        /** Draft / pre-release / not a version tag / no APK asset: not something to install. */
        data class Unusable(val reason: String) : Result
    }

    /**
     * Reads `GET /repos/{owner}/{repo}/releases/latest`. Picks the APK built for [deviceAbis]
     * (in the device's preference order) and falls back to the universal APK.
     */
    fun parse(root: JsonNode, deviceAbis: List<String>): Result {
        if (root.path("draft").asBoolean(false)) return Result.Unusable("draft")
        if (root.path("prerelease").asBoolean(false)) return Result.Unusable("pre-release")
        val tag = root.text("tag_name") ?: return Result.Unusable("no tag")
        val version = AppVersion.parse(tag) ?: return Result.Unusable("tag '$tag' is not a version")

        val assets = root.path("assets").mapNotNull { a ->
            val name = a.text("name") ?: return@mapNotNull null
            val url = a.text("browser_download_url") ?: return@mapNotNull null
            if (!url.startsWith("https://")) return@mapNotNull null
            ReleaseAsset(name, url, a.path("size").takeIf { it.isNumber }?.asLong(), a.text("content_type"))
        }
        val apks = assets.mapNotNull { asset ->
            val m = UpdateConfig.ASSET_PATTERN.matchEntire(asset.name) ?: return@mapNotNull null
            // The APK must belong to this release, not a stale file with another version.
            if (AppVersion.parse(m.groupValues[1])?.compareTo(version) != 0) return@mapNotNull null
            m.groupValues[2].ifEmpty { null } to asset
        }
        val apk = deviceAbis.firstNotNullOfOrNull { abi -> apks.firstOrNull { it.first == abi }?.second }
            ?: apks.firstOrNull { it.first == null }?.second
            ?: return Result.Unusable("no APK asset for this device")

        return Result.Ok(
            ReleaseInfo(
                tag = tag,
                version = version,
                name = root.text("name") ?: tag,
                notes = root.text("body").orEmpty().trim(),
                htmlUrl = root.text("html_url"),
                publishedAt = root.text("published_at"),
                apk = apk,
                checksums = assets.firstOrNull { it.name == UpdateConfig.CHECKSUM_ASSET },
            )
        )
    }

    /** Finds [fileName]'s hash in a `sha256sum`-style file ("<hex>  <name>" or "<hex> *<name>"). */
    fun checksumFor(sums: String, fileName: String): String? = sums.lineSequence()
        .map { it.trim() }
        .mapNotNull { line ->
            val parts = line.split(Regex("""\s+"""), limit = 2)
            if (parts.size != 2) return@mapNotNull null
            val hash = parts[0].lowercase()
            val name = parts[1].removePrefix("*").trim()
            if (name == fileName && hash.matches(Regex("[0-9a-f]{64}"))) hash else null
        }
        .firstOrNull()

    private fun JsonNode.text(field: String): String? =
        path(field).takeIf { it.isTextual }?.asText()?.takeIf { it.isNotBlank() }
}
