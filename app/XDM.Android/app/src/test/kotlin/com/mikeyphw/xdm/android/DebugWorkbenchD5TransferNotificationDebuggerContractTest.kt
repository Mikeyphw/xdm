package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugWorkbenchD5TransferNotificationDebuggerContractTest {
    private val root = androidRoot()

    @Test
    fun debugWorkbenchHostsTransferNotificationDebugger() {
        val screen = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/debug/DebugWorkbenchSettingsScreen.kt")
        val card = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/debug/TransferNotificationDebuggerCard.kt")
        val model = source("app/src/main/kotlin/com/mikeyphw/xdm/android/DebugWorkbenchD5TransferNotificationModels.kt")

        assertTrue(screen.contains("TransferNotificationDebuggerCard(state)"))
        assertTrue(card.contains("Transfer + notification debugger"))
        assertTrue(card.contains("Copy transfer debugger"))
        assertTrue(model.contains("TransferNotificationDebugReporter"))
        assertTrue(model.contains("completed-file tap"))
        assertTrue(model.contains("non-exported open-file trampoline"))
    }

    @Test
    fun d5DebuggerIsReadOnlyAndDoesNotLaunchTransfersOrFiles() {
        val card = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/debug/TransferNotificationDebuggerCard.kt")
        val model = source("app/src/main/kotlin/com/mikeyphw/xdm/android/DebugWorkbenchD5TransferNotificationModels.kt")

        assertTrue(model.contains("read-only diagnostics"))
        assertTrue(model.contains("no transfer control, viewer launch, file probe, or upload"))
        assertFalse(card.contains("onClick = {}"))
        assertFalse(card.contains("runtime.pause"))
        assertFalse(card.contains("runtime.resume"))
        assertFalse(card.contains("runtime.cancel"))
        assertFalse(card.contains("startActivity"))
        assertFalse(card.contains("Intent("))
        assertFalse(model.contains("TransferForegroundService"))
        assertFalse(model.contains("OpenDownloadedFileActivity("))
    }

    @Test
    fun d5UiDoesNotRenderRawEnumNamesUrlsOrMachineValues() {
        val card = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/debug/TransferNotificationDebuggerCard.kt")
        card.lineSequence().forEachIndexed { index, line ->
            val rendersText = Regex("""\b(?:Text|XdmSupportingText|XdmMetadataText|XdmMetricText)\s*\(""").containsMatchIn(line)
            if (rendersText) {
                assertFalse("D5 card renders raw enum name at line ${index + 1}", line.contains(".name"))
                assertFalse("D5 card renders raw URL at line ${index + 1}", line.contains(".url"))
                assertFalse("D5 card renders raw machine state at line ${index + 1}", line.contains("rawJson") || line.contains("JSONObject") || line.contains("JSONArray"))
            }
        }
    }

    @Test
    fun d5ModelRedactsCopyReportAndUsesHumanLabels() {
        val model = source("app/src/main/kotlin/com/mikeyphw/xdm/android/DebugWorkbenchD5TransferNotificationModels.kt")

        assertTrue(model.contains("DebugRedactor.redactUrl"))
        assertTrue(model.contains("DebugRedactor.fingerprint"))
        assertTrue(model.contains("transferStateLabel"))
        assertTrue(model.contains("transferBackendLabel"))
        assertFalse("D5 should not copy raw destination URI", model.contains("appendLine(\"Destination: ${'$'}{download.destinationUri}"))
        assertFalse("D5 UI model should not rely on raw enum display", model.contains("state.name"))
        assertFalse("D5 UI model should not rely on raw backend display", model.contains("backend.name"))
    }

    @Test
    fun manifestRecordsD5AndNextPhase() {
        val manifest = source("PROJECT_MANIFEST.json")
        assertTrue(manifest.contains("debug_workbench_phase_d5_transfer_notification_debugger"))
        assertTrue(manifest.contains("debug_workbench_phase_d6_runtime_self_test_suite"))
    }

    private fun source(relative: String): String = File(root, relative).readText()

    private fun androidRoot(): File {
        var cursor = File(System.getProperty("user.dir") ?: ".").canonicalFile
        repeat(8) {
            if (File(cursor, "settings.gradle.kts").isFile && File(cursor, "app/src/main").isDirectory) return cursor
            cursor = cursor.parentFile ?: return@repeat
        }
        error("Unable to locate XDM Android root")
    }
}
