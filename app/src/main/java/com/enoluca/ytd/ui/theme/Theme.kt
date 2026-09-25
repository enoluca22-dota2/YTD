package com.enoluca.ytd.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.enoluca.ytd.data.local.datastore.AppSettings
import com.enoluca.ytd.data.local.datastore.SettingsDataStore

private val LightColors = lightColorScheme(
    primary = YtdOrange,
    onPrimary = Color.White,
    primaryContainer = YtdOrangeContainerLight,
    onPrimaryContainer = Color(0xFF3A0E00),
    secondary = TierHigh,
    // Used by the navigation-bar indicator, tonal buttons and hint cards; without it Material
    // falls back to its default lavender, which clashes with the orange brand.
    secondaryContainer = Color(0xFFFFE6DC),
    onSecondaryContainer = Color(0xFF3A0E00),
    surfaceContainer = Color(0xFFF7F1EF),
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightSurface,
    onSurface = LightOnBackground,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = Color(0xFF4E4542),
    outline = LightOutline,
    error = ErrorRed,
)

private val DarkColors = darkColorScheme(
    primary = YtdOrangeLight,
    onPrimary = Color(0xFF5C1F0E),
    primaryContainer = YtdOrangeContainerDark,
    onPrimaryContainer = YtdOrangeContainerLight,
    secondary = TierHigh,
    secondaryContainer = Color(0xFF47281F),
    onSecondaryContainer = YtdOrangeContainerLight,
    surfaceContainer = Color(0xFF221E22),
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnBackground,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = Color(0xFFCFC7CB),
    outline = DarkOutline,
    error = Color(0xFFFFB4AB),
)

/** Galactic Glass: cyan is the action color, violet the supporting accent, magenta-rose for errors. */
private val GalacticColors = darkColorScheme(
    primary = GalacticElectricCyan,
    onPrimary = Color(0xFF002A33),
    primaryContainer = GalacticIndigo,
    onPrimaryContainer = GalacticStarlight,
    secondary = GalacticCosmicViolet,
    onSecondary = Color(0xFF1C0B40),
    secondaryContainer = GalacticNebulaViolet,
    onSecondaryContainer = GalacticStarlight,
    tertiary = GalacticNebulaMagenta,
    background = GalacticDeepSpace,
    onBackground = GalacticStarlight,
    surface = GalacticMidnight,
    onSurface = GalacticStarlight,
    surfaceVariant = GalacticCosmicBlue,
    onSurfaceVariant = GalacticMist,
    surfaceContainer = Color(0xFF0D1A33),
    surfaceContainerHigh = Color(0xFF12213F),
    surfaceContainerLow = Color(0xFF0A1428),
    outline = Color(0xFF2E3D63),
    outlineVariant = Color(0xFF1E2A48),
    error = GalacticErrorRose,
    onError = Color(0xFF3D0014),
    errorContainer = Color(0xFF3A0F24),
    onErrorContainer = Color(0xFFFFD9E2),
)

private fun colorSchemeFor(theme: ResolvedTheme): ColorScheme = when {
    theme.style == VisualStyle.GALACTIC -> GalacticColors
    theme.dark -> DarkColors
    else -> LightColors
}

@Composable
fun YtdTheme(
    settingsDataStore: SettingsDataStore,
    content: @Composable () -> Unit,
) {
    // Null until DataStore has been read once, so e.g. a Galactic user never sees a Light flash;
    // the window's own background shows for those few milliseconds.
    val settings: AppSettings? by settingsDataStore.settings.collectAsStateWithLifecycle(initialValue = null)
    val loaded = settings ?: return

    // isSystemInDarkTheme() reads the current Configuration, so SYSTEM (and GLASS) re-resolve
    // automatically when Android switches between light and dark, including after restarts.
    val resolved = resolveTheme(loaded.themeMode, isSystemInDarkTheme())

    SystemBarAppearance(darkIcons = !resolved.dark)

    MaterialTheme(
        colorScheme = animatedColorScheme(colorSchemeFor(resolved)),
        typography = YtdTypography,
    ) {
        CompositionLocalProvider(LocalResolvedTheme provides resolved, content = content)
    }
}

/** Status/navigation bar icons follow the app theme, not the device (e.g. Galactic on a light phone). */
@Composable
private fun SystemBarAppearance(darkIcons: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    SideEffect {
        val window = view.context.findActivity()?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = darkIcons
            isAppearanceLightNavigationBars = darkIcons
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Cross-fades the color roles that cover large areas or carry text when the theme changes
 * (~400 ms). Cheap: a handful of color animations, no layout work.
 */
@Composable
private fun animatedColorScheme(target: ColorScheme): ColorScheme {
    val spec = tween<Color>(durationMillis = 400)

    @Composable
    fun animate(color: Color, label: String) = animateColorAsState(color, spec, label = label).value

    return target.copy(
        primary = animate(target.primary, "primary"),
        onPrimary = animate(target.onPrimary, "onPrimary"),
        primaryContainer = animate(target.primaryContainer, "primaryContainer"),
        onPrimaryContainer = animate(target.onPrimaryContainer, "onPrimaryContainer"),
        secondaryContainer = animate(target.secondaryContainer, "secondaryContainer"),
        background = animate(target.background, "background"),
        onBackground = animate(target.onBackground, "onBackground"),
        surface = animate(target.surface, "surface"),
        onSurface = animate(target.onSurface, "onSurface"),
        surfaceVariant = animate(target.surfaceVariant, "surfaceVariant"),
        onSurfaceVariant = animate(target.onSurfaceVariant, "onSurfaceVariant"),
        outline = animate(target.outline, "outline"),
        error = animate(target.error, "error"),
        errorContainer = animate(target.errorContainer, "errorContainer"),
        onErrorContainer = animate(target.onErrorContainer, "onErrorContainer"),
    )
}
