package com.enoluca.ytd.library

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import com.enoluca.ytd.data.local.datastore.SettingsDataStore
import com.enoluca.ytd.data.local.db.AppDatabase
import com.enoluca.ytd.data.local.db.DownloadEntity
import com.enoluca.ytd.data.local.db.HistoryEntity
import com.enoluca.ytd.data.local.db.LibraryMediaEntity
import com.enoluca.ytd.data.local.db.MediaOrigin
import com.enoluca.ytd.data.local.db.MediaType
import com.enoluca.ytd.data.model.DownloadCategory
import com.enoluca.ytd.data.model.DownloadStatus
import com.enoluca.ytd.data.platform.Platforms
import com.enoluca.ytd.data.provider.StoragePublisher
import com.enoluca.ytd.download.CompletedDownload
import com.enoluca.ytd.download.DownloadCompletionListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Keeps the Library in sync with what is really on the device, always in the background:
 *
 *  - A download completes → the file is indexed immediately (metadata, playlist link, artwork).
 *  - A scan (app start, Settings → Rescan, MediaStore changes) imports earlier downloads, the
 *    ENAGELYUCA folders in MediaStore (all music/videos if the user opted in and granted access)
 *    and the user's Library folders, then checks every entry's file still exists.
 *
 * Only MediaStore and folders the user picked are read — never a blind walk of the storage.
 */
