package com.enoluca.ytd.ui.glass

import android.os.Build
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.enoluca.ytd.ui.theme.GalacticCosmicBlue
import com.enoluca.ytd.ui.theme.GalacticCosmicViolet
import com.enoluca.ytd.ui.theme.GalacticElectricCyan
import com.enoluca.ytd.ui.theme.GalacticIndigo
import com.enoluca.ytd.ui.theme.LocalResolvedTheme
import com.enoluca.ytd.ui.theme.VisualStyle
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.emptyBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.RoundedRectangle
import com.kyant.shapes.RoundedRectangularShape

/**
 * Surface system for all five theme modes, built on the Kyant "backdrop" library.
 *
 * - PLAIN (Light / Dark / System): opaque Material surfaces on a solid background.
 * - GLASS: the Liquid Glass theme — warm gradient background ([AppBackground]) refracted through
 *   blur + vibrancy + lens, with a translucent white/charcoal tint.
 * - GALACTIC: deep-space background ([GalacticBackground]) refracted through cool navy glass
 *   with a thin cyan→violet rim.
 *
 * Screens refract the background layer provided through [LocalGlassBackdrop]; the floating tab
 * bar refracts a second layer that also contains the screen content (see YtdNavHost).
 *
 * Platform limits (from the library): blur needs Android 12+, lens refraction Android 13+.
 * Below that the effects are skipped, so the tints get denser to keep text readable.
 */

/** The backdrop glass elements inside a screen refract (the app background). */
val LocalGlassBackdrop = staticCompositionLocalOf<Backdrop> { emptyBackdrop() }

enum class GlassStyle {
    /** Standard card: medium tint. */
    Regular,

    /** Lighter tint so more of the background shows — headers, hints. */
    Clear,

    /** Brand-tinted glass for highlighted items (e.g. "downloads in progress"). */
    Accent,
}

internal val supportsBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
internal val supportsLens = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

/** Glass theme tint — unchanged from the original Glass implementation. */
@Composable
private fun glassTint(style: GlassStyle, dark: Boolean): Color {
    val base = if (dark) Color(0xFF1B1719) else Color.White
    // Without real blur (Android 8–11) the tint must hide more of the background to stay legible.
    val boost = if (supportsBlur) 0f else 0.25f
    return when (style) {
        GlassStyle.Regular -> base.copy(alpha = (if (dark) 0.42f else 0.50f) + boost)
        GlassStyle.Clear -> base.copy(alpha = (if (dark) 0.28f else 0.34f) + boost)
        GlassStyle.Accent -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = (if (dark) 0.55f else 0.60f) + boost)
    }
}

/** Galactic tint: deep navy glass, dense enough for comfortable reading in bright light. */
private fun galacticTint(style: GlassStyle): Color {
    val boost = if (supportsBlur) 0f else 0.25f
    return when (style) {
        GlassStyle.Regular -> GalacticCosmicBlue.copy(alpha = 0.58f + boost)
        GlassStyle.Clear -> GalacticCosmicBlue.copy(alpha = 0.40f + boost)
        GlassStyle.Accent -> GalacticIndigo.copy(alpha = 0.72f + boost)
    }
}

/** Thin cool rim for Galactic surfaces: cyan catching light at the top-left, violet at the bottom-right. */
internal val GalacticRim = Brush.linearGradient(
    0f to GalacticElectricCyan.copy(alpha = 0.40f),
    0.45f to Color.White.copy(alpha = 0.06f),
    1f to GalacticCosmicViolet.copy(alpha = 0.32f),
)

