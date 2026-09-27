package com.enoluca.ytd.library

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import java.io.File

/**
 * Read-only facts about media files. Every call does I/O: use it off the main thread. Nothing
 * here throws — a file that can't be read is simply reported as missing / without metadata.
 */
object MediaFiles {

    private const val TAG = "MediaFiles"

    /**
     * True if [uriString] still points at a readable file. A MediaStore row must also still be
     * listed (not trashed or pending) — deleting through the gallery/file manager moves files to
     * the trash first, and those shouldn't keep playing from the Library.
     */
    fun exists(context: Context, uriString: String): Boolean {
        val uri = Uri.parse(uriString)
        return try {
            when (uri.scheme) {
                "file" -> uri.path?.let { File(it).isFile } == true
                else -> {
                    if (uri.authority == MediaStore.AUTHORITY) {
                        val listed = context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)
                            ?.use { it.moveToFirst() } ?: false
                        if (!listed) return false
                    }
                    context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
                }
            }
        } catch (e: Exception) {
            // FileNotFoundException (deleted), SecurityException (access revoked), IllegalArgument…
            false
        }
    }

    data class NameAndSize(val displayName: String?, val sizeBytes: Long?)

    fun nameAndSize(context: Context, uriString: String): NameAndSize {
        val uri = Uri.parse(uriString)
        if (uri.scheme == "file") {
            val file = File(uri.path.orEmpty())
            return NameAndSize(file.name, file.length().takeIf { it > 0 })
        }
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (!c.moveToFirst()) return@use NameAndSize(null, null)
                val name = c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { c.getString(it) }
                val size = c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { c.getLong(it) }
                NameAndSize(name, size?.takeIf { it > 0 })
            } ?: NameAndSize(null, null)
        } catch (e: Exception) {
            NameAndSize(null, null)
        }
    }

    /** What the container itself says about the media. */
    data class Probe(
        val durationMs: Long?,
        val title: String?,
        val artist: String?,
        val album: String?,
        val mimeType: String?,
        val hasVideo: Boolean,
    )

    fun probe(context: Context, uriString: String): Probe? {
        val retriever = MediaMetadataRetriever()
        return try {
            val uri = Uri.parse(uriString)
            if (uri.scheme == "file") retriever.setDataSource(uri.path) else retriever.setDataSource(context, uri)
            fun key(k: Int) = retriever.extractMetadata(k)?.trim()?.takeIf { it.isNotEmpty() }
            Probe(
                durationMs = key(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf { it > 0 },
                title = key(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = key(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: key(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                album = key(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                mimeType = key(MediaMetadataRetriever.METADATA_KEY_MIMETYPE),
                hasVideo = key(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes",
            )
        } catch (e: Exception) {
            Log.i(TAG, "No metadata for $uriString: ${e.javaClass.simpleName}")
            null
        } finally {
            runCatching { retriever.release() }
        }
    }
}
