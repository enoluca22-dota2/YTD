package com.enoluca.ytd.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Log
import com.enoluca.ytd.data.local.db.LibraryMediaEntity
import com.enoluca.ytd.data.local.db.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * One small JPEG per Library item (`files/artwork/<id>.jpg`, at most [MAX_SIZE_PX] on its longest
 * side), made once from the best source available: the cover embedded in the file, the download's
 * thumbnail, or a frame of the video. Lists, the player, the notification and the lock screen all
 * read this file, so nothing is decoded at full resolution or downloaded twice.
 */
class ArtworkCache(context: Context) {

    private val appContext = context.applicationContext
    private val dir = File(appContext.filesDir, "artwork")

    sealed interface Result {
        data class Saved(val path: String) : Result

        /** No source has artwork: don't look again (the UI shows the generated placeholder). */
        data object None : Result

        /** A thumbnail couldn't be fetched right now (offline…): try again on a later scan. */
        data object RetryLater : Result
    }

    suspend fun create(media: LibraryMediaEntity): Result = withContext(Dispatchers.IO) {
        var transientFailure = false
        val sources: List<() -> Bitmap?> = when (media.mediaType) {
            MediaType.AUDIO -> listOf(
                { embeddedPicture(media.uri) },
                { thumbnail(media.thumbnailUrl) { transientFailure = true } },
            )
            MediaType.VIDEO -> listOf(
                { thumbnail(media.thumbnailUrl) { transientFailure = true } },
                { videoFrame(media.uri, media.durationMs) },
            )
        }
        for (source in sources) {
            val bitmap = runCatching { source() }.getOrNull() ?: continue
            try {
                dir.mkdirs()
                val target = File(dir, "${media.id}.jpg")
                target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                return@withContext Result.Saved(target.absolutePath)
            } catch (e: IOException) {
                Log.w(TAG, "Couldn't save artwork for ${media.id}", e)
                return@withContext Result.RetryLater
            } finally {
                bitmap.recycle()
            }
        }
        if (transientFailure) Result.RetryLater else Result.None
    }

    fun delete(mediaId: Long) {
        File(dir, "$mediaId.jpg").delete()
    }

    private fun embeddedPicture(uri: String): Bitmap? = withRetriever(uri) { it.embeddedPicture?.let(::decodeScaled) }

    private fun videoFrame(uri: String, durationMs: Long?): Bitmap? = withRetriever(uri) { retriever ->
        // A frame a little into the video: the very first one is often black.
        val atUs = ((durationMs ?: 0L) / 10).coerceIn(0L, 30_000L) * 1000
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            retriever.getScaledFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, MAX_SIZE_PX, MAX_SIZE_PX)
        } else {
            retriever.getFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let(::scaleDown)
        }
    }

    private fun thumbnail(url: String?, onTransientFailure: () -> Unit): Bitmap? {
        if (url.isNullOrBlank() || !url.startsWith("https://")) return null
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            if (connection.responseCode !in 200..299) return null
            if (connection.contentLengthLong > MAX_DOWNLOAD_BYTES) return null
            val bytes = connection.inputStream.use { it.readBytes() }
            decodeScaled(bytes)
        } catch (e: IOException) {
            onTransientFailure()
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun <T> withRetriever(uri: String, block: (MediaMetadataRetriever) -> T?): T? {
        val retriever = MediaMetadataRetriever()
        return try {
            val parsed = Uri.parse(uri)
            if (parsed.scheme == "file") retriever.setDataSource(parsed.path) else retriever.setDataSource(appContext, parsed)
            block(retriever)
        } catch (e: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun decodeScaled(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIZE_PX) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        return scaleDown(decoded)
    }

    private fun scaleDown(bitmap: Bitmap): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= MAX_SIZE_PX) return bitmap
        val factor = MAX_SIZE_PX.toFloat() / longest
        val scaled = Bitmap.createScaledBitmap(bitmap, (bitmap.width * factor).toInt().coerceAtLeast(1), (bitmap.height * factor).toInt().coerceAtLeast(1), true)
        if (scaled != bitmap) bitmap.recycle()
        return scaled
    }

    private companion object {
        const val TAG = "ArtworkCache"
        const val MAX_SIZE_PX = 512
        const val MAX_DOWNLOAD_BYTES = 10L * 1024 * 1024
    }
}
