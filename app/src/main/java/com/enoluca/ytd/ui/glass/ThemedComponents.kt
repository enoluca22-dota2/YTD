package com.enoluca.ytd.ui.glass

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.enoluca.ytd.ui.theme.GalacticAuroraBlue
import com.enoluca.ytd.ui.theme.GalacticAuroraGreen
import com.enoluca.ytd.ui.theme.GalacticCosmicBlue
import com.enoluca.ytd.ui.theme.GalacticCosmicViolet
import com.enoluca.ytd.ui.theme.GalacticElectricCyan
import com.enoluca.ytd.ui.theme.GalacticErrorRose
import com.enoluca.ytd.ui.theme.LocalResolvedTheme
import com.enoluca.ytd.ui.theme.SuccessGreen
import com.enoluca.ytd.ui.theme.VisualStyle

// ---------------------------------------------------------------------------------------------
// Primary action button
// ---------------------------------------------------------------------------------------------

enum class ActionState { Idle, Loading, Success, Error }

/**
 * The app's primary call to action ("Find video", "Download now"). Same API in every theme:
 *  - Light/Dark/Glass: solid brand button (as before) with a subtle press scale.
 *  - Galactic: glass pill with a cyan → aurora-blue → violet fill, a top sheen and a soft glow.
 * States always pair color with an icon and a label, so they never rely on color alone.
 */
