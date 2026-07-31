package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase60RuntimeRecoveryFlowSealContractTest {
    private val root = androidRoot()

    @Test
    fun runtimeRecoveryFlowSealConnectsPlannerGuardPreviewAndReport() {
        val seal = source("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/RuntimeRecoveryFlowSeal.kt")
        val details = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadDetails.kt")
        val recoveryModel = source("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/RuntimeFailureRecovery.kt")

        assertTrue(seal.contains("RuntimeRecoveryFlowSealPlanner"))
        assertTrue(seal.contains("RuntimeFailureRecoveryPlanner.evaluate"))
        assertTrue(seal.contains("RuntimeRecoveryExecutionGuard.decide"))
        assertTrue(seal.contains("RuntimeRecoveryActionPreviewPlanner.build"))
        assertTrue(seal.contains("redactedReportSection"))
        assertTrue(recoveryModel.contains("Recovery options"))
        assertTrue(details.contains("Action safety"))
        assertTrue(details.contains("Action preview"))
    }

    @Test
    fun phase60SealPreservesRuntimeSafetyBoundaries() {
        val manifest = source("PROJECT_MANIFEST.json")
        val androidManifest = source("app/src/main/AndroidManifest.xml")
        val seal = source("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/RuntimeRecoveryFlowSeal.kt")
        val doc = source("docs/architecture/PHASE-60-RUNTIME-RECOVERY-FLOW-SEAL.md")

        assertTrue(manifest.contains("\"field_bugfix_phase_60\""))
        assertTrue(manifest.contains("\"room_schema_unchanged\": 14"))
        assertTrue(manifest.contains("\"automatic_transfer_start\": false"))
        assertTrue(manifest.contains("\"automatic_deletion\": false"))
        assertTrue(manifest.contains("\"all_files_permission_added\": false"))
        assertFalse(androidManifest.contains("MANAGE_EXTERNAL_STORAGE"))
        assertFalse(seal.contains("startTransfer"))
        assertFalse(seal.contains("enqueueTransfer"))
        assertFalse(seal.contains("delete()"))
        assertTrue(doc.contains("does not start transfers, delete files, or persist browser session values"))
    }

    @Test
    fun finalGatesRunPhase60AndEarlierValidatorsAcceptLaterOverlay() {
        val finalGate = source("tools/run-final-release-gate.sh")
        val phase48Gate = source("tools/run-phase-48-final-release-gate.sh")
        val phase59Validator = source("tools/validate-phase59-runtime-recovery-action-transparency.py")
        val phase60Validator = source("tools/validate-phase60-runtime-recovery-flow-seal.py")

        assertTrue(finalGate.contains("validate-phase60-runtime-recovery-flow-seal.py"))
        assertTrue(phase48Gate.contains("validate-phase60-runtime-recovery-flow-seal.py"))
        assertTrue(phase59Validator.contains("xdm_android_phase60_runtime_recovery_flow_seal_overlay.zip"))
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
