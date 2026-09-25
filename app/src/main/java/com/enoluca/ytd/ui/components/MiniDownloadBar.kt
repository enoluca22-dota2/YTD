package com.enoluca.ytd.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.enoluca.ytd.core.Formatting
import com.enoluca.ytd.download.ProgressDisplay
import com.enoluca.ytd.data.local.db.DownloadEntity
import com.enoluca.ytd.ui.glass.GlassProgressBar
import com.enoluca.ytd.ui.glass.GlassStyle
import com.enoluca.ytd.ui.glass.GlassSurface

/** Height reserved above the tab bar while the mini bar is visible. */
val MiniDownloadBarHeight = 64.dp

/**
 * Persistent compact panel for the download in progress ("↓ My Video · 78% · 8.4 MB/s  ⏸"),
 * shown above the tab bar on every top-level screen except Downloads. Tap opens Downloads.
 */
@Composable
fun MiniDownloadBar(
    current: DownloadEntity,
    othersActive: Int,
    onOpen: () -> Unit,
    onPause: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassSurface(
        modifier.fillMaxWidth().height(MiniDownloadBarHeight),
        cornerRadius = 20.dp,
        style = GlassStyle.Accent,
        onClick = onOpen,
        onClickLabel = "Open downloads",
    ) {
        Column(Modifier.padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Download, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(current.title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(
                            ProgressDisplay.short(current),
                            Formatting.speed(current.speedBytesPerSec).takeIf { it != "-" },
                            othersActive.takeIf { it > 0 }?.let { "+$it more" },
                        ).joinToString(" • "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onPause) { Icon(Icons.Filled.Pause, contentDescription = "Pause ${current.title}") }
            }
            GlassProgressBar(
                progress = ProgressDisplay.fraction(current),
                modifier = Modifier.padding(end = 10.dp),
            )
        }
    }
}
