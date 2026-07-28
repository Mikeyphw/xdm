package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserExtensionPhase40ContractTest {
    private val root = File(System.getProperty("user.dir") ?: ".").let { current ->
        if (current.name == "app") current.parentFile else current
    }

    @Test
    fun `compose and extension package use the shared token catalog`() {
        val theme = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/XdmTheme.kt").readText()
        val generator = File(root, "browser-extension/src/main/kotlin/com/mikeyphw/xdm/android/browserextension/BrowserExtensionPackageGenerator.kt").readText()
        assertTrue(theme.contains("XdmThemeTokenCatalog"))
        assertTrue(theme.contains("Color(tokens.background)"))
        assertTrue(generator.contains("XdmThemeCssGenerator.render"))
        assertFalse(theme.contains("private val XdmBackground"))
        assertFalse(generator.contains("@@BACKGROUND@@\" to"))
    }

    @Test
    fun `follow app theme exposes stale export state`() {
        val models = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/BrowserExtensionExportModels.kt").readText()
        val screen = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/settings/BrowserExtensionSettingsScreen.kt").readText()
        val preferences = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/UserPreferencesStore.kt").readText()
        assertTrue(models.contains("ThemeSelection.FollowApp"))
        assertTrue(models.contains("isThemeStale"))
        assertTrue(screen.contains("Regeneration needed"))
        assertTrue(screen.contains("preferences.resolvedTheme(state.themeMode)"))
        assertTrue(preferences.contains("ThemeSelection.entries"))
    }

    @Test
    fun `page launcher is a compact shadow dom fab`() {
        val fab = File(root, "browser-extension/src/main/extension/xdm-firefox/fab.js").readText()
        val frame = File(root, "browser-extension/src/main/extension/xdm-firefox/frame-bridge.js").readText()
        val background = File(root, "browser-extension/src/main/extension/xdm-firefox/network-observer.js").readText()
        assertTrue(fab.contains("attachShadow({ mode: \"open\" })"))
        assertTrue(fab.contains("__xdm_media_fab_host"))
        assertTrue(fab.contains("env(safe-area-inset-bottom)"))
        assertTrue(fab.contains("prefers-reduced-motion"))
        assertTrue(fab.contains("fabSizePx: 56"))
        assertTrue(fab.contains("aria-haspopup"))
        assertFalse(fab.contains("max-width:390px"))
        assertFalse(fab.contains("setInterval"))
        assertTrue(frame.contains("XdmLauncherUiV1.update"))
        assertTrue(background.contains("candidateCount: candidateStore.size(tabId)"))
    }
}