class LibraryIndexer(
    private val context: Context,
    private val db: AppDatabase,
    private val repository: LibraryRepository,
    private val artwork: ArtworkCache,
    private val settings: SettingsDataStore,
    private val scope: CoroutineScope,
) : DownloadCompletionListener {

    data class ScanResult(val added: Int, val missing: Int, val restored: Int, val removed: Int, val total: Int)

    data class ScanState(val running: Boolean = false, val lastScanAt: Long? = null, val lastResult: ScanResult? = null)

    private val dao = db.libraryDao()
    private val _state = MutableStateFlow(ScanState())
    val state: StateFlow<ScanState> = _state.asStateFlow()

    /** One scan at a time. */
    private val scanMutex = Mutex()

    /** Guards "is this URI already indexed? → insert", shared by scans and completed downloads. */
    private val insertMutex = Mutex()
    private var observerJob: Job? = null
    private var lastObserverScan = 0L
    private var started = false

    /** App start: a quick scan shortly after launch, then follow MediaStore changes. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            delay(STARTUP_DELAY_MS)
            runCatching { rescan(includeFolders = true) }.onFailure { Log.w(TAG, "Start-up scan failed", it) }
        }
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = scheduleObserverScan()
        }
        runCatching {
            context.contentResolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observer)
            context.contentResolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer)
        }.onFailure { Log.w(TAG, "Can't observe MediaStore", it) }
    }

    /** Files deleted/added elsewhere: re-check soon, at most every [OBSERVER_MIN_INTERVAL_MS]. */
    private fun scheduleObserverScan() {
        if (observerJob?.isActive == true) return
        observerJob = scope.launch {
            val wait = (lastObserverScan + OBSERVER_MIN_INTERVAL_MS - System.currentTimeMillis()).coerceAtLeast(OBSERVER_DEBOUNCE_MS)
            delay(wait)
            lastObserverScan = System.currentTimeMillis()
            runCatching { rescan(includeFolders = false) }.onFailure { Log.w(TAG, "Scan after MediaStore change failed", it) }
        }
    }

    /** Settings → "Rescan library". */
    fun requestRescan(): Job = scope.launch {
        runCatching { rescan(includeFolders = true) }.onFailure { Log.w(TAG, "Rescan failed", it) }
    }

    // --- Downloads ----------------------------------------------------------------------------

    override suspend fun onDownloadCompleted(download: CompletedDownload) {
        val entity = download.entity
        val type = typeForDownload(entity.category, download.mimeType, "${entity.fileBaseName}.${download.extension}") ?: return
        val mediaId = withContext(Dispatchers.IO) {
            val probe = MediaFiles.probe(context, download.fileUri)
            val name = MediaFiles.nameAndSize(context, download.fileUri)
            val now = System.currentTimeMillis()
            upsertDownload(
                LibraryMediaEntity(
                    uri = download.fileUri,
                    fileName = name.displayName ?: "${entity.fileBaseName}.${download.extension}",
                    title = entity.title,
                    artist = entity.uploader ?: probe?.artist,
                    album = probe?.album,
                    thumbnailUrl = entity.thumbnailUrl,
                    mediaType = type,
                    mimeType = download.mimeType,
                    durationMs = probe?.durationMs,
                    sizeBytes = download.sizeBytes ?: name.sizeBytes,
                    dateAdded = now,
                    dateModified = now,
                    sourceUrl = entity.webpageUrl.ifBlank { entity.sourceUrl },
                    sourcePlatform = platformOf(entity.webpageUrl.ifBlank { entity.sourceUrl }),
                    downloadId = entity.id,
                    origin = MediaOrigin.DOWNLOAD,
                    location = download.location,
                )
            )
        }
        linkToSourcePlaylist(entity, mediaId)
        // Artwork is a nicety: a failure here (storage, a corrupt file) must never crash the app;
        // the item keeps its placeholder and the next backfill tries again.
        scope.launch {
            try {
                ensureArtwork(mediaId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Artwork for $mediaId failed", e)
            }
        }
    }

    /** A download from a source playlist joins that Library playlist at its source position. */
    private suspend fun linkToSourcePlaylist(entity: DownloadEntity, mediaId: Long) {
        val batchId = entity.batchId ?: return
        val playlistId = repository.playlistForBatch(batchId) ?: return
        repository.addFromSource(playlistId, mediaId, entity.playlistSourceIndex ?: entity.batchIndex)
    }

    /** Inserts, or upgrades an entry a scan found first (it then gains the download's info). */
    private suspend fun upsertDownload(item: LibraryMediaEntity): Long = insertMutex.withLock {
        val existing = dao.getMediaByUri(item.uri)
        if (existing == null) {
            dao.insertMedia(item)
        } else {
            dao.updateMedia(
                existing.copy(
                    title = item.title,
                    artist = item.artist ?: existing.artist,
                    album = item.album ?: existing.album,
                    thumbnailUrl = item.thumbnailUrl ?: existing.thumbnailUrl,
                    durationMs = item.durationMs ?: existing.durationMs,
                    sizeBytes = item.sizeBytes ?: existing.sizeBytes,
                    sourceUrl = item.sourceUrl,
                    sourcePlatform = item.sourcePlatform,
                    downloadId = item.downloadId,
                    origin = MediaOrigin.DOWNLOAD,
                    location = item.location ?: existing.location,
                    missingSince = null,
                )
            )
            existing.id
        }
    }

    // --- Scanning -----------------------------------------------------------------------------

    /**
     * Full reconciliation. [includeFolders] also walks the user's Library folders (slower, so
     * MediaStore change notifications skip it).
     */
    suspend fun rescan(includeFolders: Boolean = true): ScanResult = scanMutex.withLock {
        _state.update { it.copy(running = true) }
        try {
            val result = withContext(Dispatchers.IO) { scan(includeFolders) }
            _state.value = ScanState(running = false, lastScanAt = System.currentTimeMillis(), lastResult = result)
            scope.launch { backfillArtwork() }
            result
        } catch (e: Exception) {
            _state.update { it.copy(running = false) }
            throw e
        }
    }

    private suspend fun scan(includeFolders: Boolean): ScanResult {
        val settingsNow = settings.settings.first()
        var added = 0
        val known = dao.getAllUris().toHashSet()

        // 1. Everything ENAGELYUCA downloaded before (also before the Library existed).
        added += importPastDownloads(known)

        // 2. MediaStore: the app's own folders, or all music/videos when the user allowed it.
        val deviceScope = settingsNow.includeDeviceMedia && hasDeviceMediaPermission(context)
        val seenInMediaStore = HashSet<String>()
        val mediaStoreOk = runCatching {
            for (collection in MediaStoreCollection.entries) {
                queryMediaStore(collection, deviceScope) { item ->
                    seenInMediaStore += item.uri
                    if (item.uri !in known && insertIfNew(item)) {
                        known += item.uri
                        added++
                    }
                }
            }
        }.onFailure { Log.w(TAG, "MediaStore scan failed", it) }.isSuccess

        // 3. The custom download folder and the user's Library folders (SAF).
        val folders = (listOfNotNull(settingsNow.customDownloadTreeUri) + settingsNow.libraryFolders).distinct()
        val seenInFolders = HashSet<String>()
        var foldersOk = includeFolders
        if (includeFolders) {
            for (tree in folders) {
                val ok = runCatching {
                    walkTree(Uri.parse(tree)) { item ->
                        seenInFolders += item.uri
                        if (item.uri !in known) {
                            val probed = withProbe(item)
                            if (insertIfNew(probed)) {
                                known += item.uri
                                added++
                            }
                        }
                    }
                }.onFailure { Log.w(TAG, "Couldn't read Library folder $tree", it) }.isSuccess
                foldersOk = foldersOk && ok
            }
        }

        // 4. Does every entry's file still exist? Out-of-scope device media is forgotten (not deleted).
        var missing = 0
        var restored = 0
        val forget = mutableListOf<Long>()
        val now = System.currentTimeMillis()
        for (item in dao.getAllMedia()) {
            val outOfScope = when (item.origin) {
                MediaOrigin.DEVICE -> mediaStoreOk && item.uri !in seenInMediaStore
                MediaOrigin.FOLDER -> foldersOk && item.uri !in seenInFolders && item.uri !in seenInMediaStore
                MediaOrigin.DOWNLOAD -> false
            }
            val exists = MediaFiles.exists(context, item.uri)
            when {
                exists && outOfScope -> forget += item.id
                exists && item.missingSince != null -> {
                    dao.markPresent(item.id)
                    restored++
                }
                !exists && item.missingSince == null -> {
                    dao.markMissing(item.id, now)
                    missing++
                }
                !exists && now - item.missingSince!! > FORGET_MISSING_AFTER_MS -> forget += item.id
                !exists -> missing++
            }
        }
        repository.forget(forget)
        return ScanResult(added = added, missing = missing, restored = restored, removed = forget.size, total = dao.getAllUris().size)
    }

    /** Completed downloads (queue rows first — they know more — then History) not in the Library yet. */
    private suspend fun importPastDownloads(known: MutableSet<String>): Int {
        var added = 0
        val rows = db.downloadDao().getByStatuses(listOf(DownloadStatus.COMPLETED)).filter { it.fileUri != null }
        for (row in rows) {
            val uri = row.fileUri ?: continue
            if (uri in known || !MediaFiles.exists(context, uri)) continue
            val extension = row.container.orEmpty()
            val type = typeForDownload(row.category, null, "${row.fileBaseName}.$extension") ?: continue
            val probe = MediaFiles.probe(context, uri)
            val name = MediaFiles.nameAndSize(context, uri)
            val item = LibraryMediaEntity(
                uri = uri,
                fileName = name.displayName ?: "${row.fileBaseName}.$extension",
                title = row.title,
                artist = row.uploader ?: probe?.artist,
                album = probe?.album,
                thumbnailUrl = row.thumbnailUrl,
                mediaType = type,
                mimeType = probe?.mimeType,
                durationMs = probe?.durationMs,
                sizeBytes = name.sizeBytes ?: row.totalBytes,
                dateAdded = row.completedAt ?: row.updatedAt,
                dateModified = row.completedAt,
                sourceUrl = row.webpageUrl.ifBlank { row.sourceUrl },
                sourcePlatform = platformOf(row.webpageUrl.ifBlank { row.sourceUrl }),
                downloadId = row.id,
                origin = MediaOrigin.DOWNLOAD,
                location = null,
            )
            if (insertIfNew(item)) {
                known += uri
                added++
                dao.getMediaByUri(uri)?.let { linkToSourcePlaylist(row, it.id) }
            }
        }
        for (entry in db.historyDao().getCompletedWithFile()) {
            val uri = entry.fileUri ?: continue
            if (uri in known || !MediaFiles.exists(context, uri)) continue
            if (insertIfNew(fromHistory(entry, uri) ?: continue)) {
                known += uri
                added++
            }
        }
        return added
    }

    private fun fromHistory(entry: HistoryEntity, uri: String): LibraryMediaEntity? {
        val type = typeForDownload(entry.category, null, entry.filename) ?: return null
        val probe = MediaFiles.probe(context, uri)
        val name = MediaFiles.nameAndSize(context, uri)
        return LibraryMediaEntity(
            uri = uri,
            fileName = name.displayName ?: entry.filename,
            title = entry.title,
            artist = probe?.artist,
            album = probe?.album,
            thumbnailUrl = entry.thumbnailUrl,
            mediaType = type,
            mimeType = probe?.mimeType,
            durationMs = probe?.durationMs,
            sizeBytes = name.sizeBytes ?: entry.fileSizeBytes,
            dateAdded = entry.completedAt,
            dateModified = entry.completedAt,
            sourceUrl = entry.webpageUrl.ifBlank { entry.sourceUrl },
            sourcePlatform = platformOf(entry.webpageUrl.ifBlank { entry.sourceUrl }),
            downloadId = entry.downloadId,
            origin = MediaOrigin.DOWNLOAD,
            location = entry.location,
        )
    }

    private suspend fun insertIfNew(item: LibraryMediaEntity): Boolean = insertMutex.withLock {
        if (dao.getMediaByUri(item.uri) != null) return@withLock false
        dao.insertMedia(item)
        true
    }

    private enum class MediaStoreCollection(val uri: Uri, val type: MediaType) {
        AUDIO(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, MediaType.AUDIO),
        VIDEO(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, MediaType.VIDEO),
    }

    /** ENAGELYUCA's folders (and the pre-rename "YTD" ones) under Music, Movies and Download. */
    private val ownFolders: List<String> = listOf(StoragePublisher.FOLDER, LEGACY_FOLDER).flatMap { folder ->
        listOf("Music/$folder/", "Movies/$folder/", "Download/$folder/")
    }

    private suspend fun queryMediaStore(collection: MediaStoreCollection, deviceScope: Boolean, onItem: suspend (LibraryMediaEntity) -> Unit) {
        val modern = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val pathColumn = if (modern) MediaStore.MediaColumns.RELATIVE_PATH else @Suppress("DEPRECATION") MediaStore.MediaColumns.DATA
        val folderClause = ownFolders.joinToString(" OR ") { "$pathColumn LIKE ?" }
        val folderArgs = ownFolders.map { if (modern) "$it%" else "%/$it%" }
        val (selection, args) = when {
            !deviceScope -> "($folderClause)" to folderArgs
            // Songs, not ringtones/notification sounds; plus anything in our folders.
            collection == MediaStoreCollection.AUDIO -> "(${MediaStore.Audio.Media.IS_MUSIC} != 0 OR $folderClause)" to folderArgs
            else -> null to emptyList()
        }
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.TITLE,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DATE_MODIFIED,
            if (collection == MediaStoreCollection.AUDIO) MediaStore.Audio.AudioColumns.DURATION else MediaStore.Video.VideoColumns.DURATION,
            if (collection == MediaStoreCollection.AUDIO) MediaStore.Audio.AudioColumns.ARTIST else MediaStore.Video.VideoColumns.ARTIST,
            if (collection == MediaStoreCollection.AUDIO) MediaStore.Audio.AudioColumns.ALBUM else MediaStore.Video.VideoColumns.ALBUM,
            pathColumn,
        )
        context.contentResolver.query(collection.uri, projection, selection, args.toTypedArray(), null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val name = c.stringAt(1)
                val mime = c.stringAt(3)
                // Our folders can hold other file types; only playable media belongs in the Library.
                val type = MediaKinds.typeOf(mime, name) ?: collection.type
                val path = c.stringAt(10)
                onItem(
                    LibraryMediaEntity(
                        uri = ContentUris.withAppendedId(collection.uri, id).toString(),
                        fileName = name,
                        title = c.stringAt(2)?.takeIf { it.isNotBlank() } ?: name?.substringBeforeLast('.') ?: "Untitled",
                        artist = c.stringAt(8)?.takeUnless { it == MediaStore.UNKNOWN_STRING },
                        album = c.stringAt(9)?.takeUnless { it == MediaStore.UNKNOWN_STRING },
                        thumbnailUrl = null,
                        mediaType = type,
                        mimeType = mime,
                        durationMs = c.longAt(7)?.takeIf { it > 0 },
                        sizeBytes = c.longAt(4)?.takeIf { it > 0 },
                        dateAdded = (c.longAt(5) ?: 0L) * 1000,
                        dateModified = c.longAt(6)?.let { it * 1000 },
                        sourceUrl = null,
                        sourcePlatform = null,
                        downloadId = null,
                        origin = MediaOrigin.DEVICE,
                        location = path?.let { if (modern) it.trimEnd('/') else it.substringBeforeLast('/').substringAfter("/0/") },
                    )
                )
            }
        }
    }

    /** Media files under a folder the user granted (a few levels deep). */
    private suspend fun walkTree(treeUri: Uri, onItem: suspend (LibraryMediaEntity) -> Unit) {
        val resolver = context.contentResolver
        val folderName = runCatching { androidx.documentfile.provider.DocumentFile.fromTreeUri(context, treeUri)?.name }.getOrNull()
        val pending = ArrayDeque<Pair<String, Int>>()
        pending += DocumentsContract.getTreeDocumentId(treeUri) to 0
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        while (pending.isNotEmpty()) {
            val (parentId, depth) = pending.removeFirst()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
            resolver.query(children, projection, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val docId = c.stringAt(0) ?: continue
                    val name = c.stringAt(1)
                    val mime = c.stringAt(2)
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (depth < MAX_FOLDER_DEPTH) pending += docId to depth + 1
                        continue
                    }
                    val type = MediaKinds.typeOf(mime, name) ?: continue
                    val modified = c.longAt(4)?.takeIf { it > 0 }
                    onItem(
                        LibraryMediaEntity(
                            uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId).toString(),
                            fileName = name,
                            title = name?.substringBeforeLast('.') ?: "Untitled",
                            artist = null,
                            album = null,
                            thumbnailUrl = null,
                            mediaType = type,
                            mimeType = mime,
                            durationMs = null,
                            sizeBytes = c.longAt(3)?.takeIf { it > 0 },
                            dateAdded = modified ?: System.currentTimeMillis(),
                            dateModified = modified,
                            sourceUrl = null,
                            sourcePlatform = null,
                            downloadId = null,
                            origin = MediaOrigin.FOLDER,
                            location = folderName,
                        )
                    )
                }
            }
        }
    }

    /** Folder files have no MediaStore metadata: read tags/duration from the file itself. */
    private fun withProbe(item: LibraryMediaEntity): LibraryMediaEntity {
        val probe = MediaFiles.probe(context, item.uri) ?: return item
        return item.copy(
            title = probe.title ?: item.title,
            artist = probe.artist,
            album = probe.album,
            durationMs = probe.durationMs,
            mediaType = if (probe.hasVideo) MediaType.VIDEO else item.mediaType,
        )
    }

    // --- Artwork ------------------------------------------------------------------------------

    private val artworkMutex = Mutex()

    private suspend fun ensureArtwork(mediaId: Long) = artworkMutex.withLock {
        val item = dao.getMedia(mediaId) ?: return@withLock
        if (item.artworkPath != null) return@withLock
        when (val result = artwork.create(item)) {
            is ArtworkCache.Result.Saved -> dao.setArtworkPath(mediaId, result.path)
            ArtworkCache.Result.None -> dao.setArtworkPath(mediaId, "")
            ArtworkCache.Result.RetryLater -> Unit
        }
    }

    /** Artwork for entries that don't have any yet, one at a time, after a scan. */
    private suspend fun backfillArtwork() {
        try {
            dao.getMediaWithoutArtwork().forEach { ensureArtwork(it.id) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Artwork backfill stopped", e)
        }
    }

    // --- Helpers ------------------------------------------------------------------------------

    private fun typeForDownload(category: DownloadCategory, mimeType: String?, fileName: String): MediaType? = when (category) {
        DownloadCategory.MUSIC -> MediaType.AUDIO
        DownloadCategory.VIDEO -> MediaKinds.typeOf(mimeType, fileName) ?: MediaType.VIDEO
        else -> MediaKinds.typeOf(mimeType, fileName)
    }

    private fun platformOf(url: String): String? = Platforms.detect(url).spec?.displayName

    private fun Cursor.stringAt(index: Int): String? = if (isNull(index)) null else getString(index)
    private fun Cursor.longAt(index: Int): Long? = if (isNull(index)) null else getLong(index)

    companion object {
        private const val TAG = "LibraryIndexer"
        private const val STARTUP_DELAY_MS = 3_000L
        private const val OBSERVER_DEBOUNCE_MS = 2_000L
        private const val OBSERVER_MIN_INTERVAL_MS = 20_000L
        private const val MAX_FOLDER_DEPTH = 5

        /** A missing file is shown as unavailable; after this long the Library forgets it. */
        const val FORGET_MISSING_AFTER_MS = 30L * 24 * 60 * 60 * 1000

        /** Folder name used before the app was renamed ENAGELYUCA. */
        private const val LEGACY_FOLDER = "YTD"

        /** Permissions needed to include music/videos from outside ENAGELYUCA's folders. */
        val deviceMediaPermissions: Array<String>
            get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_MEDIA_VIDEO)
            } else {
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            }

        fun hasDeviceMediaPermission(context: Context): Boolean =
            deviceMediaPermissions.any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    }
}
