package com.enoluca.ytd.ui.home

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.enoluca.ytd.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.enoluca.ytd.core.FileActions
import com.enoluca.ytd.core.Formatting
import com.enoluca.ytd.core.UrlValidator
import com.enoluca.ytd.data.analyzer.ErrorAction
import com.enoluca.ytd.data.local.db.DownloadEntity
import com.enoluca.ytd.data.local.db.HistoryEntity
import com.enoluca.ytd.download.ProgressDisplay
import com.enoluca.ytd.data.model.DownloadStatus
import com.enoluca.ytd.data.model.QuickFormat
import com.enoluca.ytd.data.platform.Platforms
import com.enoluca.ytd.ui.components.PlatformBadge
import com.enoluca.ytd.ui.components.ThumbnailImage
import com.enoluca.ytd.ui.glass.ActionState
import com.enoluca.ytd.ui.glass.GlassChip
import com.enoluca.ytd.ui.glass.GlassProgressBar
import com.enoluca.ytd.ui.glass.GlassSnackbarHost
import com.enoluca.ytd.ui.glass.GlassStyle
import com.enoluca.ytd.ui.glass.GlassSurface
import com.enoluca.ytd.ui.glass.PrimaryActionButton
import com.enoluca.ytd.ui.glass.SkeletonRow
import com.enoluca.ytd.ui.glass.ToastKind
import com.enoluca.ytd.ui.glass.showToast
import com.enoluca.ytd.ui.theme.GalacticCosmicViolet
import com.enoluca.ytd.ui.theme.GalacticDeepSpace
import com.enoluca.ytd.ui.theme.GalacticElectricCyan
import com.enoluca.ytd.ui.theme.LocalResolvedTheme
import com.enoluca.ytd.ui.theme.VisualStyle
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    contentPadding: PaddingValues,
    downloads: List<DownloadEntity>,
    recent: List<HistoryEntity>,
    clipboardDetection: Boolean,
    onOpenDownloads: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val toasts = remember { SnackbarHostState() }

    // Clipboard: the description is always safe to check (no content read, no system "pasted"
    // toast). With clipboard detection on, a *new* clip (by timestamp) is read once and offered
    // only if it's a recognized media link; the same copy never triggers twice.
    var clipboardHasText by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val manager = context.getSystemService(ClipboardManager::class.java)
        val description = manager?.primaryClipDescription
        clipboardHasText = description?.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) == true
        if (clipboardDetection && clipboardHasText && description != null && viewModel.isNewClip(description.timestamp)) {
            val text = runCatching { manager.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() }.getOrNull()
            if (!text.isNullOrBlank()) viewModel.onClipboardText(description.timestamp, text)
        }
    }
    val paste: () -> Unit = {
        scope.launch {
            val text = clipboard.getClipEntry()?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
            val count = text?.let { UrlValidator.extractAllUrls(it).size } ?: 0
            when {
                text.isNullOrBlank() -> toasts.showToast("The clipboard is empty.", ToastKind.Error)
                count == 0 -> {
                    viewModel.onTextPasted(text)
                    toasts.showToast("No link found in what you copied.", ToastKind.Error)
                }
                else -> {
                    viewModel.onTextPasted(text)
                    toasts.showToast(if (count > 1) "$count links pasted" else "Link detected")
                }
            }
        }
    }
    val copyUrl: (String) -> Unit = { url ->
        scope.launch {
            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("URL", url)))
            toasts.showToast("Copied to clipboard", ToastKind.Success)
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(contentPadding)
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Header()

            state.clipboardSuggestion?.let { suggestion ->
                ClipboardCard(
                    suggestion = suggestion,
                    onAnalyze = viewModel::acceptClipboardSuggestion,
                    onIgnore = viewModel::ignoreClipboardSuggestion,
                )
            }

            AnalyzerCard(
                state = state,
                showPasteButton = clipboardHasText && state.urlText.isBlank() && state.clipboardSuggestion == null,
                onUrlChanged = viewModel::onUrlChanged,
                onPaste = paste,
                onClear = { viewModel.onUrlChanged("") },
                onAnalyze = viewModel::analyze,
                onCancel = viewModel::cancelAnalyze,
            )

            val phase = state.phase
            AnimatedVisibility(visible = phase is DetectPhase.Error) {
                (phase as? DetectPhase.Error)?.let { error ->
                    AnalysisErrorCard(
                        error = error,
                        onRetry = viewModel::analyze,
                        onCopyUrl = { copyUrl(error.url) },
                        onClose = { viewModel.resetDetection(clearUrl = false) },
                    )
                }
            }

            (phase as? DetectPhase.ChooseScope)?.let { choice ->
                ScopeChoiceCard(
                    choice = choice,
                    onCurrentVideo = viewModel::chooseCurrentVideo,
                    onPlaylist = viewModel::choosePlaylist,
                    onClose = { viewModel.resetDetection(clearUrl = false) },
                )
            }

            // Metadata loading: skeletons shaped like the format list that's about to appear.
            AnimatedVisibility(visible = phase is DetectPhase.Analyzing) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Analyzing ${(phase as? DetectPhase.Analyzing)?.detected?.displayName ?: "link"}…",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    repeat(3) { SkeletonRow() }
                }
            }

            (phase as? DetectPhase.Batch)?.let { batch ->
                BatchResultsCard(
                    batch = batch,
                    onChooseScope = viewModel::chooseBatchScope,
                    onDownloadAll = { choice ->
                        scope.launch {
                            val queued = viewModel.downloadAllReady(choice, includeAlreadyDownloaded = false)
                            if (queued > 0) {
                                viewModel.dismissBatch()
                                toasts.showToast("$queued download${if (queued == 1) "" else "s"} started", ToastKind.Success)
                                onOpenDownloads()
                            } else {
                                toasts.showToast("Nothing ready to download.", ToastKind.Error)
                            }
                        }
                    },
                    onDismiss = viewModel::dismissBatch,
                )
            }

            if (phase is DetectPhase.Idle && state.urlText.isBlank()) SupportedPlatforms()

            val active = downloads.filter { it.status.isActive || it.status == DownloadStatus.QUEUED }
            if (active.isNotEmpty()) ActiveDownloadsSection(active, onOpenDownloads)

            if (recent.isNotEmpty()) {
                RecentDownloadsSection(recent) { entry ->
                    val uri = entry.fileUri
                    if (uri == null || !FileActions.open(context, uri, entry.filename)) {
                        scope.launch { toasts.showToast("No app can open this file, or it was moved.", ToastKind.Error) }
                    }
                }
            }
        }
        GlassSnackbarHost(
            toasts,
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = contentPadding.calculateBottomPadding()),
        )
    }
}

