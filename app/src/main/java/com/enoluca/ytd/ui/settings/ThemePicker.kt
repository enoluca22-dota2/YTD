package com.enoluca.ytd.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.enoluca.ytd.data.local.datastore.AppThemeMode
import com.enoluca.ytd.ui.theme.DarkBackground
import com.enoluca.ytd.ui.theme.DarkSurfaceVariant
import com.enoluca.ytd.ui.theme.GalacticCosmicBlue
import com.enoluca.ytd.ui.theme.GalacticCosmicViolet
import com.enoluca.ytd.ui.theme.GalacticDeepSpace
import com.enoluca.ytd.ui.theme.GalacticElectricCyan
import com.enoluca.ytd.ui.theme.GalacticIndigo
import com.enoluca.ytd.ui.theme.GalacticNebulaViolet
import com.enoluca.ytd.ui.theme.LightBackground
import com.enoluca.ytd.ui.theme.LightSurfaceVariant
import com.enoluca.ytd.ui.theme.YtdOrange
import com.enoluca.ytd.ui.theme.YtdOrangeLight

private data class ThemeOption(val mode: AppThemeMode, val symbol: String, val label: String)

private val Options = listOf(
    ThemeOption(AppThemeMode.LIGHT, "☀", "Light"),
    ThemeOption(AppThemeMode.DARK, "☾", "Dark"),
    ThemeOption(AppThemeMode.GLASS, "◈", "Glass"),
    ThemeOption(AppThemeMode.GALACTIC, "✦", "Premium Glass"),
    ThemeOption(AppThemeMode.SYSTEM, "⚙", "System"),
)

/**
 * Five theme tiles, each with a miniature preview of the theme it applies. Laid out 3 + 2 so every
 * label fits at phone width. Behaves as a radio group for accessibility.
 */
