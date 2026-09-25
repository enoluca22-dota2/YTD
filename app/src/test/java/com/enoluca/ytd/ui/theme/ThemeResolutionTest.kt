package com.enoluca.ytd.ui.theme

import com.enoluca.ytd.data.local.datastore.AppThemeMode
import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeResolutionTest {

    @Test
    fun `there are exactly five theme modes`() {
        assertEquals(
            listOf(AppThemeMode.LIGHT, AppThemeMode.DARK, AppThemeMode.GLASS, AppThemeMode.GALACTIC, AppThemeMode.SYSTEM),
            AppThemeMode.entries.toList(),
        )
    }

    @Test
    fun `system follows the device and is never pinned`() {
        assertEquals(ResolvedTheme(VisualStyle.PLAIN, dark = false), resolveTheme(AppThemeMode.SYSTEM, systemInDarkTheme = false))
        assertEquals(ResolvedTheme(VisualStyle.PLAIN, dark = true), resolveTheme(AppThemeMode.SYSTEM, systemInDarkTheme = true))
    }

    @Test
    fun `light and dark ignore the device setting`() {
        for (systemDark in listOf(false, true)) {
            assertEquals(ResolvedTheme(VisualStyle.PLAIN, dark = false), resolveTheme(AppThemeMode.LIGHT, systemDark))
            assertEquals(ResolvedTheme(VisualStyle.PLAIN, dark = true), resolveTheme(AppThemeMode.DARK, systemDark))
        }
    }

    @Test
    fun `glass keeps its light and dark variants`() {
        assertEquals(ResolvedTheme(VisualStyle.GLASS, dark = false), resolveTheme(AppThemeMode.GLASS, systemInDarkTheme = false))
        assertEquals(ResolvedTheme(VisualStyle.GLASS, dark = true), resolveTheme(AppThemeMode.GLASS, systemInDarkTheme = true))
    }

    @Test
    fun `galactic is always dark`() {
        assertEquals(ResolvedTheme(VisualStyle.GALACTIC, dark = true), resolveTheme(AppThemeMode.GALACTIC, systemInDarkTheme = false))
        assertEquals(ResolvedTheme(VisualStyle.GALACTIC, dark = true), resolveTheme(AppThemeMode.GALACTIC, systemInDarkTheme = true))
    }

    @Test
    fun `previously stored values still parse`() {
        // DataStore stores the enum name; installs from before this change hold one of these.
        listOf("LIGHT", "DARK", "SYSTEM").forEach { AppThemeMode.valueOf(it) }
    }
}
