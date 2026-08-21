package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugWorkbenchD4BridgeAddDownloadDebuggerContractTest {
    @Test
    fun debugWorkbenchContainsBridgeAndAddDownloadDebuggerCards() {
        val root = androidRoot()
        val screen = source(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/debug/DebugWorkbenchSettingsScreen.kt")
        val card = source(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/debug/BridgeAndAddDownloadDebuggerCard.kt")
        val models = source(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/DebugWorkbenchD4DebuggerModels.kt")

        assertTrue(screen.contains("BrowserBridgeDebuggerCard(state)"))
        assertTrue(screen.contains("AddDownloadDebuggerCard(state)"))
        assertTrue(card.contains("Browser bridge debugger"))
        assertTrue(card.contains("Add Download debugger"))
        assertTrue(models.contains("BrowserBridgeDebugReporter"))
        assertTrue(models.contains("AddDownloadDebugReporter"))
        assertTrue(models.contains("DebugRedactor.redactUrl"))
    }

    @Test
    fun d4DebuggerIsCopyOnlyAndReviewOnly() {
        val root = androidRoot()
        val card = source(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/debug/BridgeAndAddDownloadDebuggerCard.kt")
        val models = source(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/DebugWorkbenchD4DebuggerModels.kt")

        assertTrue(card.contains("Copy bridge debugger"))
        assertTrue(card.contains("Copy Add debugger"))
        assertTrue(models.contains("copy-only diagnostics"))
        assertTrue(models.contains("review-only; no transfer starts"))
        assertTrue(models.contains("direct v3 media-locator handoff"))
        assertFalse(models.contains("testUri(scheme, \"capture\")"))
        assertFalse(models.contains("\$scheme://capture?v=1"))
        assertFalse(card.contains("onClick = {}"))
        assertFalse(card.contains("queueIntelligenceCoordinator"))
        assertFalse(card.contains("navigate(AppRoute"))
    }

    @Test
    fun d4UiDoesNotRenderRawEnumNamesOrRawLinks() {
        val root = androidRoot()
        val card = source(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/debug/BridgeAndAddDownloadDebuggerCard.kt")
        val model = source(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/DebugWorkbenchD4DebuggerModels.kt")

        card.lineSequence().forEachIndexed { index, line ->
            val rendersText = Regex("""\b(?:Text|XdmSupportingText|XdmMetadataText|XdmMetricText)\s*\(""").containsMatchIn(line)
            if (rendersText) {
                assertFalse("D4 card renders raw enum name at line ${index + 1}", line.contains(".name"))
                assertFalse("D4 card renders raw URL at line ${index + 1}", line.contains(".url"))
            }
        }
        assertTrue(model.contains("debugLabel()"))
        assertTrue(model.contains("DebugRedactor.redactUrl"))
    }

    @Test
    fun manifestRecordsD4AndNextPhase() {
        val manifest = source(androidRoot(), "PROJECT_MANIFEST.json")
        assertTrue(manifest.contains("debug_workbench_phase_d4_browser_bridge_add_download_debugger"))
        assertTrue(manifest.contains("debug_workbench_phase_d5_transfer_notification_debugger"))
    }

    private fun source(root: File, relative: String): String = File(root, relative).readText()

    private fun androidRoot(): File {
        var cursor = File(System.getProperty("user.dir") ?: ".").canonicalFile
        repeat(8) {
            if (File(cursor, "settings.gradle.kts").isFile && File(cursor, "app/src/main").isDirectory) return cursor
            cursor = cursor.parentFile ?: return@repeat
        }
        error("Unable to locate XDM Android root")
    }
}
