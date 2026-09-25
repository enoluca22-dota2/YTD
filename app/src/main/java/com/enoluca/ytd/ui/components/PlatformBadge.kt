package com.enoluca.ytd.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.enoluca.ytd.data.platform.ContentKind
import com.enoluca.ytd.data.platform.DetectedPlatform

/** Brand dot color for the two supported sites; anything else gets the theme's outline color. */
fun platformColor(id: String?): Color? = when (id) {
    "youtube" -> Color(0xFFFF0033)
    "tiktok" -> Color(0xFF25F4EE)
    else -> null
}

/** "◉ TikTok · Video" — small pill identifying the source of a link. */
@Composable
fun PlatformBadge(
    detected: DetectedPlatform,
    modifier: Modifier = Modifier,
    kind: ContentKind? = detected.kind,
) {
    val dot = platformColor(detected.spec?.id) ?: MaterialTheme.colorScheme.outline
    val text = listOfNotNull(detected.displayName, kind?.label).joinToString(" · ")
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f))
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .semantics(mergeDescendants = true) { contentDescription = "Source: $text" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.size(8.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}
