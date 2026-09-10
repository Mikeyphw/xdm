package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Ux11BrowserExternalSurfacePolishContractTest {
    private val root = androidRoot()

    @Test
    fun browserIntegrationIsProductFirstWithTechnicalDetailsOnDemand() {
        val screen = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/settings/BrowserExtensionSettingsScreen.kt")
        assertTrue(screen.contains("Firefox extension"))
        assertTrue(screen.contains("Test connection"))
        assertTrue(screen.contains("Install / Update"))
        assertTrue(screen.contains("Technical details"))
        assertTrue(screen.contains("DocumentsContract.getTreeDocumentId"))
        assertTrue(screen.contains("Regenerate when app theme changes"))
        assertTrue(screen.contains("Preview"))
        assertTrue(screen.contains("if (showTechnicalDetails)"))
    }

    @Test
    fun browserAutoRegenerationIsOptInAndPersisted() {
        val models = source("app/src/main/kotlin/com/mikeyphw/xdm/android/BrowserExtensionExportModels.kt")
        val preferences = source("app/src/main/kotlin/com/mikeyphw/xdm/android/UserPreferencesStore.kt")
        val viewModel = source("app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt")
        assertTrue(models.contains("autoRegenerateOnThemeChange: Boolean = false"))
        assertTrue(preferences.contains("BrowserExtensionAutoRegenerateTheme"))
        assertTrue(preferences.contains("setBrowserExtensionAutoRegenerateOnThemeChange"))
        assertTrue(viewModel.contains("setBrowserExtensionAutoRegenerateOnThemeChange"))
    }

    @Test
    fun normalSettingsSummaryDoesNotLeadWithHashesOrInternalSchemes() {
        val settings = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/settings/SettingsScreen.kt")
        val external = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/settings/AdvancedDownloadSettingsScreen.kt")
        assertTrue(settings.contains("Firefox extension connected • handoff ready"))
        assertFalse(settings.contains("verified SHA-256 export"))
        assertTrue(external.contains("Integration status"))
        assertTrue(external.contains("Check Termux"))
    }

    private fun source(relative: String): String = File(root, relative).readText()

    private fun androidRoot(): File = generateSequence(File(requireNotNull(System.getProperty("user.dir"))).canonicalFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile && File(it, "app/src/main").isDirectory }
}
