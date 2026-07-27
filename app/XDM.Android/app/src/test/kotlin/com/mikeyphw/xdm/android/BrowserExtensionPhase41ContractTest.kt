package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserExtensionPhase41ContractTest {
    private val repo = File(System.getProperty("user.dir"))

    @Test
    fun settingsExposeTruthfulStatusRecoveryOpenAndCopyActions() {
        val source = repo.resolve("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/settings/BrowserExtensionSettingsScreen.kt").readText()
        listOf(
            "Bridge status",
            "Compatibility and recovery",
            "Open exported XPI",
            "Copy setup instructions",
            "Redacted diagnostics",
            "Refresh status",
        ).forEach { assertTrue("Missing settings contract: $it", source.contains(it)) }
    }

    @Test
    fun diagnosticsArePersistedWithoutRawDeepLinksOrHeaders() {
        val preferences = repo.resolve("app/src/main/kotlin/com/mikeyphw/xdm/android/UserPreferencesStore.kt").readText()
        val diagnostics = repo.resolve("app/src/main/kotlin/com/mikeyphw/xdm/android/BrowserBridgeIntegrationModels.kt").readText()
        assertTrue(preferences.contains("BrowserBridgeLastAcceptedSummary"))
        assertTrue(preferences.contains("BrowserBridgeLastRejectedSummary"))
        assertTrue(preferences.contains("BrowserBridgeLastGenerationPhase"))
        assertTrue(diagnostics.contains("BrowserBridgeDiagnosticsRedactor"))
        assertFalse(preferences.contains("browser_bridge_raw_deep_link"))
        assertFalse(preferences.contains("browser_bridge_cookie"))
        assertFalse(preferences.contains("browser_bridge_authorization"))
    }

    @Test
    fun integrationHealthCoversSchemeSafChecksumVariantAndInterruptedGeneration() {
        val manager = repo.resolve("app/src/main/kotlin/com/mikeyphw/xdm/android/BrowserExtensionExportManager.kt").readText()
        val exportModels = repo.resolve("app/src/main/kotlin/com/mikeyphw/xdm/android/BrowserExtensionExportModels.kt").readText()
        val contract = manager + "\n" + exportModels
        listOf(
            "resolveActivity",
            "PermissionRevoked",
            "ExportMissing",
            "ChecksumMismatch",
            "Variant",
            "previous XPI generation was interrupted",
        ).forEach { assertTrue("Missing health contract: $it", contract.contains(it, ignoreCase = true)) }
    }

    @Test
    fun customSchemeRejectionsDoNotFallThroughToGenericViewIntake() {
        val activity = repo.resolve("app/src/main/kotlin/com/mikeyphw/xdm/android/MainActivity.kt").readText()
        assertTrue(activity.contains("parseDetailed"))
        assertTrue(activity.contains("is XdmBrowserDeepLinkParseResult.Rejected -> return"))
        assertTrue(activity.indexOf("parseDetailed") < activity.indexOf("sharedText(incoming)"))
    }

    @Test
    fun phaseDoesNotAddTopLevelRouteOrWebViewRuntime() {
        val settingsPanel = repo.resolve("app/src/main/kotlin/com/mikeyphw/xdm/android/SettingsPanel.kt").readText()
        assertTrue(settingsPanel.contains("BrowserExtension"))
        val appRoutes = repo.resolve("app/src/main/kotlin/com/mikeyphw/xdm/android/AppRoute.kt")
        if (appRoutes.exists()) {
            assertFalse(appRoutes.readText().contains("BrowserExtension"))
        }
        val changedSources = listOf(
            "BrowserBridgeIntegrationModels.kt",
            "BrowserExtensionExportManager.kt",
            "BrowserExtensionExportModels.kt",
        ).joinToString("\n") { repo.resolve("app/src/main/kotlin/com/mikeyphw/xdm/android/$it").readText() }
        assertFalse(changedSources.contains("WebView"))
        assertFalse(changedSources.contains("setJavaScriptEnabled"))
    }
}
