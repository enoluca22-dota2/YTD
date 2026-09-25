package com.enoluca.ytd.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.enoluca.ytd.core.UrlValidator
import com.enoluca.ytd.data.analyzer.AnalysisErrorInfo
import com.enoluca.ytd.data.analyzer.MediaAnalyzer
import com.enoluca.ytd.data.analyzer.toAnalysisError
import com.enoluca.ytd.data.local.db.HistoryEntity
import com.enoluca.ytd.data.model.AnalysisResult
import com.enoluca.ytd.data.model.DownloadCategory
import com.enoluca.ytd.data.model.MediaFormat
import com.enoluca.ytd.data.model.MediaInfo
import com.enoluca.ytd.data.model.PlaylistEntry
import com.enoluca.ytd.data.model.PlaylistInfo
import com.enoluca.ytd.data.model.QuickFormat
import com.enoluca.ytd.data.platform.DetectedPlatform
import com.enoluca.ytd.data.platform.PlaylistContext
import com.enoluca.ytd.data.provider.ProviderException
import com.enoluca.ytd.data.repository.DownloadRepository
import com.enoluca.ytd.data.repository.HistoryRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Where the analyzer is for the link(s) currently in the field. */
sealed interface DetectPhase {
    data object Idle : DetectPhase
    data class Analyzing(val detected: DetectedPlatform) : DetectPhase

    /** `watch?v=…&list=…`: the user picks "Current video" or "Playlist" before anything is fetched. */
    data class ChooseScope(val url: String, val detected: DetectedPlatform) : DetectPhase
    data class Detected(val result: AnalysisResult) : DetectPhase
    data class Error(val url: String, val detected: DetectedPlatform?, val info: AnalysisErrorInfo) : DetectPhase
    data class Batch(val items: List<BatchItem>) : DetectPhase
}

enum class BatchStatus { NEEDS_CHOICE, ANALYZING, READY, FAILED }

/** One link of a multi-URL paste/share, analyzed independently. */
data class BatchItem(
    val url: String,
    val detected: DetectedPlatform,
    val status: BatchStatus = BatchStatus.ANALYZING,
    val result: AnalysisResult? = null,
    val error: String? = null,
    val alreadyDownloaded: Boolean = false,
) {
    val title: String?
        get() = when (val r = result) {
            is AnalysisResult.Single -> r.media.title
            is AnalysisResult.Collection -> "${r.playlist.title} (${r.playlist.entries.size} items)"
            null -> null
        }
}

/** A media link found on the clipboard, offered once until the clipboard changes. */
data class ClipboardSuggestion(val url: String, val detected: DetectedPlatform)

data class HomeUiState(
    val urlText: String = "",
    val phase: DetectPhase = DetectPhase.Idle,
    val clipboardSuggestion: ClipboardSuggestion? = null,
) {
    val urls: List<String> get() = UrlValidator.extractAllUrls(urlText)

    /** Live, network-free platform detection for the field (shown before analysis). */
    val detectedPreview: DetectedPlatform? get() = urls.singleOrNull()?.let(com.enoluca.ytd.data.platform.Platforms::detect)

    /** Shown under the field only once the user typed something that isn't a usable link. */
    val urlHint: String?
        get() = if (urlText.isNotBlank() && urls.isEmpty()) "Enter a full link starting with https://" else null

    val canAnalyze: Boolean
        get() = phase !is DetectPhase.Analyzing && phase !is DetectPhase.ChooseScope && urls.isNotEmpty() &&
            (phase !is DetectPhase.Batch || phase.items.none { it.status == BatchStatus.ANALYZING })
}