@Composable
private fun Header() {
    val galactic = LocalResolvedTheme.current.style == VisualStyle.GALACTIC
    Column {
        Text(
            stringResource(R.string.app_name),
            maxLines = 1,
            style = if (galactic) {
                // Galactic: cyan → violet wordmark.
                MaterialTheme.typography.headlineLarge.copy(brush = Brush.linearGradient(listOf(GalacticElectricCyan, GalacticCosmicViolet)))
            } else {
                MaterialTheme.typography.headlineLarge
            },
            // An explicit color would override the gradient brush.
            color = if (galactic) Color.Unspecified else MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Black,
        )
        Text(stringResource(R.string.app_subtitle), style = MaterialTheme.typography.titleMedium)
        Text(
            "Paste or share a YouTube or TikTok link — ENAGELYUCA shows the qualities that are really available.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AnalyzerCard(
    state: HomeUiState,
    showPasteButton: Boolean,
    onUrlChanged: (String) -> Unit,
    onPaste: () -> Unit,
    onClear: () -> Unit,
    onAnalyze: () -> Unit,
    onCancel: () -> Unit,
) {
    val analyzing = state.phase is DetectPhase.Analyzing
    val galactic = LocalResolvedTheme.current.style == VisualStyle.GALACTIC
    val scheme = MaterialTheme.colorScheme
    val fieldInteraction = remember { MutableInteractionSource() }
    val focused by fieldInteraction.collectIsFocusedAsState()
    // Galactic focus glow: a soft cyan halo that fades in while the field is focused.
    val glowAlpha by animateFloatAsState(if (galactic && focused) 1f else 0f, tween(250), label = "fieldGlow")
    val fieldShape = RoundedCornerShape(if (galactic) 20.dp else 16.dp)
    val urlCount = state.urls.size
    GlassSurface(modifier = Modifier.fillMaxWidth(), cornerRadius = 28.dp) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            OutlinedTextField(
                value = state.urlText,
                onValueChange = onUrlChanged,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = if (galactic) 64.dp else 56.dp)
                    .then(
                        if (glowAlpha > 0f) {
                            Modifier.shadow(16.dp * glowAlpha, fieldShape, ambientColor = GalacticElectricCyan, spotColor = GalacticElectricCyan)
                        } else {
                            Modifier
                        },
                    ),
                interactionSource = fieldInteraction,
                enabled = !analyzing,
                textStyle = if (galactic) MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium) else LocalTextStyle.current,
                label = { Text("Media link") },
                placeholder = { Text("YouTube or TikTok link") },
                leadingIcon = { Icon(Icons.Filled.Link, contentDescription = null) },
                trailingIcon = {
                    if (state.urlText.isNotEmpty() && !analyzing) {
                        IconButton(onClick = onClear) { Icon(Icons.Filled.Clear, contentDescription = "Clear link") }
                    } else if (!analyzing) {
                        IconButton(onClick = onPaste) { Icon(Icons.Filled.ContentPaste, contentDescription = "Paste link") }
                    }
                },
                isError = state.urlHint != null,
                supportingText = state.urlHint?.let { hint -> { Text(hint) } },
                // Several links can be pasted at once, one per line.
                singleLine = false,
                maxLines = 5,
                shape = fieldShape,
                // Translucent fill keeps the typed link readable on top of the glass.
                colors = if (galactic) {
                    OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = GalacticDeepSpace.copy(alpha = 0.72f),
                        unfocusedContainerColor = GalacticDeepSpace.copy(alpha = 0.55f),
                        disabledContainerColor = GalacticDeepSpace.copy(alpha = 0.4f),
                        focusedBorderColor = GalacticElectricCyan,
                        unfocusedBorderColor = scheme.outline,
                        focusedLeadingIconColor = GalacticElectricCyan,
                        cursorColor = GalacticElectricCyan,
                    )
                } else {
                    OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = scheme.surface.copy(alpha = 0.75f),
                        unfocusedContainerColor = scheme.surface.copy(alpha = 0.55f),
                        disabledContainerColor = scheme.surface.copy(alpha = 0.4f),
                    )
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { if (state.canAnalyze) onAnalyze() }),
            )

            // Platform detected locally as soon as a link is in the field — before any network call.
            val preview = (state.phase as? DetectPhase.Analyzing)?.detected ?: state.detectedPreview
            if (preview != null) {
                PlatformBadge(preview)
            } else if (urlCount > 1) {
                Text("$urlCount links — each is analyzed separately", style = MaterialTheme.typography.labelLarge)
            }

            if (showPasteButton && !analyzing) {
                FilledTonalButton(onClick = onPaste, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Icon(Icons.Filled.ContentPaste, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Paste link from clipboard")
                }
            }

            if (analyzing) GlassProgressBar(progress = null)

            PrimaryActionButton(
                text = if (urlCount > 1) "Analyze $urlCount links" else "Analyze",
                icon = Icons.Filled.TravelExplore,
                onClick = onAnalyze,
                enabled = state.canAnalyze || state.phase is DetectPhase.Error,
                state = when (state.phase) {
                    is DetectPhase.Analyzing -> ActionState.Loading
                    is DetectPhase.Error -> ActionState.Error
                    else -> ActionState.Idle
                },
                loadingText = "Analyzing…",
                errorText = "Try again",
                modifier = Modifier.fillMaxWidth(),
            )

            if (analyzing) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    TextButton(onClick = onCancel) { Text("Cancel") }
                }
            }
        }
    }
}