/**
 * A glass/plain container that renders according to the active theme. [cornerRadius] drives a
 * com.kyant.shapes rounded rectangle — the lens shader needs its corner radii. [selected] draws
 * an accent outline (and, in Galactic, a soft glow) so chosen items are obvious without relying
 * on color alone at the call site.
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 24.dp,
    style: GlassStyle = GlassStyle.Regular,
    selected: Boolean = false,
    backdrop: Backdrop = LocalGlassBackdrop.current,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val theme = LocalResolvedTheme.current
    val shape: RoundedRectangularShape = RoundedRectangle(cornerRadius)
    val scheme = MaterialTheme.colorScheme
    val contentColor = scheme.onSurface
    val clickModifier = if (onClick != null) {
        Modifier.clickable(
            onClickLabel = onClickLabel,
            role = Role.Button,
            interactionSource = null,
            indication = ripple(),
            onClick = onClick,
        )
    } else {
        Modifier
    }
    val glassTintColor = glassTint(style, theme.dark)
    val selectedBorder = if (selected) Modifier.border(2.dp, scheme.primary, shape) else Modifier

    val surface = when (theme.style) {
        VisualStyle.PLAIN -> modifier
            .clip(shape)
            .background(if (style == GlassStyle.Accent) scheme.primaryContainer else scheme.surfaceVariant)
            .then(selectedBorder)

        VisualStyle.GLASS -> modifier
            .drawBackdrop(
                backdrop = backdrop,
                shape = { shape },
                effects = {
                    vibrancy()
                    blur(10.dp.toPx())
                    if (supportsLens) {
                        val height = (cornerRadius * 0.6f).toPx()
                        lens(refractionHeight = height, refractionAmount = height * 2f)
                    }
                },
                shadow = { Shadow(radius = 18.dp, color = Color.Black.copy(alpha = 0.08f)) },
                onDrawSurface = { drawRect(glassTintColor) },
            )
            .clip(shape)
            .then(selectedBorder)

        VisualStyle.GALACTIC -> {
            val tint = galacticTint(style)
            modifier
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { shape },
                    effects = {
                        vibrancy()
                        blur(14.dp.toPx())
                        if (supportsLens) {
                            val height = (cornerRadius * 0.55f).toPx()
                            lens(refractionHeight = height, refractionAmount = height * 1.6f)
                        }
                    },
                    shadow = {
                        if (selected) {
                            Shadow(radius = 22.dp, color = scheme.primary.copy(alpha = 0.32f))
                        } else {
                            Shadow(radius = 20.dp, color = Color.Black.copy(alpha = 0.35f))
                        }
                    },
                    onDrawSurface = {
                        drawRect(tint)
                        // Faint top sheen: light falling on the glass from above.
                        drawRect(
                            Brush.verticalGradient(
                                0f to Color.White.copy(alpha = 0.06f),
                                0.5f to Color.Transparent,
                            ),
                        )
                    },
                )
                .clip(shape)
                .then(
                    if (selected) {
                        Modifier.border(1.5.dp, scheme.primary.copy(alpha = 0.9f), shape)
                    } else {
                        Modifier.border(1.dp, GalacticRim, shape)
                    },
                )
        }
    }

    Box(surface.then(clickModifier)) {
        CompositionLocalProvider(LocalContentColor provides contentColor) { content() }
    }
}

/**
 * Background for the active theme: solid color (plain), the warm Glass gradient, or deep space.
 * All static — drawn once and re-used by the backdrop layers.
 */
@Composable
fun AppBackground(modifier: Modifier = Modifier) {
    // Cross-fade between theme backgrounds (e.g. Glass → Galactic) instead of snapping.
    Crossfade(LocalResolvedTheme.current, modifier, animationSpec = tween(450), label = "background") { theme ->
        val fill = Modifier.fillMaxSize()
        when (theme.style) {
            VisualStyle.PLAIN -> Box(fill.background(MaterialTheme.colorScheme.background))
            VisualStyle.GLASS -> GlassGradientBackground(theme.dark, fill)
            VisualStyle.GALACTIC -> GalacticBackground(fill)
        }
    }
}

/** The Glass theme background (unchanged): calm base with soft brand-colored fields. */
@Composable
private fun GlassGradientBackground(dark: Boolean, modifier: Modifier = Modifier) {
    val base = if (dark) Color(0xFF110D11) else Color(0xFFFFF5F0)
    val blobs = if (dark) {
        listOf(
            Blob(Color(0xFFFF4D2E), 0.38f, Offset(0.05f, 0.08f), 0.75f),
            Blob(Color(0xFF7B2FBE), 0.32f, Offset(0.95f, 0.38f), 0.70f),
            Blob(Color(0xFF1F4FA8), 0.30f, Offset(0.10f, 0.78f), 0.80f),
            Blob(Color(0xFFFF8A3D), 0.22f, Offset(0.90f, 0.95f), 0.60f),
        )
    } else {
        listOf(
            Blob(Color(0xFFFF7A59), 0.55f, Offset(0.02f, 0.05f), 0.80f),
            Blob(Color(0xFFFFB3C8), 0.55f, Offset(1.0f, 0.35f), 0.75f),
            Blob(Color(0xFFFFD08A), 0.50f, Offset(0.05f, 0.75f), 0.80f),
            Blob(Color(0xFF9FD8FF), 0.40f, Offset(0.95f, 0.98f), 0.70f),
        )
    }
    Box(
        modifier.drawBehind {
            drawRect(base)
            val extent = size.maxDimension
            blobs.forEach { blob ->
                val center = Offset(size.width * blob.center.x, size.height * blob.center.y)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(blob.color.copy(alpha = blob.alpha), blob.color.copy(alpha = 0f)),
                        center = center,
                        radius = extent * blob.radius,
                    ),
                    radius = extent * blob.radius,
                    center = center,
                )
            }
        },
    )
}

internal data class Blob(val color: Color, val alpha: Float, val center: Offset, val radius: Float)
