package com.enoluca.ytd.ui.formats

import android.content.Context
import android.os.Environment
import android.os.StatFs
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.enoluca.ytd.data.provider.StoragePublisher
import com.enoluca.ytd.core.FileNaming
import com.enoluca.ytd.core.Formatting
import com.enoluca.ytd.data.model.DownloadCategory
import com.enoluca.ytd.data.model.FormatKind
import com.enoluca.ytd.data.model.MediaFormat
import com.enoluca.ytd.data.model.MediaInfo
import com.enoluca.ytd.data.model.Metric
import com.enoluca.ytd.data.model.suggestedCategory
import com.enoluca.ytd.ui.components.ThumbnailImage
import com.enoluca.ytd.ui.glass.ActionState
import com.enoluca.ytd.ui.glass.GlassChip
import com.enoluca.ytd.ui.glass.PrimaryActionButton
import com.enoluca.ytd.ui.glass.sheetContainerColor
import com.enoluca.ytd.ui.theme.LocalResolvedTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadDetailsSheet(
    media: MediaInfo,
    format: MediaFormat,
    customFolderSelected: Boolean,
    submitState: ActionState,
    onDismiss: () -> Unit,
    onConfirm: (category: DownloadCategory, fileBaseName: String, startNow: Boolean) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var fileBaseName by rememberSaveable(format.key) { mutableStateOf(FileNaming.sanitize(media.title)) }
    var category by rememberSaveable(format.key) { mutableStateOf(format.kind.suggestedCategory()) }
    val isAudio = format.kind == FormatKind.AUDIO
    val extension = if (isAudio && !format.nativeAudio) "mp3" else format.container ?: "mp4"

    val submitting = submitState == ActionState.Loading || submitState == ActionState.Success
    val darkTheme = LocalResolvedTheme.current.dark
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = sheetContainerColor(),
        // The sheet has its own window; keep its status/nav bar icons matching the app theme
        // (otherwise Galactic briefly shows dark icons on a light phone while the sheet closes).
        properties = ModalBottomSheetProperties(
            isAppearanceLightStatusBars = !darkTheme,
            isAppearanceLightNavigationBars = !darkTheme,
        ),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                ThumbnailImage(
                    url = media.thumbnailUrl,
                    contentDescription = null,
                    modifier = Modifier.size(width = 96.dp, height = 54.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(media.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                    Text(
                        Formatting.duration(media.durationSeconds),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))

            DetailRow("Quality", listOfNotNull(format.displayLabel, format.qualityName).joinToString(" "))
            DetailRow(
                "File type",
                when {
                    format.nativeAudio -> format.container?.uppercase() ?: "Original"
                    isAudio -> "MP3 (converted)"
                    else -> format.container?.uppercase() ?: "Unknown"
                },
            )
            if (format.nativeAudio) {
                DetailRow("Audio", "Original stream, not re-encoded")
            } else if (isAudio) {
                DetailRow("Bitrate", format.mp3BitrateKbps?.let { "$it kbps constant" } ?: "Variable — chosen by the encoder")
            } else {
                DetailRow("Codec", format.videoCodec ?: "Unknown")
            }
            DetailRow("Size", sizeLabel(format))
            DetailRow("Saved to", destinationLabel(category, customFolderSelected))
            val context = LocalContext.current
            val freeBytes = remember { freeStorageBytes(context) }
            freeBytes?.let { free ->
                DetailRow("Free storage", Formatting.bytes(free))
                val estimate = (format.fileSizeBytes as? Metric.Known)?.value ?: (format.fileSizeBytes as? Metric.Estimated)?.value
                if (estimate != null && estimate > free) {
                    Text(
                        "Not enough free space for this file. Free up storage or pick a smaller quality.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            val note = when {
                format.kind == FormatKind.VIDEO && !format.hasAudio ->
                    "This resolution has no sound on the source, so ENAGELYUCA adds the best available audio track automatically."
                format.nativeAudio ->
                    "Downloads the source's own ${format.container?.uppercase() ?: "audio"} track exactly as provided — no conversion."
                format.isBestAvailable ->
                    "The downloader picks the best video and audio this source offers at download time."
                format.isVbrAudio ->
                    "Downloads the best audio track and converts it to MP3 without forcing a bitrate, so quality follows the source."
                isAudio ->
                    "Downloads the best audio track and converts it to MP3 at ${format.mp3BitrateKbps} kbps."
                else -> null
            }
            note?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = fileBaseName,
                onValueChange = { fileBaseName = it },
                label = { Text("File name") },
                suffix = { Text(".$extension") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            )

            Spacer(Modifier.height(12.dp))
            Text("Save as", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(4.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(DownloadCategory.entries) { c ->
                    GlassChip(
                        label = c.name.lowercase().replaceFirstChar(Char::uppercase),
                        selected = category == c,
                        onClick = { category = c },
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            val name = fileBaseName.ifBlank { "ENAGELYUCA Download" }
            PrimaryActionButton(
                text = "Download now",
                icon = Icons.Filled.Download,
                onClick = { onConfirm(category, name, true) },
                state = submitState,
                loadingText = "Adding…",
                successText = "Added to downloads",
                errorText = "Try again",
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { onConfirm(category, name, false) },
                    enabled = !submitting,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) { Text("Save for later") }
                TextButton(onClick = onDismiss, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Cancel") }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.width(16.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Free space on the shared-storage volume downloads are saved to; null if it can't be read. */
private fun freeStorageBytes(context: Context): Long? =
    context.getExternalFilesDir(null)?.path?.let { path -> runCatching { StatFs(path).availableBytes }.getOrNull() }

private fun destinationLabel(category: DownloadCategory, customFolderSelected: Boolean): String {
    if (customFolderSelected) return "Your chosen folder"
    val root = when (category) {
        DownloadCategory.VIDEO -> Environment.DIRECTORY_MOVIES
        DownloadCategory.MUSIC -> Environment.DIRECTORY_MUSIC
        DownloadCategory.IMAGE -> Environment.DIRECTORY_PICTURES
        DownloadCategory.SUBTITLE, DownloadCategory.OTHER -> Environment.DIRECTORY_DOWNLOADS
    }
    return "$root/${StoragePublisher.FOLDER}"
}

private fun sizeLabel(format: MediaFormat): String = when (val metric = format.fileSizeBytes) {
    is Metric.Known -> Formatting.bytes(metric.value)
    is Metric.Estimated -> "About ${Formatting.bytes(metric.value)}"
    Metric.Unknown -> if (format.isVbrAudio) "Depends on the audio" else "Unknown"
}
