package com.mikeyphw.xdm.android.browserextension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XdmThemeTokensTest {
    @Test
    fun `dark tokens match the XDM app palette`() {
        val tokens = XdmThemeTokenCatalog.Dark
        assertEquals(0xFF090B0F, tokens.background)
        assertEquals(0xFF101318, tokens.surface)
        assertEquals(0xFF7DB8FF, tokens.primary)
        assertEquals(0xFF183B59, tokens.primaryContainer)
        assertEquals(0xFFE8EDF5, tokens.text)
        assertEquals(0xFF252B35, tokens.separator)
        assertTrue(tokens.fabSizePx >= 48)
        assertEquals(18, tokens.fabCornerRadiusPx)
    }

    @Test
    fun `amoled changes structural surfaces without drifting accents`() {
        val dark = XdmThemeTokenCatalog.Dark
        val amoled = XdmThemeTokenCatalog.Amoled
        assertEquals(0xFF000000, amoled.background)
        assertEquals(0xFF000000, amoled.surface)
        assertFalse(dark.background == amoled.background)
        assertEquals(dark.primary, amoled.primary)
        assertEquals(dark.primaryContainer, amoled.primaryContainer)
        assertEquals(dark.text, amoled.text)
    }

    @Test
    fun `css generator renders palette shape and motion tokens`() {
        val template = """
            :root {
              --bg: @@BACKGROUND@@;
              --surface: @@SURFACE@@;
              --primary: @@PRIMARY@@;
              --on-primary-container: @@ON_PRIMARY_CONTAINER@@;
              --separator: @@SEPARATOR@@;
              --fab-size: @@FAB_SIZE@@px;
              --fab-radius: @@FAB_RADIUS@@px;
              --motion: @@MOTION_STANDARD@@ms;
              --mode: @@THEME_MODE@@;
            }
        """.trimIndent()
        val dark = XdmThemeCssGenerator.render(template, BrowserExtensionSourceContract.ThemeMode.Dark)
        val amoled = XdmThemeCssGenerator.render(template, BrowserExtensionSourceContract.ThemeMode.Amoled)
        assertTrue(dark.contains("--bg: #090B0F"))
        assertTrue(dark.contains("--primary: #7DB8FF"))
        assertTrue(dark.contains("--on-primary-container: #D2E8FF"))
        assertTrue(dark.contains("--fab-size: 56px"))
        assertTrue(dark.contains("--fab-radius: 18px"))
        assertTrue(dark.contains("--motion: 220ms"))
        assertTrue(amoled.contains("--bg: #000000"))
        assertFalse(dark == amoled)
        assertFalse("@@" in dark)
        assertFalse("@@" in amoled)
    }

    @Test
    fun `follow app selection resolves to concrete package theme`() {
        val selection = BrowserExtensionSourceContract.ThemeSelection.FollowApp
        assertEquals(BrowserExtensionSourceContract.ThemeMode.Dark, selection.resolve(BrowserExtensionSourceContract.ThemeMode.Dark))
        assertEquals(BrowserExtensionSourceContract.ThemeMode.Amoled, selection.resolve(BrowserExtensionSourceContract.ThemeMode.Amoled))
        assertEquals(
            BrowserExtensionSourceContract.ThemeMode.Dark,
            BrowserExtensionSourceContract.ThemeSelection.Dark.resolve(BrowserExtensionSourceContract.ThemeMode.Amoled),
        )
    }
}
