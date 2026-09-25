package com.enoluca.ytd.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import com.enoluca.ytd.data.local.datastore.AppThemeMode

/** How surfaces are rendered. Independent of light/dark. */
enum class VisualStyle {
    /** Opaque Material surfaces on a solid background (Light / Dark / System). */
    PLAIN,

    /** The Liquid Glass theme: warm gradient background, refractive glass surfaces. */
    GLASS,

    /** Galactic Glass: deep-space background, cool translucent glass, cyan/violet accents. */
    GALACTIC,
}

/** What the UI actually renders once [AppThemeMode.SYSTEM] has been resolved. */
data class ResolvedTheme(val style: VisualStyle, val dark: Boolean)

/**
 * Pure mapping from the persisted mode (+ the device's current night mode) to what we draw.
 * SYSTEM is never stored as Light/Dark — it's resolved here on every configuration, so it keeps
 * following Android after theme changes and restarts.
 */
fun resolveTheme(mode: AppThemeMode, systemInDarkTheme: Boolean): ResolvedTheme = when (mode) {
    AppThemeMode.LIGHT -> ResolvedTheme(VisualStyle.PLAIN, dark = false)
    AppThemeMode.DARK -> ResolvedTheme(VisualStyle.PLAIN, dark = true)
    AppThemeMode.SYSTEM -> ResolvedTheme(VisualStyle.PLAIN, dark = systemInDarkTheme)
    // Glass keeps its existing behavior: a light and a dark variant that follow the device.
    AppThemeMode.GLASS -> ResolvedTheme(VisualStyle.GLASS, dark = systemInDarkTheme)
    AppThemeMode.GALACTIC -> ResolvedTheme(VisualStyle.GALACTIC, dark = true)
}

val LocalResolvedTheme = staticCompositionLocalOf { ResolvedTheme(VisualStyle.PLAIN, dark = false) }
