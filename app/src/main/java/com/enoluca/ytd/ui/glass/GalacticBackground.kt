package com.enoluca.ytd.ui.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import com.enoluca.ytd.ui.theme.GalacticAuroraBlue
import com.enoluca.ytd.ui.theme.GalacticCosmicBlue
import com.enoluca.ytd.ui.theme.GalacticCosmicViolet
import com.enoluca.ytd.ui.theme.GalacticDeepSpace
import com.enoluca.ytd.ui.theme.GalacticElectricCyan
import com.enoluca.ytd.ui.theme.GalacticIndigo
import com.enoluca.ytd.ui.theme.GalacticMidnight
import com.enoluca.ytd.ui.theme.GalacticNebulaMagenta
import com.enoluca.ytd.ui.theme.GalacticNebulaViolet
import kotlin.random.Random

/**
 * Deep-space backdrop for Galactic Glass, drawn procedurally:
 *  1. vertical Midnight → Deep Space → Cosmic Blue base,
 *  2. two large Indigo / Nebula-Violet nebula clouds,
 *  3. a faint, wide aurora band (cyan → blue → violet),
 *  4. two small blurred light sources and a hint of magenta,
 *  5. a sparse star field.
 *
 * Everything is computed once per size in drawWithCache and never animates, so it costs a single
 * draw — the backdrop layers re-use it for every glass surface. No bitmaps, no GPU loops.
 */
@Composable
fun GalacticBackground(modifier: Modifier = Modifier) {
    Box(
        modifier.drawWithCache {
            val w = size.width
            val h = size.height
            val extent = size.maxDimension

            val base = Brush.verticalGradient(
                0f to GalacticMidnight,
                0.45f to GalacticDeepSpace,
                1f to GalacticCosmicBlue,
            )

            fun glow(color: Color, alpha: Float, cx: Float, cy: Float, r: Float) = Triple(
                Brush.radialGradient(
                    listOf(color.copy(alpha = alpha), color.copy(alpha = alpha * 0.35f), Color.Transparent),
                    center = Offset(w * cx, h * cy),
                    radius = extent * r,
                ),
                Offset(w * cx, h * cy),
                extent * r,
            )

            val nebulae = listOf(
                glow(GalacticIndigo, 0.95f, 0.88f, 0.12f, 0.70f),
                glow(GalacticNebulaViolet, 0.85f, 0.05f, 0.55f, 0.65f),
                glow(GalacticNebulaMagenta, 0.10f, 0.78f, 0.62f, 0.30f),
                glow(GalacticCosmicBlue, 0.90f, 0.40f, 0.98f, 0.60f),
            )
            // Blurred light sources: tiny, soft, low alpha.
            val lights = listOf(
                glow(GalacticElectricCyan, 0.22f, 0.18f, 0.20f, 0.10f),
                glow(GalacticCosmicViolet, 0.20f, 0.85f, 0.80f, 0.12f),
            )
            // Aurora: a radial glow squashed into a long diagonal band.
            val auroraCenter = Offset(w * 0.55f, h * 0.32f)
            val aurora = Brush.radialGradient(
                0f to GalacticElectricCyan.copy(alpha = 0.16f),
                0.45f to GalacticAuroraBlue.copy(alpha = 0.10f),
                0.75f to GalacticCosmicViolet.copy(alpha = 0.05f),
                1f to Color.Transparent,
                center = auroraCenter,
                radius = extent * 0.55f,
            )

            // Fixed seed: the same sky every time, no shimmer between frames.
            val random = Random(20260924)
            val density = this.density
            val stars = List(STAR_COUNT) {
                Star(
                    position = Offset(random.nextFloat() * w, random.nextFloat() * h),
                    radius = (0.4f + random.nextFloat() * random.nextFloat() * 1.3f) * density,
                    alpha = 0.18f + random.nextFloat() * 0.6f,
                    tint = if (random.nextFloat() < 0.15f) GalacticElectricCyan else Color.White,
                )
            }

            onDrawBehind {
                drawRect(base)
                nebulae.forEach { (brush, center, radius) -> drawCircle(brush, radius, center) }
                // Tilted and squashed into a long band so it reads as aurora, not a spotlight.
                rotate(degrees = -14f, pivot = auroraCenter) {
                    scale(scaleX = 1.9f, scaleY = 0.42f, pivot = auroraCenter) {
                        drawCircle(aurora, extent * 0.55f, auroraCenter)
                    }
                }
                lights.forEach { (brush, center, radius) -> drawCircle(brush, radius, center) }
                stars.forEach { star ->
                    drawCircle(star.tint.copy(alpha = star.alpha), star.radius, star.position)
                }
            }
        },
    )
}

private const val STAR_COUNT = 110

private data class Star(val position: Offset, val radius: Float, val alpha: Float, val tint: Color)