class HomeViewModel(
    private val analyzer: MediaAnalyzer,
    private val downloadRepository: DownloadRepository,
    private val historyRepository: HistoryRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private var analyzeJob: Job? = null

    /** Clipboard dedupe: a clip is offered at most once (by timestamp), and an ignored URL never again. */
    private var lastClipTimestamp = -1L
    private val ignoredClipboardUrls = mutableSetOf<String>()

    fun onUrlChanged(text: String) {
        _state.update {
            // Editing the link dismisses a previous result/error.
            val keepPhase = it.phase is DetectPhase.Analyzing
            it.copy(urlText = text, phase = if (keepPhase) it.phase else DetectPhase.Idle)
        }
    }

    /** Pasted/shared text often contains more than the link ("Check this out: https://…"). */
    fun onTextPasted(text: String) {
        val urls = UrlValidator.extractAllUrls(text)
        onUrlChanged(if (urls.isEmpty()) text.trim() else urls.joinToString("\n"))
    }

    /** Share → app: the shared text goes straight into the analyzer and is analyzed automatically. */
    fun consumeSharedText(text: String) {
        val urls = UrlValidator.extractAllUrls(text)
        if (urls.isEmpty()) {
            analyzeJob?.cancel()
            val info = AnalysisErrorInfo(
                "No link found",
                "Nothing that was shared contains a YouTube or TikTok link.",
                emptySet(),
            )
            _state.update { it.copy(phase = DetectPhase.Error(text, null, info)) }
            return
        }
        analyzeJob?.cancel()
        _state.update { it.copy(urlText = urls.joinToString("\n"), phase = DetectPhase.Idle, clipboardSuggestion = null) }
        analyze()
    }

    fun analyze() {
        val current = _state.value
        if (current.phase is DetectPhase.Analyzing) return
        val urls = current.urls
        when {
            urls.isEmpty() -> _state.update {
                it.copy(phase = DetectPhase.Error(current.urlText, null, ProviderException.InvalidUrl().toAnalysisError(null)))
            }
            urls.size == 1 -> analyzeSingle(urls.single())
            else -> analyzeBatch(urls)
        }
    }

    private fun analyzeSingle(url: String) {
        val detected = analyzer.detect(url)
        if (detected.needsPlaylistChoice) {
            // Never silently collapse video+playlist into one of them: ask first.
            _state.update { it.copy(urlText = url, phase = DetectPhase.ChooseScope(url, detected)) }
            return
        }
        runAnalysis(url, url, detected, focus = null)
    }

    /** "Current video": the original link (all its parameters intact); yt-dlp runs with --no-playlist. */
    fun chooseCurrentVideo() {
        val choice = _state.value.phase as? DetectPhase.ChooseScope ?: return
        runAnalysis(choice.url, choice.url, choice.detected, focus = null)
    }

    /** "Playlist": the playlist the link was opened from, with that video highlighted. */
    fun choosePlaylist() {
        val choice = _state.value.phase as? DetectPhase.ChooseScope ?: return
        val context = choice.detected.playlistContext ?: return
        runAnalysis(choice.url, context.playlistUrl, analyzer.detect(context.playlistUrl), focus = context)
    }

    private fun runAnalysis(fieldUrl: String, url: String, detected: DetectedPlatform, focus: PlaylistContext?) {
        analyzeJob?.cancel()
        _state.update { it.copy(urlText = fieldUrl, phase = DetectPhase.Analyzing(detected)) }
        analyzeJob = viewModelScope.launch {
            analyzer.analyze(url).fold(
                onSuccess = { result ->
                    val withFocus = if (result is AnalysisResult.Collection && focus != null) result.copy(focus = focus) else result
                    _state.update { it.copy(phase = DetectPhase.Detected(withFocus)) }
                },
                onFailure = { e ->
                    if (e !is ProviderException.Cancelled) {
                        _state.update { it.copy(phase = DetectPhase.Error(url, detected, e.toAnalysisError(detected))) }
                    }
                },
            )
        }
    }

    /** Several links: each analyzed on its own (2 at a time), results shown as a list. */
    private fun analyzeBatch(urls: List<String>) {
        val items = urls.map { url ->
            val detected = analyzer.detect(url)
            BatchItem(url, detected, if (detected.needsPlaylistChoice) BatchStatus.NEEDS_CHOICE else BatchStatus.ANALYZING)
        }
        _state.update { it.copy(phase = DetectPhase.Batch(items)) }
        analyzeJob = viewModelScope.launch {
            analyzeBatchItems(items.filter { it.status == BatchStatus.ANALYZING }.map { it.url to it.url })
        }
    }

    /** Batch item opened from a playlist: analyze either that video or its playlist, as the user chose. */
    fun chooseBatchScope(url: String, wholePlaylist: Boolean) {
        val batch = _state.value.phase as? DetectPhase.Batch ?: return
        val item = batch.items.firstOrNull { it.url == url && it.status == BatchStatus.NEEDS_CHOICE } ?: return
        val target = if (wholePlaylist) item.detected.playlistContext?.playlistUrl ?: url else url
        _state.update { it.copy(phase = DetectPhase.Batch(batch.items.map { if (it.url == url) it.copy(status = BatchStatus.ANALYZING) else it })) }
        viewModelScope.launch { analyzeBatchItems(listOf(url to target)) }
    }

    /** Pairs of (row url, url to analyze). */
    private suspend fun analyzeBatchItems(targets: List<Pair<String, String>>) {
        coroutineScope {
            val permits = Semaphore(2)
            targets.map { (url, target) ->
                async {
                    permits.withPermit {
                        val result = analyzer.analyze(target)
                        val updated = result.fold(
                            onSuccess = { r ->
                                val existing = (r as? AnalysisResult.Single)?.let { findExisting(it.media) }
                                BatchItem(
                                    url = url,
                                    detected = analyzer.detect(url),
                                    status = BatchStatus.READY,
                                    result = r,
                                    alreadyDownloaded = existing != null,
                                )
                            },
                            onFailure = { e ->
                                val detected = analyzer.detect(url)
                                BatchItem(url, detected, BatchStatus.FAILED, error = e.toAnalysisError(detected).title)
                            },
                        )
                        _state.update { s ->
                            val batch = s.phase as? DetectPhase.Batch ?: return@update s
                            s.copy(phase = DetectPhase.Batch(batch.items.map { if (it.url == url) updated else it }))
                        }
                    }
                }
            }.awaitAll()
        }
    }

    /** Queues every READY item of a batch (skipping ones already downloaded) with one quality choice. */
    suspend fun downloadAllReady(choice: QuickFormat, includeAlreadyDownloaded: Boolean): Int {
        val batch = _state.value.phase as? DetectPhase.Batch ?: return 0
        var queued = 0
        batch.items.filter { it.status == BatchStatus.READY && (includeAlreadyDownloaded || !it.alreadyDownloaded) }.forEach { item ->
            when (val r = item.result) {
                is AnalysisResult.Single -> {
                    downloadRepository.enqueueQuick(r.media.webpageUrl, r.media.title, r.media.thumbnailUrl, choice)
                    queued++
                }
                is AnalysisResult.Collection -> queued += enqueueEntries(r.playlist, r.playlist.entries.filter { it.isAvailable }, choice)
                else -> Unit
            }
        }
        return queued
    }

    /** Playlist: queue the selected entries through the normal download queue, one job per item. */
    suspend fun enqueueEntries(playlist: PlaylistInfo, entries: List<PlaylistEntry>, choice: QuickFormat): Int =
        downloadRepository.enqueuePlaylist(playlist.sourceUrl, playlist.id, playlist.title, entries, choice)

    fun cancelAnalyze() {
        analyzeJob?.cancel()
        analyzeJob = null
        _state.update { it.copy(phase = DetectPhase.Idle) }
    }

    /** Leaves a result screen. The link stays in the field unless the download was submitted. */
    fun resetDetection(clearUrl: Boolean) {
        _state.update { it.copy(phase = DetectPhase.Idle, urlText = if (clearUrl) "" else it.urlText) }
    }

    fun dismissBatch() = resetDetection(clearUrl = true)

    // --- Clipboard detection -----------------------------------------------------------------

    /**
     * Called when the clipboard may have changed (app resumed). [clipTimestamp] identifies the
     * clip, so the same copy is inspected once; only recognized media links are offered.
     */
    fun onClipboardText(clipTimestamp: Long, text: String) {
        lastClipTimestamp = clipTimestamp
        val url = UrlValidator.extractAllUrls(text).firstOrNull() ?: return
        if (url in ignoredClipboardUrls || _state.value.urls.contains(url)) return
        val detected = analyzer.detect(url)
        // Only supported YouTube/TikTok video links.
        if (!detected.isSupported) return
        _state.update { it.copy(clipboardSuggestion = ClipboardSuggestion(url, detected)) }
    }

    fun isNewClip(clipTimestamp: Long): Boolean = clipTimestamp != lastClipTimestamp

    fun acceptClipboardSuggestion() {
        val suggestion = _state.value.clipboardSuggestion ?: return
        ignoredClipboardUrls += suggestion.url // don't offer it again after use
        _state.update { it.copy(clipboardSuggestion = null) }
        consumeSharedText(suggestion.url)
    }

    fun ignoreClipboardSuggestion() {
        val suggestion = _state.value.clipboardSuggestion ?: return
        ignoredClipboardUrls += suggestion.url
        _state.update { it.copy(clipboardSuggestion = null) }
    }

    // --- Downloads ---------------------------------------------------------------------------

    suspend fun findExisting(media: MediaInfo): HistoryEntity? =
        historyRepository.findExistingDownload(media.webpageUrl, media.sourceUrl)

    suspend fun existingUrls(urls: List<String>): Set<String> =
        urls.filter { historyRepository.findExistingDownload(it) != null }.toSet()

    suspend fun submitDownload(
        media: MediaInfo,
        format: MediaFormat,
        category: DownloadCategory,
        fileBaseName: String,
        startNow: Boolean,
    ): Long = downloadRepository.enqueue(media, format, category, fileBaseName, startNow)
}
