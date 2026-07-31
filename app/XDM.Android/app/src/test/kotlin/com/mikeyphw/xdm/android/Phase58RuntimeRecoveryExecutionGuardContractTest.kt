package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase58RuntimeRecoveryExecutionGuardContractTest {
    private val root = androidRoot()

    @Test
    fun recoveryActionsUseExecutionGuardBeforeCallbacks() {
        val details = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadDetails.kt")
        val guard = source("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/RuntimeRecoveryExecutionGuard.kt")

        assertTrue(details.contains("RuntimeRecoveryExecutionGuard.decide(download, action.kind)"))
        assertTrue(details.contains("Action safety"))
        assertTrue(details.contains("decision.allowsImmediateCallback"))
        assertTrue(details.contains("RuntimeRecoveryExecutionMode.OpenRecoveryFirst"))
        assertTrue(details.contains("showRuntimeRecoveryToast(context, decision.safetyNote)"))
        assertTrue(guard.contains("RuntimeRecoveryExecutionMode"))
        assertTrue(guard.contains("Review before retry"))
        assertTrue(guard.contains("Review captured session"))
        assertTrue(guard.contains("Guidance only"))
        assertTrue(guard.contains("Recovery review required"))
    }

    @Test
    fun executionGuardDoesNotAddAutomaticBackgroundWorkOrDangerousOperations() {
        val guard = source("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/RuntimeRecoveryExecutionGuard.kt")
        val details = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadDetails.kt")

        assertFalse(guard.contains("startTransfer"))
        assertFalse(guard.contains("enqueueTransfer"))
        assertFalse(guard.contains("delete()"))
        assertFalse(guard.contains("MANAGE_EXTERNAL_STORAGE"))
        assertFalse(details.contains("MANAGE_EXTERNAL_STORAGE"))
        assertTrue(guard.contains("does not start transfers"))
        assertTrue(guard.contains("does not start transfers, migrate methods, delete files"))
    }

    @Test
    fun phase58PreservesSealedBoundariesAndReleaseGate() {
        val manifest = source("PROJECT_MANIFEST.json")
        val androidManifest = source("app/src/main/AndroidManifest.xml")
        val doc = source("docs/architecture/PHASE-58-RUNTIME-RECOVERY-EXECUTION-GUARD.md")
        val validator = source("tools/validate-phase58-runtime-recovery-execution-guard.py")
        val finalGate = source("tools/run-final-release-gate.sh")

        assertTrue(manifest.contains("\"field_bugfix_phase_58\""))
        assertTrue(manifest.contains("\"room_schema_unchanged\": 14"))
        assertTrue(manifest.contains("\"automatic_transfer_start\": false"))
        assertTrue(manifest.contains("\"automatic_deletion\": false"))
        assertTrue(manifest.contains("\"all_files_permission_added\": false"))
        assertFalse(androidManifest.contains("MANAGE_EXTERNAL_STORAGE"))
        assertTrue(doc.contains("does not auto-start retries"))
        assertTrue(validator.contains("Phase 58 runtime recovery execution guard validator passed"))
        assertTrue(finalGate.contains("validate-phase58-runtime-recovery-execution-guard.py"))
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