@Composable
fun ThemePicker(selected: AppThemeMode, onSelect: (AppThemeMode) -> Unit, modifier: Modifier = Modifier) {
    val systemDark = isSystemInDarkTheme()
    Column(modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Options.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { option ->
                    ThemeTile(
                        option = option,
                        selected = option.mode == selected,
                        systemDark = systemDark,
                        onClick = { onSelect(option.mode) },
                        modifier = Modifier.weight(1f),
                    )
                }
                // Keep the second row's tiles the same width as the first row's.
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        Text(
            if (selected == AppThemeMode.SYSTEM) {
                "Follows Android's appearance — currently ${if (systemDark) "Dark" else "Light"}. Changes automatically."
            } else {
                descriptionFor(selected)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun descriptionFor(mode: AppThemeMode) = when (mode) {
    AppThemeMode.LIGHT -> "Bright, plain surfaces."
    AppThemeMode.DARK -> "Dark, plain surfaces. Easy on the eyes at night."
    AppThemeMode.GLASS -> "Liquid Glass with a warm gradient. Follows Android's light/dark setting."
    AppThemeMode.GALACTIC -> "Premium Glass: deep-space glass with aurora accents. Always dark."
    AppThemeMode.SYSTEM -> ""
}

@Composable
private fun ThemeTile(
    option: ThemeOption,
    selected: Boolean,
    systemDark: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier
            .clip(shape)
            .border(if (selected) 2.dp else 1.dp, if (selected) scheme.primary else scheme.outline.copy(alpha = 0.6f), shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = "${option.label} theme" }
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            Canvas(Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(11.dp))) {
                when (option.mode) {
                    AppThemeMode.LIGHT -> drawPlainPreview(LightBackground, LightSurfaceVariant, YtdOrange)
                    AppThemeMode.DARK -> drawPlainPreview(DarkBackground, DarkSurfaceVariant, YtdOrangeLight)
                    AppThemeMode.GLASS -> drawGlassPreview(systemDark)
                    AppThemeMode.GALACTIC -> drawGalacticPreview()
                    AppThemeMode.SYSTEM -> drawSystemPreview()
                }
            }
            if (selected) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(scheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = scheme.onPrimary, modifier = Modifier.size(14.dp))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "${option.symbol}  ${option.label}",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) scheme.primary else scheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    }
}

// --- miniature previews: a background, a "card" and an accent "button" ---

private fun DrawScope.drawCard(color: Color, border: Color? = null) {
    val top = Offset(size.width * 0.12f, size.height * 0.22f)
    val cardSize = Size(size.width * 0.76f, size.height * 0.36f)
    drawRoundRect(color, top, cardSize, CornerRadius(8.dp.toPx()))
    if (border != null) {
        drawRoundRect(border, top, cardSize, CornerRadius(8.dp.toPx()), style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
    }
}

private fun DrawScope.drawButton(brush: Brush) {
    drawRoundRect(
        brush,
        Offset(size.width * 0.12f, size.height * 0.68f),
        Size(size.width * 0.76f, size.height * 0.16f),
        CornerRadius(size.height * 0.08f),
    )
}

private fun DrawScope.drawPlainPreview(background: Color, card: Color, accent: Color) {
    drawRect(background)
    drawCard(card)
    drawButton(Brush.linearGradient(listOf(accent, accent)))
}

private fun DrawScope.drawGlassPreview(dark: Boolean) {
    drawRect(if (dark) Color(0xFF110D11) else Color(0xFFFFF5F0))
    val blobs = if (dark) listOf(Color(0xFFFF4D2E), Color(0xFF7B2FBE)) else listOf(Color(0xFFFF7A59), Color(0xFFFFB3C8))
    drawCircle(Brush.radialGradient(listOf(blobs[0].copy(alpha = 0.6f), Color.Transparent), Offset.Zero, size.width * 0.8f), size.width * 0.8f, Offset.Zero)
    drawCircle(
        Brush.radialGradient(listOf(blobs[1].copy(alpha = 0.6f), Color.Transparent), Offset(size.width, size.height), size.width * 0.8f),
        size.width * 0.8f,
        Offset(size.width, size.height),
    )
    drawCard(if (dark) Color(0x6B1B1719) else Color(0x80FFFFFF), border = Color.White.copy(alpha = 0.5f))
    drawButton(Brush.linearGradient(listOf(YtdOrange, YtdOrange)))
}

private fun DrawScope.drawGalacticPreview() {
    drawRect(Brush.verticalGradient(listOf(GalacticIndigo, GalacticDeepSpace, GalacticCosmicBlue)))
    drawCircle(
        Brush.radialGradient(listOf(GalacticNebulaViolet, Color.Transparent), Offset(size.width * 0.1f, size.height * 0.6f), size.width * 0.6f),
        size.width * 0.6f,
        Offset(size.width * 0.1f, size.height * 0.6f),
    )
    // A few stars.
    listOf(0.15f to 0.12f, 0.8f to 0.1f, 0.62f to 0.9f, 0.92f to 0.55f, 0.35f to 0.85f).forEach { (x, y) ->
        drawCircle(Color.White.copy(alpha = 0.8f), 0.9.dp.toPx(), Offset(size.width * x, size.height * y))
    }
    drawCard(GalacticCosmicBlue.copy(alpha = 0.7f), border = GalacticElectricCyan.copy(alpha = 0.5f))
    drawButton(Brush.horizontalGradient(listOf(GalacticElectricCyan, GalacticCosmicViolet)))
}

/** Split diagonally: half Light, half Dark — "follows the device". */
private fun DrawScope.drawSystemPreview() {
    drawRect(LightBackground)
    val path = androidx.compose.ui.graphics.Path().apply {
        moveTo(size.width, 0f)
        lineTo(size.width, size.height)
        lineTo(0f, size.height)
        close()
    }
    drawPath(path, DarkBackground)
    drawCard(Color(0xFF8E8A8C).copy(alpha = 0.5f))
    drawButton(Brush.linearGradient(listOf(YtdOrange, YtdOrangeLight)))
}