@Composable
private fun ClipboardCard(suggestion: ClipboardSuggestion, onAnalyze: () -> Unit, onIgnore: () -> Unit) {
    GlassSurface(Modifier.fillMaxWidth(), cornerRadius = 22.dp, style = GlassStyle.Accent) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Media link detected", style = MaterialTheme.typography.titleSmall)
            Text("${suggestion.detected.displayName} link copied to the clipboard.", style = MaterialTheme.typography.bodyMedium)
            Text(
                suggestion.url,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onAnalyze) { Text("Analyze") }
                TextButton(onClick = onIgnore) { Text("Ignore") }
            }
        }
    }
}

@Composable
private fun AnalysisErrorCard(error: DetectPhase.Error, onRetry: () -> Unit, onCopyUrl: () -> Unit, onClose: () -> Unit) {
    GlassSurface(Modifier.fillMaxWidth(), cornerRadius = 22.dp) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(10.dp))
                Text(error.info.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
            }
            error.detected?.let { PlatformBadge(it) }
            Text(error.info.message, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (ErrorAction.RETRY in error.info.actions) {
                    FilledTonalButton(onClick = onRetry) {
                        Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Retry")
                    }
                }
                if (ErrorAction.COPY_URL in error.info.actions) {
                    OutlinedButton(onClick = onCopyUrl) {
                        Icon(Icons.Filled.ContentCopy, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Copy URL")
                    }
                }
                TextButton(onClick = onClose) { Text("Close") }
            }
        }
    }
}

