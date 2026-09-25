package com.enoluca.ytd.ui.formats

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.enoluca.ytd.core.FileActions
import com.enoluca.ytd.core.FileNaming
import com.enoluca.ytd.core.Formatting
import com.enoluca.ytd.data.local.db.HistoryEntity
import com.enoluca.ytd.data.model.DownloadCategory
import com.enoluca.ytd.data.model.MediaFormat
import com.enoluca.ytd.data.model.MediaInfo
import com.enoluca.ytd.data.model.suggestedCategory
import com.enoluca.ytd.data.platform.DetectedPlatform
import com.enoluca.ytd.ui.components.EmptyState
import com.enoluca.ytd.ui.components.FormatCard
import com.enoluca.ytd.ui.components.PlatformBadge
import com.enoluca.ytd.ui.components.ThumbnailImage
import com.enoluca.ytd.ui.glass.ActionState
import com.enoluca.ytd.ui.glass.GlassSnackbarHost
import com.enoluca.ytd.ui.glass.GlassStyle
import com.enoluca.ytd.ui.glass.GlassSurface
import com.enoluca.ytd.ui.glass.ToastKind
import com.enoluca.ytd.ui.glass.sheetContainerColor
import com.enoluca.ytd.ui.glass.showToast
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaDetailScreen(
    media: MediaInfo,
    detected: DetectedPlatform,
    askBeforeDownloading: Boolean,
    customFolderSelected: Boolean,
    findExisting: suspend () -> HistoryEntity?,
    onBack: () -> Unit,
    onSubmit: suspend (format: MediaFormat, category: DownloadCategory, fileBaseName: String, startNow: Boolean) -> Unit,
    onSubmitted: () -> Unit,
) {
    // Saved across rotation/recreation; formats are looked up again by their stable key.
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var submitState by remember { mutableStateOf(ActionState.Idle) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    BackHandler(onBack = onBack)

    // Duplicate detection: an earlier download of this same media whose file still exists.
    var existing by remember { mutableStateOf<HistoryEntity?>(null) }
    var duplicateConfirmed by rememberSaveable { mutableStateOf(false) }
    var pendingSubmit by remember { mutableStateOf<(() -> Unit)?>(null) }
    val context = LocalContext.current
    LaunchedEffect(media.webpageUrl) { existing = findExisting() }

    fun submit(format: MediaFormat, category: DownloadCategory, name: String, startNow: Boolean) {
        if (submitState == ActionState.Loading || submitState == ActionState.Success) return
        if (existing != null && !duplicateConfirmed) {
            // Ask first: open the file they already have, or download it again.
            pendingSubmit = { submit(format, category, name, startNow) }
            return
        }
        submitState = ActionState.Loading
        scope.launch {
            try {
                onSubmit(format, category, name, startNow)
                // Brief confirmation on the button before moving to Downloads.
                submitState = ActionState.Success
                delay(450)
                selectedKey = null
                onSubmitted()
            } catch (e: Exception) {
                submitState = ActionState.Error
                snackbar.showToast("Couldn't add the download. Please try again.", ToastKind.Error)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Choose quality") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { GlassSnackbarHost(snackbar) },
        // Transparent so the app's glass background shows through.
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            MediaHeader(media, detected, alreadyDownloaded = existing != null)

            PrimaryTabRow(selectedTabIndex = selectedTab, containerColor = Color.Transparent) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Video (${media.videoFormats.size})") },
                    icon = { Icon(Icons.Filled.Videocam, contentDescription = null) },
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Audio (${media.audioFormats.size})") },
                    icon = { Icon(Icons.Filled.MusicNote, contentDescription = null) },
                )
            }

            val formats = if (selectedTab == 0) media.videoFormats else media.audioFormats
            if (formats.isEmpty()) {
                EmptyState(
                    title = "Nothing to show here",
                    message = if (selectedTab == 0) "This link doesn't offer any video formats." else "This link doesn't offer an audio track.",
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item(key = "hint_$selectedTab") { FormatHint(isAudio = selectedTab == 1) }
                    items(formats, key = { it.key }) { format ->
                        FormatCard(
                            format = format,
                            selected = format.key == selectedKey,
                            onClick = {
                                if (askBeforeDownloading) {
                                    selectedKey = format.key
                                } else {
                                    submit(format, format.kind.suggestedCategory(), FileNaming.sanitize(media.title), true)
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    if (pendingSubmit != null) {
        val previous = existing
        AlertDialog(
            onDismissRequest = { pendingSubmit = null },
            containerColor = sheetContainerColor(),
            icon = { Icon(Icons.Filled.CheckCircle, contentDescription = null) },
            title = { Text("Already downloaded") },
            text = {
                Text(
                    "You downloaded this before" +
                        (previous?.let { " (${it.formatLabel}${it.resolutionLabel?.let { r -> " · $r" } ?: ""})" } ?: "") +
                        " and the file still exists.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val again = pendingSubmit
                    pendingSubmit = null
                    duplicateConfirmed = true
                    again?.invoke()
                }) { Text("Download again") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { pendingSubmit = null }) { Text("Cancel") }
                    TextButton(onClick = {
                        pendingSubmit = null
                        val uri = previous?.fileUri
                        if (uri == null || !FileActions.open(context, uri, previous.filename)) {
                            scope.launch { snackbar.showToast("No app can open this file.", ToastKind.Error) }
                        }
                    }) { Text("Open existing") }
                }
            },
        )
    }

    val selectedFormat = selectedKey?.let { key -> (media.videoFormats + media.audioFormats).firstOrNull { it.key == key } }
    selectedFormat?.let { format ->
        DownloadDetailsSheet(
            media = media,
            format = format,
            customFolderSelected = customFolderSelected,
            submitState = submitState,
            onDismiss = {
                selectedKey = null
                submitState = ActionState.Idle
            },
            onConfirm = { category, fileBaseName, startNow -> submit(format, category, fileBaseName, startNow) },
        )
    }
}

@Composable
private fun FormatHint(isAudio: Boolean) {
    val text = if (isAudio) {
        "MP3 options are converted. \"Original quality (VBR)\" lets the encoder choose the bitrate to match the " +
            "source; fixed bitrates give a predictable size but can't add quality the source doesn't have. " +
            "M4A (when offered) is the source's own audio, not re-encoded."
    } else {
        "Only formats this source actually offers are listed. \"Best available\" lets the downloader pick the top quality; " +
            "formats without sound get the best audio track added automatically."
    }
    GlassSurface(cornerRadius = 16.dp, style = GlassStyle.Clear) {
        Row(Modifier.padding(12.dp)) {
            Icon(
                Icons.Filled.Info,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(10.dp))
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun MediaHeader(media: MediaInfo, detected: DetectedPlatform, alreadyDownloaded: Boolean) {
    GlassSurface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), cornerRadius = 24.dp, style = GlassStyle.Clear) {
        Row(Modifier.fillMaxWidth().padding(10.dp)) {
            ThumbnailImage(
                url = media.thumbnailUrl,
                contentDescription = null,
                modifier = Modifier.size(width = 128.dp, height = 72.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                PlatformBadge(detected)
                Spacer(Modifier.height(4.dp))
                Text(media.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                val meta = listOfNotNull(
                    media.uploader,
                    media.durationSeconds?.let { Formatting.duration(it) },
                ).joinToString(" · ")
                if (meta.isNotBlank()) {
                    Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (alreadyDownloaded) {
                    Text("Already downloaded", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}
