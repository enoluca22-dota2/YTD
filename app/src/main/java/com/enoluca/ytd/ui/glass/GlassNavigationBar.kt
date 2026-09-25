package com.enoluca.ytd.ui.glass

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.enoluca.ytd.ui.theme.GalacticMidnight
import com.enoluca.ytd.ui.theme.LocalResolvedTheme
import com.enoluca.ytd.ui.theme.VisualStyle
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule

/** Height of the floating bar itself; screens add this (plus margins) to their bottom padding. */
val GlassNavigationBarHeight = 68.dp

data class GlassNavItem(val label: String, val icon: ImageVector, val selected: Boolean, val onClick: () -> Unit)

/**
 * Floating Liquid Glass tab bar. [backdrop] should contain the screen content (not just the
 * background) so scrolled items refract through the bar.
 */
@Composable
fun GlassNavigationBar(
    items: List<GlassNavItem>,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val theme = LocalResolvedTheme.current
    val dark = theme.dark

    val surface = when (theme.style) {
        VisualStyle.PLAIN -> Modifier.clip(Capsule()).background(scheme.surfaceVariant)

        // Glass theme: unchanged from the original implementation.
        VisualStyle.GLASS -> {
            val tint = if (dark) {
                Color(0xFF1B1719).copy(alpha = if (supportsBlur) 0.45f else 0.80f)
            } else {
                Color.White.copy(alpha = if (supportsBlur) 0.45f else 0.82f)
            }
            Modifier.drawBackdrop(
                backdrop = backdrop,
                shape = { Capsule() },
                effects = {
                    vibrancy()
                    blur(4.dp.toPx())
                    lens(refractionHeight = 20.dp.toPx(), refractionAmount = 40.dp.toPx())
                },
                shadow = { Shadow(radius = 24.dp, color = Color.Black.copy(alpha = 0.14f)) },
                onDrawSurface = { drawRect(tint) },
            )
        }

        VisualStyle.GALACTIC -> {
            val tint = GalacticMidnight.copy(alpha = if (supportsBlur) 0.55f else 0.88f)
            Modifier
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { Capsule() },
                    effects = {
                        vibrancy()
                        blur(8.dp.toPx())
                        lens(refractionHeight = 18.dp.toPx(), refractionAmount = 32.dp.toPx())
                    },
                    shadow = { Shadow(radius = 28.dp, color = Color.Black.copy(alpha = 0.45f)) },
                    onDrawSurface = { drawRect(tint) },
                )
                .border(1.dp, GalacticRim, Capsule())
        }
    }

    Row(
        modifier
            .fillMaxWidth()
            .height(GlassNavigationBarHeight)
            .then(surface)
            .padding(6.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEach { item ->
            val galactic = theme.style == VisualStyle.GALACTIC
            val indicator by animateColorAsState(
                when {
                    !item.selected -> Color.Transparent
                    galactic -> scheme.primary.copy(alpha = 0.14f)
                    else -> scheme.primary.copy(alpha = if (dark) 0.28f else 0.16f)
                },
                label = "tabIndicator",
            )
            // Galactic: a thin cyan outline on the active tab instead of a heavy fill.
            val indicatorBorder by animateColorAsState(
                if (item.selected && galactic) scheme.primary.copy(alpha = 0.55f) else Color.Transparent,
                label = "tabIndicatorBorder",
            )
            val color by animateColorAsState(
                if (item.selected) scheme.primary else scheme.onSurface.copy(alpha = 0.75f),
                label = "tabColor",
            )
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(Capsule())
                    .background(indicator)
                    .border(1.dp, indicatorBorder, Capsule())
                    .selectable(
                        selected = item.selected,
                        onClick = item.onClick,
                        role = Role.Tab,
                        interactionSource = null,
                        indication = ripple(),
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(item.icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
                Text(
                    item.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = color,
                    fontWeight = if (item.selected) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                )
            }
        }
    }
}