@Composable
fun PrimaryActionButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    state: ActionState = ActionState.Idle,
    loadingText: String = text,
    successText: String = text,
    errorText: String = text,
) {
    val theme = LocalResolvedTheme.current
    val scheme = MaterialTheme.colorScheme
    val galactic = theme.style == VisualStyle.GALACTIC
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, spring(stiffness = 600f), label = "pressScale")
    val clickable = enabled && state != ActionState.Loading
    // Loading keeps the "active" look (it's busy, not unavailable) so its label stays readable.
    val active = enabled || state == ActionState.Loading
    val shape = RoundedCornerShape(if (galactic) 26.dp else 16.dp)

    val successColor = if (galactic) GalacticAuroraGreen else SuccessGreen
    val container by animateColorAsState(
        when {
            !active -> scheme.onSurface.copy(alpha = 0.12f)
            state == ActionState.Success -> successColor
            state == ActionState.Error -> scheme.error
            else -> scheme.primary
        },
        label = "actionContainer",
    )
    val contentColor = when {
        !active -> scheme.onSurface.copy(alpha = 0.38f)
        galactic -> Color(0xFF041018)
        state == ActionState.Error -> scheme.onError
        else -> scheme.onPrimary
    }

    val background = if (galactic && active && (state == ActionState.Idle || state == ActionState.Loading)) {
        Modifier
            .shadow(18.dp, shape, ambientColor = GalacticCosmicViolet, spotColor = GalacticElectricCyan)
            .clip(shape)
            .background(
                Brush.horizontalGradient(listOf(GalacticElectricCyan, GalacticAuroraBlue, GalacticCosmicViolet)),
            )
            // Glassy top sheen.
            .background(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.28f), 0.55f to Color.Transparent))
            .border(1.dp, Color.White.copy(alpha = 0.35f), shape)
    } else {
        Modifier.clip(shape).background(container)
    }

    Box(
        modifier
            .heightIn(min = 54.dp)
            .scale(scale)
            .then(background)
            .clickable(
                enabled = clickable,
                role = Role.Button,
                interactionSource = interaction,
                indication = ripple(color = contentColor),
                onClick = onClick,
            )
            .semantics {
                stateDescription = when (state) {
                    ActionState.Loading -> "In progress"
                    ActionState.Success -> "Done"
                    ActionState.Error -> "Failed"
                    ActionState.Idle -> if (enabled) "" else "Unavailable"
                }
            }
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = state,
            transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
            label = "actionContent",
        ) { current ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                when (current) {
                    ActionState.Loading -> CircularProgressIndicator(
                        Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = contentColor,
                    )
                    ActionState.Success -> Icon(Icons.Filled.CheckCircle, null, tint = contentColor)
                    ActionState.Error -> Icon(Icons.Filled.ErrorOutline, null, tint = contentColor)
                    ActionState.Idle -> Icon(icon, null, tint = contentColor)
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    when (current) {
                        ActionState.Loading -> loadingText
                        ActionState.Success -> successText
                        ActionState.Error -> errorText
                        ActionState.Idle -> text
                    },
                    color = contentColor,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Progress
// ---------------------------------------------------------------------------------------------

/**
 * Download progress bar. [progress] in 0..1 (real progress only), or null for indeterminate.
 * Changes between real values are animated; the bar never moves on its own. Glass draws a
 * frosted translucent track with a primary gradient fill, Galactic an aurora gradient; both with
 * a bright leading edge. Light/Dark keep the Material bar.
 */
@Composable
fun GlassProgressBar(progress: Float?, modifier: Modifier = Modifier, muted: Boolean = false) {
    val theme = LocalResolvedTheme.current
    val scheme = MaterialTheme.colorScheme
    when (theme.style) {
        VisualStyle.PLAIN -> {
            val color = if (muted) scheme.outline else scheme.primary
            if (progress == null) {
                LinearProgressIndicator(modifier.fillMaxWidth(), color = color)
            } else {
                val animated by animateFloatAsState(progress.coerceIn(0f, 1f), tween(400), label = "progress")
                LinearProgressIndicator(progress = { animated }, modifier = modifier.fillMaxWidth(), color = color)
            }
        }
        VisualStyle.GLASS -> StyledProgressBar(
            progress = progress,
            modifier = modifier,
            trackColor = scheme.onSurface.copy(alpha = 0.10f),
            borderColor = Color.White.copy(alpha = 0.22f),
            fillColors = if (muted) listOf(scheme.outline, scheme.outline) else listOf(scheme.primary.copy(alpha = 0.85f), scheme.primary, scheme.tertiary),
        )
        VisualStyle.GALACTIC -> StyledProgressBar(
            progress = progress,
            modifier = modifier,
            trackColor = GalacticCosmicBlue.copy(alpha = 0.9f),
            borderColor = Color.White.copy(alpha = 0.08f),
            fillColors = if (muted) listOf(scheme.outline, scheme.outline) else listOf(GalacticElectricCyan, GalacticAuroraBlue, GalacticCosmicViolet),
        )
    }
}

@Composable
private fun StyledProgressBar(
    progress: Float?,
    modifier: Modifier,
    trackColor: Color,
    borderColor: Color,
    fillColors: List<Color>,
) {
    val track = RoundedCornerShape(50)
    Box(
        modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(track)
            .background(trackColor)
            .border(0.5.dp, borderColor, track),
    ) {
        val fill = Brush.horizontalGradient(fillColors)
        if (progress == null) {
            // Indeterminate: a short segment sweeping across; it shows activity, not an amount.
            val sweep by rememberInfiniteTransition(label = "sweep").animateFloat(
                initialValue = -0.35f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(1300, easing = LinearEasing)),
                label = "sweepOffset",
            )
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth()
                    .drawWithContent {
                        val segment = size.width * 0.35f
                        val start = size.width * sweep
                        drawRoundRect(
                            fill,
                            topLeft = Offset(start, 0f),
                            size = androidx.compose.ui.geometry.Size(segment, size.height),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2),
                        )
                    },
            )
        } else {
            // Eases between the real values reported ~5×/s, so it reads as continuous.
            val animated by animateFloatAsState(progress.coerceIn(0f, 1f), tween(450), label = "progress")
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(animated)
                    .clip(track)
                    .background(fill)
                    // Bright leading edge.
                    .drawWithContent {
                        drawContent()
                        drawCircle(
                            Brush.radialGradient(
                                listOf(Color.White.copy(alpha = 0.9f), Color.Transparent),
                                center = Offset(size.width, size.height / 2),
                                radius = size.height * 1.4f,
                            ),
                            radius = size.height * 1.4f,
                            center = Offset(size.width, size.height / 2),
                        )
                    },
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Loading skeletons
// ---------------------------------------------------------------------------------------------

/**
 * Soft shimmer for skeleton placeholders. The infinite transition only exists while the
 * placeholder is in the composition, so nothing animates once real content arrives.
 */
fun Modifier.shimmer(): Modifier = composed {
    val base = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val highlight = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
    val x by rememberInfiniteTransition(label = "shimmer").animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
        label = "shimmerX",
    )
    drawWithContent {
        drawRect(
            Brush.linearGradient(
                listOf(base, highlight, base),
                start = Offset(size.width * x - size.width, 0f),
                end = Offset(size.width * x, size.height),
            ),
        )
    }
}

/** Placeholder row shaped like a format card, shown while metadata is being fetched. */
@Composable
fun SkeletonRow(modifier: Modifier = Modifier) {
    GlassSurface(modifier.fillMaxWidth(), cornerRadius = 20.dp, style = GlassStyle.Clear) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(width = 72.dp, height = 44.dp).clip(RoundedCornerShape(10.dp)).shimmer())
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.fillMaxWidth(0.7f).height(14.dp).clip(RoundedCornerShape(7.dp)).shimmer())
                Box(Modifier.fillMaxWidth(0.4f).height(10.dp).clip(RoundedCornerShape(5.dp)).shimmer())
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Toasts (snackbars)
// ---------------------------------------------------------------------------------------------

