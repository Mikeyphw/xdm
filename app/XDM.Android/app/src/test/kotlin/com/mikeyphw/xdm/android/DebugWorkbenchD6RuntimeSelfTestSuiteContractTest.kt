package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugWorkbenchD6RuntimeSelfTestSuiteContractTest {
    private val root = androidRoot()

    @Test
    fun debugWorkbenchHostsRuntimeSelfTestSuite() {
        val screen = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/debug/DebugWorkbenchSettingsScreen.kt")
        val card = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/debug/RuntimeSelfTestSuiteCard.kt")
        val model = source("app/src/main/kotlin/com/mikeyphw/xdm/android/DebugWorkbenchD6RuntimeSelfTestModels.kt")

        assertTrue(screen.contains("RuntimeSelfTestSuiteCard(state)"))
        assertTrue(card.contains("Runtime self-test suite"))
        assertTrue(card.contains("Copy self-test report"))
        assertTrue(model.contains("DebugWorkbenchRuntimeSelfTestSuite"))
        assertTrue(model.contains("mediaSnifferSmoke"))
        assertTrue(model.contains("redactionSmoke"))
    }

    @Test
    fun d6SuiteIsReadOnlyAndDoesNotLaunchOrProbe() {
        val card = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/debug/RuntimeSelfTestSuiteCard.kt")
        val model = source("app/src/main/kotlin/com/mikeyphw/xdm/android/DebugWorkbenchD6RuntimeSelfTestModels.kt")

        assertTrue(model.contains("read-only checks"))
        assertTrue(model.contains("no downloads, viewers, file probes, browser probes, or uploads"))
        assertFalse(card.contains("onClick = {}"))
        assertFalse(card.contains("startActivity"))
        assertFalse(card.contains("Intent("))
        assertFalse(model.contains("OpenDownloadedFileActivity("))
        assertFalse(model.contains("MediaPageProbe("))
        assertFalse(model.contains("TransferForegroundService"))
    }

    @Test
    fun d6UiDoesNotRenderRawEnumNamesUrlsOrMachineValues() {
        val card = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/debug/RuntimeSelfTestSuiteCard.kt")
        card.lineSequence().forEachIndexed { index, line ->
            val rendersText = Regex("""\b(?:Text|XdmSupportingText|XdmMetadataText|XdmMetricText)\s*\(""").containsMatchIn(line)
            if (rendersText) {
                assertFalse("D6 card renders raw enum name at line ${index + 1}", line.contains(".name"))
                assertFalse("D6 card renders raw URL at line ${index + 1}", line.contains(".url"))
                assertFalse("D6 card renders raw machine state at line ${index + 1}", line.contains("rawJson") || line.contains("JSONObject") || line.contains("JSONArray"))
            }
        }
    }

    @Test
    fun d6ModelRedactsCopyReportAndUsesSharedSniffer() {
        val model = source("app/src/main/kotlin/com/mikeyphw/xdm/android/DebugWorkbenchD6RuntimeSelfTestModels.kt")

        assertTrue(model.contains("DebugRedactor.redactUrl"))
        assertTrue(model.contains("DebugRedactor.redactText"))
        assertTrue(model.contains("MediaSniffingEngine"))
        assertTrue(model.contains("MediaSniffingSource.SharedText"))
        assertFalse(model.contains("MediaPageProbe("))
        assertFalse(model.contains("appendLine(\"Source: ${'$'}{download.sourceUrl}"))
    }

    @Test
    fun manifestRecordsD6AndNextPhase() {
        val manifest = source("PROJECT_MANIFEST.json")
        assertTrue(manifest.contains("debug_workbench_phase_d6_runtime_self_test_suite"))
        assertTrue(manifest.contains("debug_workbench_phase_d7_final_debug_seal"))
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
