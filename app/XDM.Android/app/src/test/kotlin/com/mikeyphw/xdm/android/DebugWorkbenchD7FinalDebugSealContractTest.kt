package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugWorkbenchD7FinalDebugSealContractTest {
    private val root = androidRoot()

    @Test
    fun manifestRecordsCompleteDebugWorkbenchSeal() {
        val manifest = source("PROJECT_MANIFEST.json")

        assertTrue(manifest.contains("debug_workbench_phase_d7_final_debug_seal"))
        assertTrue(manifest.contains("\"overlay\": \"xdm_android_debug_workbench_phase_d7_final_debug_seal_overlay.zip\""))
        assertTrue(manifest.contains("\"next_phase\": \"complete\""))
        assertTrue(manifest.contains("\"final_phase_complete\": true"))
        assertTrue(manifest.contains("\"d6_tests_passed\": 414"))
        assertTrue(manifest.contains("\"automatic_upload\": false"))
        assertTrue(manifest.contains("\"top_level_route_added\": false"))
        assertTrue(manifest.contains("\"room_schema_unchanged\": 14"))
    }

    @Test
    fun finalSealDocsAndReleaseGateAreWired() {
        val doc = source("docs/architecture/DEBUG-WORKBENCH-D7-FINAL-DEBUG-SEAL.md")
        val script = source("tools/run-phase-48-final-release-gate.sh")
        val validator = source("tools/validate-debug-workbench-d7-final-debug-seal.py")

        assertTrue(doc.contains("D7 seals the Debug Workbench roadmap"))
        assertTrue(doc.contains("Support bundles remain local and user-shared only"))
        assertTrue(doc.contains("414 passed, 0 failed, 0 skipped"))
        assertTrue(script.contains("validate-debug-workbench-d7-final-debug-seal.py"))
        assertFalse(script.contains("validate-debug-workbench-d1-event-recorder.py"))
        assertTrue(validator.contains("Debug Workbench D7 final debug seal validator"))
    }

    @Test
    fun normalDebugUiUsesHumanLabelsAndKeepsReadOnlyBoundaries() {
        val screen = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/debug/DebugWorkbenchSettingsScreen.kt")
        val debugFiles = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/debug")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

        assertTrue(screen.contains("check.state.displayLabel()"))
        assertFalse(screen.contains("check.state.name"))
        assertFalse(screen.contains("D2 does not"))

        debugFiles.forEach { file ->
            val text = file.readText()
            assertFalse("${file.name} starts runtime work", text.contains("TransferForegroundService"))
            assertFalse("${file.name} launches activities", text.contains("startActivity"))
            assertFalse("${file.name} has placeholder click", text.contains("onClick = {}"))
            text.lineSequence().forEachIndexed { index, line ->
                val rendersText = Regex("""\b(?:Text|XdmSupportingText|XdmMetadataText|XdmMetricText)\s*\(""").containsMatchIn(line)
                if (rendersText) {
                    assertFalse("${file.name}:${index + 1} renders enum name", line.contains(".name"))
                    assertFalse("${file.name}:${index + 1} renders raw URL", line.contains(".url"))
                    assertFalse("${file.name}:${index + 1} renders machine JSON", line.contains("rawJson") || line.contains("JSONObject") || line.contains("JSONArray"))
                }
            }
        }
    }

    @Test
    fun supportBundlePrivacyContractUsesHumanLabelsAndRedaction() {
        val shell = source("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DebugWorkbenchShellModels.kt")
        val events = source("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DebugEventModels.kt")
        val shellTest = source("core-model/src/test/kotlin/com/mikeyphw/xdm/android/model/DebugWorkbenchShellModelsTest.kt")

        assertTrue(shell.contains("fun DebugArea.supportLabel()"))
        assertTrue(shell.contains("fun DebugWorkbenchCheckState.displayLabel()"))
        assertTrue(shell.contains("debugAreas.joinToString { it.supportLabel() }"))
        assertTrue(shell.contains("check.state.displayLabel()"))
        assertFalse(shell.contains("debugAreas.joinToString { it.name }"))
        assertFalse(shell.contains("check.state.name"))
        assertTrue(events.contains("exportSupportBundle"))
        assertTrue(events.contains("DebugRedactor.redactDetails(metadata)"))
        assertTrue(events.contains("No automatic upload"))
        assertTrue(shellTest.contains("clipboardReportUsesHumanLabelsForAreasAndCheckStates"))
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