enum class ToastKind { Info, Success, Error }

/** Snackbar payload carrying a [ToastKind] so the host can pick icon + accent. */
data class YtdToast(
    override val message: String,
    val kind: ToastKind = ToastKind.Info,
    override val actionLabel: String? = null,
    override val withDismissAction: Boolean = false,
    override val duration: SnackbarDuration = SnackbarDuration.Short,
) : SnackbarVisuals

suspend fun SnackbarHostState.showToast(message: String, kind: ToastKind = ToastKind.Info, actionLabel: String? = null) =
    showSnackbar(YtdToast(message, kind, actionLabel))

/** Snackbar host that renders every message as a themed glass toast. */
@Composable
fun GlassSnackbarHost(hostState: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(hostState, modifier) { data -> GlassToast(data) }
}

@Composable
private fun GlassToast(data: SnackbarData) {
    val theme = LocalResolvedTheme.current
    val scheme = MaterialTheme.colorScheme
    val kind = (data.visuals as? YtdToast)?.kind ?: ToastKind.Info
    val galactic = theme.style == VisualStyle.GALACTIC
    val (icon, accent) = when (kind) {
        ToastKind.Success -> Icons.Filled.CheckCircle to (if (galactic) GalacticAuroraGreen else SuccessGreen)
        ToastKind.Error -> Icons.Filled.ErrorOutline to (if (galactic) GalacticErrorRose else scheme.error)
        ToastKind.Info -> Icons.Filled.Info to scheme.primary
    }
    val shape = RoundedCornerShape(18.dp)
    val container = when (theme.style) {
        VisualStyle.PLAIN -> scheme.inverseSurface
        VisualStyle.GLASS -> if (theme.dark) Color(0xF01B1719) else Color(0xF2FFFFFF)
        VisualStyle.GALACTIC -> Color(0xF20B1730)
    }
    val textColor = if (theme.style == VisualStyle.PLAIN) scheme.inverseOnSurface else scheme.onSurface
    Row(
        Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .fillMaxWidth()
            .shadow(12.dp, shape)
            .clip(shape)
            .background(container)
            .then(if (galactic) Modifier.border(1.dp, GalacticRim, shape) else Modifier)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = accent)
        Spacer(Modifier.width(12.dp))
        Text(data.visuals.message, color = textColor, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        data.visuals.actionLabel?.let { label ->
            TextButton(onClick = { data.performAction() }) { Text(label, color = accent) }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Sheets & dialogs
// ---------------------------------------------------------------------------------------------

/**
 * Container color for bottom sheets and dialogs. They live in their own window, so they can't
 * refract the app background — Galactic uses near-opaque cosmic glass instead.
 */
@Composable
fun sheetContainerColor(): Color {
    val theme = LocalResolvedTheme.current
    return when (theme.style) {
        // Fully opaque: the list behind a sheet must not ghost through its text.
        VisualStyle.GALACTIC -> Color(0xFF0B1730)
        else -> MaterialTheme.colorScheme.surfaceContainer
    }
}

/** Small clickable glass pill used for quick choices (categories etc.). */
@Composable
fun GlassChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val galactic = LocalResolvedTheme.current.style == VisualStyle.GALACTIC
    val shape = RoundedCornerShape(50)
    val fill by animateColorAsState(
        when {
            selected && galactic -> scheme.primary.copy(alpha = 0.16f)
            selected -> scheme.secondaryContainer
            else -> Color.Transparent
        },
        label = "chipFill",
    )
    val border = when {
        selected -> scheme.primary
        galactic -> scheme.outline
        else -> scheme.outline
    }
    Row(
        modifier
            .heightIn(min = 40.dp)
            .clip(shape)
            .background(fill)
            .border(1.dp, border, shape)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { stateDescription = if (selected) "Selected" else "Not selected" }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected) {
            Icon(Icons.Filled.CheckCircle, null, tint = scheme.primary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) scheme.primary else scheme.onSurface, maxLines = 1)
    }
}