@Composable
private fun BatchResultsCard(
    batch: DetectPhase.Batch,
    onChooseScope: (url: String, wholePlaylist: Boolean) -> Unit,
    onDownloadAll: (QuickFormat) -> Unit,
    onDismiss: () -> Unit,
) {
    // Offered choices for a whole batch: best video, 720p cap, MP3 (VBR).
    val offered = listOf(QuickFormat.BestVideo(), QuickFormat.BestVideo(720), QuickFormat.AudioMp3())
    var choiceIndex by rememberSaveable { mutableIntStateOf(0) }
    val ready = batch.items.count { it.status == BatchStatus.READY }
    val stillAnalyzing = batch.items.any { it.status == BatchStatus.ANALYZING }
    val undecided = batch.items.any { it.status == BatchStatus.NEEDS_CHOICE }
    val existing = batch.items.count { it.status == BatchStatus.READY && it.alreadyDownloaded }
    GlassSurface(Modifier.fillMaxWidth(), cornerRadius = 24.dp) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("${batch.items.size} links", style = MaterialTheme.typography.titleMedium)
            batch.items.forEach { item -> BatchRow(item, onChooseScope) }
            if (undecided) {
                Text(
                    "Some links point to a video inside a playlist. Choose \"This video\" or \"Playlist\" for each.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (!stillAnalyzing && ready > 0) {
                Text("Quality for all", style = MaterialTheme.typography.labelLarge)
                // Single-line chips; the row scrolls sideways on narrow screens instead of wrapping.
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    offered.forEachIndexed { i, choice ->
                        GlassChip(choice.label, selected = choiceIndex == i, onClick = { choiceIndex = i })
                    }
                }
                if (existing > 0) {
                    Text(
                        "$existing already downloaded — they'll be skipped.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            PrimaryActionButton(
                text = if (ready - existing > 0) "Download all ready (${ready - existing})" else "Download all ready",
                icon = Icons.Filled.Download,
                onClick = { onDownloadAll(offered[choiceIndex]) },
                enabled = !stillAnalyzing && !undecided && ready - existing > 0,
                state = if (stillAnalyzing) ActionState.Loading else ActionState.Idle,
                loadingText = "Analyzing links…",
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Clear") }
        }
    }
}

@Composable
private fun BatchRow(item: BatchItem, onChooseScope: (url: String, wholePlaylist: Boolean) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    if (item.status == BatchStatus.NEEDS_CHOICE) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            PlatformBadge(item.detected)
            Text(item.url, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassChip("This video", selected = false, onClick = { onChooseScope(item.url, false) })
                GlassChip("Playlist", selected = false, onClick = { onChooseScope(item.url, true) })
            }
        }
        return
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        when (item.status) {
            BatchStatus.NEEDS_CHOICE -> Unit
            BatchStatus.ANALYZING -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            BatchStatus.READY -> Icon(Icons.Filled.CheckCircle, "Ready", tint = scheme.primary, modifier = Modifier.size(20.dp))
            BatchStatus.FAILED ->
                Icon(Icons.Filled.ErrorOutline, "Not available", tint = scheme.error, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            PlatformBadge(item.detected)
            Text(item.title ?: item.url, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            when (item.status) {
                BatchStatus.NEEDS_CHOICE -> "Choose"
                BatchStatus.ANALYZING -> "Analyzing"
                BatchStatus.READY -> if (item.alreadyDownloaded) "Downloaded" else "Ready"
                BatchStatus.FAILED -> item.error ?: "Failed"
            },
            style = MaterialTheme.typography.labelMedium,
            color = if (item.status == BatchStatus.FAILED) scheme.error else scheme.onSurfaceVariant,
            maxLines = 2,
            modifier = Modifier.width(96.dp),
        )
    }
}

/**
 * `watch?v=…&list=…`: explicit choice between the one video and the playlist it came from. The
 * link itself (all parameters) is kept either way.
 */
@Composable
private fun ScopeChoiceCard(
    choice: DetectPhase.ChooseScope,
    onCurrentVideo: () -> Unit,
    onPlaylist: () -> Unit,
    onClose: () -> Unit,
) {
    val context = choice.detected.playlistContext
    GlassSurface(Modifier.fillMaxWidth(), cornerRadius = 24.dp) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PlatformBadge(choice.detected)
            Text("This video is part of a playlist", style = MaterialTheme.typography.titleSmall)
            Text(
                buildString {
                    append("Download only this video, or open the playlist to pick its items")
                    context?.index?.let { append(" (this is item $it)") }
                    append(".")
                    if (context?.isMix == true) append(" This is a YouTube Mix; it's generated for you and may be long.")
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(onClick = onCurrentVideo, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Movie, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Current video", maxLines = 1)
                }
                FilledTonalButton(onClick = onPlaylist, modifier = Modifier.weight(1f)) {
                    Icon(Icons.AutoMirrored.Filled.PlaylistPlay, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Playlist", maxLines = 1)
                }
            }
            TextButton(onClick = onClose, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Cancel") }
        }
    }
}

@Composable
private fun SupportedPlatforms() {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Supported", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            "YouTube (videos, Shorts, live replays, playlists) • TikTok (videos, share links)",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun ActiveDownloadsSection(active: List<DownloadEntity>, onOpen: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Active downloads", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onOpen) {
                Text("See all")
                Spacer(Modifier.width(4.dp))
                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(16.dp))
            }
        }
        active.take(3).forEach { entity ->
            GlassSurface(Modifier.fillMaxWidth(), cornerRadius = 18.dp, onClick = onOpen, onClickLabel = "Open downloads") {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(entity.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (entity.status.isActive) {
                        GlassProgressBar(progress = ProgressDisplay.fraction(entity))
                        Text(
                            ProgressDisplay.text(entity),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text("Waiting", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentDownloadsSection(recent: List<HistoryEntity>, onOpen: (HistoryEntity) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Recent downloads", style = MaterialTheme.typography.titleSmall)
        recent.forEach { entry ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClickLabel = "Open file") { onOpen(entry) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ThumbnailImage(entry.thumbnailUrl, null, Modifier.size(width = 64.dp, height = 36.dp), cornerRadius = 8.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(entry.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(
                            Platforms.detect(entry.webpageUrl).displayName,
                            entry.formatLabel,
                            entry.fileSizeBytes?.let { Formatting.bytes(it) },
                        ).joinToString(" • "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
