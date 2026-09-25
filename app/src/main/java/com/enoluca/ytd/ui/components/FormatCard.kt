package com.enoluca.ytd.ui.components

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MergeType
import androidx.compose.material.icons.filled.Hd
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.enoluca.ytd.core.Formatting
import com.enoluca.ytd.data.model.FormatKind
import com.enoluca.ytd.data.model.MediaFormat
import com.enoluca.ytd.data.model.Metric
import com.enoluca.ytd.data.model.Watermark
import com.enoluca.ytd.ui.glass.GlassSurface

@Composable
fun FormatCard(
    format: MediaFormat,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    val detail = detailLine(format)
    GlassSurface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .semantics(mergeDescendants = true) {
                this.selected = selected
                contentDescription = "${format.displayLabel}${format.qualityName?.let { " $it" } ?: ""}, $detail, ${sizeDescription(format.fileSizeBytes)}"
            },
        cornerRadius = 20.dp,
        selected = selected,
        onClick = onClick,
        onClickLabel = "Choose this quality",
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(format.displayLabel, style = MaterialTheme.typography.titleMedium)
                    format.qualityName?.let {
                        Text(it, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    }
                    if (format.isHdr) {
                        Icon(Icons.Filled.Hd, contentDescription = "HDR", modifier = Modifier.size(18.dp))
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    QualityBadge(format.tier)
                    Spacer(Modifier.width(8.dp))
                    if (format.kind == FormatKind.VIDEO && !format.hasAudio) {
                        Icon(
                            Icons.AutoMirrored.Filled.MergeType,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(12.dp))
            SizeText(format.fileSizeBytes, isVbr = format.isVbrAudio)
        }
    }
}

@Composable
private fun SizeText(metric: Metric<Long>, isVbr: Boolean) {
    when (metric) {
        is Metric.Known -> Text(Formatting.bytes(metric.value), style = MaterialTheme.typography.labelLarge)
        is Metric.Estimated -> Column(horizontalAlignment = Alignment.End) {
            Text("~${Formatting.bytes(metric.value)}", style = MaterialTheme.typography.labelLarge)
            EstimatedTag()
        }
        Metric.Unknown -> Text(
            if (isVbr) "Size varies" else "Size unknown",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun sizeDescription(metric: Metric<Long>): String = when (metric) {
    is Metric.Known -> Formatting.bytes(metric.value)
    is Metric.Estimated -> "about ${Formatting.bytes(metric.value)}"
    Metric.Unknown -> "size unknown"
}

private fun detailLine(format: MediaFormat): String {
    val parts = mutableListOf<String>()
    if (format.isBestAvailable) {
        parts += "Top video + audio"
    } else if (format.kind == FormatKind.VIDEO) {
        format.container?.let { parts += it.uppercase() }
        format.fps?.takeIf { it > 30 }?.let { parts += "$it fps" }
        parts += if (format.hasAudio) "with audio" else "audio added"
    } else if (format.nativeAudio) {
        parts += "Original stream, no re-encode"
    } else if (format.isVbrAudio) {
        parts += "Bitrate follows the source"
    } else {
        parts += "Constant bitrate"
    }
    // Only what the extractor itself reports — never a claim that we removed a watermark.
    when (format.watermark) {
        Watermark.NO_WATERMARK -> parts += "No watermark ✓"
        Watermark.WATERMARKED -> parts += "Watermarked"
        Watermark.UNKNOWN -> Unit
    }
    return parts.joinToString(" · ")
}
