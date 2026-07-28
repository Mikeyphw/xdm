package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserExtensionPhase39ContractTest {
    private val root = File(System.getProperty("user.dir") ?: ".").let { current ->
        if (current.name == "app") current.parentFile else current
    }

    @Test
    fun `browser extension remains a settings subpanel`() {
        val panel = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/SettingsPanel.kt").readText()
        val routes = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/AppRoute.kt").readText()
        assertTrue(panel.contains("BrowserExtension(\"Browser extension\")"))
        assertFalse(routes.contains("BrowserExtension"))
    }

    @Test
    fun `saf export persists directory and verified metadata`() {
        val preferences = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/UserPreferencesStore.kt").readText()
        val manager = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/BrowserExtensionExportManager.kt").readText()
        for (token in listOf(
            "browser_extension_export_tree_uri",
            "browser_extension_default_target",
            "browser_extension_last_export_sha256",
            "browser_extension_last_export_epoch_ms",
        )) assertTrue(preferences.contains(token))
        assertTrue(manager.contains("takePersistableUriPermission"))
        assertTrue(manager.contains("BrowserExtensionExportTransaction"))
        assertTrue(manager.contains("BrowserExtensionHash::digest"))
    }
}
