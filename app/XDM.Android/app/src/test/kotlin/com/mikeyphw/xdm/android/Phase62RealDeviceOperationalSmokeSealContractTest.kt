package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase62RealDeviceOperationalSmokeSealContractTest {
    private val root = androidRoot()

    @Test
    fun smokeSealCoversRealDeviceOperationalFlows() {
        val seal = source("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/RealDeviceOperationalSmokeSeal.kt")
        val test = source("core-model/src/test/kotlin/com/mikeyphw/xdm/android/model/RealDeviceOperationalSmokeSealPlannerTest.kt")

        assertTrue(seal.contains("ExternalBrowserHandoff"))
        assertTrue(seal.contains("ExtensionMediaCapture"))
        assertTrue(seal.contains("AuthenticatedFailureRecovery"))
        assertTrue(seal.contains("CompletedStorageVisibility"))
        assertTrue(seal.contains("RecoveryDoctorReview"))
        assertTrue(seal.contains("manual device run required"))
        assertTrue(test.contains("smokeSealRequiresManualDeviceRunBeforeRcCandidate"))
        assertTrue(test.contains("capturedSmokeRunCanSealRcCandidate"))
    }

    @Test
    fun phase62PreservesDownloaderSafetyBoundaries() {
        val manifest = source("PROJECT_MANIFEST.json")
        val androidManifest = source("app/src/main/AndroidManifest.xml")
        val seal = source("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/RealDeviceOperationalSmokeSeal.kt")
        val doc = source("docs/architecture/PHASE-62-REAL-DEVICE-OPERATIONAL-SMOKE-SEAL.md")

        assertTrue(manifest.contains("\"field_bugfix_phase_62\""))
        assertTrue(manifest.contains("\"room_schema_unchanged\": 14"))
        assertTrue(manifest.contains("\"automatic_transfer_start\": false"))
        assertTrue(manifest.contains("\"automatic_deletion\": false"))
        assertTrue(manifest.contains("\"all_files_permission_added\": false"))
        assertTrue(androidManifest.contains("MANAGE_EXTERNAL_STORAGE"))
        assertFalse(seal.contains("startTransfer"))
        assertFalse(seal.contains("enqueueTransfer"))
        assertFalse(seal.contains("delete()"))
        assertTrue(doc.contains("does not start transfers, delete files, request all-files storage, persist browser session values, or reopen Debug Workbench"))
    }

    @Test
    fun finalGatesRunPhase62AndEarlierValidatorsAcceptLaterOverlay() {
        val finalGate = source("tools/run-final-release-gate.sh")
        val phase48Gate = source("tools/run-phase-48-final-release-gate.sh")
        val phase61Validator = source("tools/validate-phase61-final-gate-validator-harmony.py")
        val phase62Validator = source("tools/validate-phase62-real-device-operational-smoke-seal.py")

        assertTrue(finalGate.contains("validate-phase62-real-device-operational-smoke-seal.py"))
        assertTrue(phase48Gate.contains("validate-phase62-real-device-operational-smoke-seal.py"))
        assertTrue(phase61Validator.contains("xdm_android_phase62_real_device_operational_smoke_seal_overlay.zip"))
        assertTrue(phase62Validator.contains("Phase62 real-device operational smoke seal validator passed"))
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
