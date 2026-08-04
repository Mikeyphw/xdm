package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase61FinalGateValidatorHarmonyContractTest {
    private val root = androidRoot()

    @Test
    fun uixR3ValidatorAcceptsPhase44PlannerBackedDownloadRows() {
        val uixR3Validator = source("tools/validate-uix-r3-downloads-add-workspace.py")
        val phase44Validator = source("tools/validate-phase-44-download-list-actions.py")
        val row = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadRow.kt")

        assertFalse("UIX R3 must not require the retired row-local primaryRowAction symbol", uixR3Validator.contains("\"primaryRowAction\""))
        assertFalse("UIX R3 must not require the retired row-local primaryRowAction symbol", uixR3Validator.contains("'primaryRowAction'"))
        assertTrue("UIX R3 must require the current planner-backed row action", uixR3Validator.contains("DownloadActionPlanner.primaryActionFor(download, actionContext)"))
        assertTrue("UIX R3 must keep the row action icon contract", uixR3Validator.contains("DownloadAction.iconVector()"))
        assertTrue("Phase44 must still forbid the old row-local action planner", phase44Validator.contains("private fun Download.primaryRowAction"))
        assertTrue("DownloadRow must use the shared action planner", row.contains("DownloadActionPlanner.primaryActionFor(download, actionContext)"))
        assertFalse("DownloadRow must not revive row-local action planning", row.contains("private fun Download.primaryRowAction"))
        assertFalse("DownloadRow must not revive row-local action data", row.contains("private data class DownloadRowAction"))
    }

    @Test
    fun finalGatesRunHarmonyValidatorAndKeepRuntimeBoundary() {
        val finalGate = source("tools/run-final-release-gate.sh")
        val phase48Gate = source("tools/run-phase-48-final-release-gate.sh")
        val manifest = source("PROJECT_MANIFEST.json")
        val androidManifest = source("app/src/main/AndroidManifest.xml")

        assertTrue(finalGate.contains("validate-phase61-final-gate-validator-harmony.py"))
        assertTrue(phase48Gate.contains("validate-phase61-final-gate-validator-harmony.py"))
        assertTrue(manifest.contains("\"field_bugfix_phase_61\""))
        assertTrue(manifest.contains("\"room_schema_unchanged\": 14"))
        assertTrue(manifest.contains("\"automatic_transfer_start\": false"))
        assertTrue(manifest.contains("\"automatic_deletion\": false"))
        assertTrue(manifest.contains("\"all_files_permission_added\": false"))
        assertFalse(androidManifest.contains("MANAGE_EXTERNAL_STORAGE"))
    }

    @Test
    fun phase60ValidatorAllowsPhase61CurrentOverlay() {
        val phase60Validator = source("tools/validate-phase60-runtime-recovery-flow-seal.py")
        assertTrue(phase60Validator.contains("xdm_android_phase61_final_gate_validator_harmony_overlay.zip"))
        assertTrue(phase60Validator.contains("Phase 60 runtime recovery flow seal validator passed"))
    }

    private fun source(relative: String): String = File(root, relative).readText()

    private fun androidRoot(): File {
        var cursor = File(System.getProperty("user.dir") ?: ".").canonicalFile
        repeat(8) {
            if (File(cursor, "settings.gradle.kts").isFile && File(cursor, "app/src/main").isDirectory) return cursor
            cursor = cursor.parentFile ?: cursor
        }
        error("Android root not found")
    }
}
