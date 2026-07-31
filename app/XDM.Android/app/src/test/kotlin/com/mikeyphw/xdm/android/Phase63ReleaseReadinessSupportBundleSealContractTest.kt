package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase63ReleaseReadinessSupportBundleSealContractTest {
    private val root = androidRoot()

    @Test
    fun supportBundleSealCoversReleaseReadinessSections() {
        val seal = source("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/SupportBundleReleaseReadinessSeal.kt")
        val test = source("core-model/src/test/kotlin/com/mikeyphw/xdm/android/model/SupportBundleReleaseReadinessPlannerTest.kt")

        assertTrue(seal.contains("SupportBundleReleaseReadinessPlanner"))
        assertTrue(seal.contains("Operational diagnostics summary"))
        assertTrue(seal.contains("Release-security status"))
        assertTrue(seal.contains("Install/update readiness"))
        assertTrue(seal.contains("Final-release warning explanations"))
        assertTrue(seal.contains("Real-device smoke status"))
        assertTrue(seal.contains("Privacy redaction boundary"))
        assertTrue(test.contains("supportBundleSealIsReadyWhenAllReleaseSectionsAreRedactedAndPresent"))
        assertTrue(test.contains("supportBundleSealBlocksWhenWarningsAreBareOrSessionValuesWouldPersist"))
    }

    @Test
    fun supportReportIncludesSealAndNoRawPrivateMarkers() {
        val viewModel = source("app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt")
        val seal = source("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/SupportBundleReleaseReadinessSeal.kt")

        assertTrue(viewModel.contains("SupportBundleReleaseReadinessPlanner.evaluate"))
        assertTrue(viewModel.contains("supportBundleSeal.redactedSummary()"))
        assertTrue(viewModel.contains("finalReleaseGateReport.redactedExplanationSummary()"))
        assertFalse(seal.contains("https://example.test"))
        assertFalse(seal.contains("Cookie:"))
        assertFalse(seal.contains("Authorization:"))
        assertFalse(seal.contains("Bearer secret"))
        assertFalse(seal.contains("upload("))
        assertFalse(seal.contains("MANAGE_EXTERNAL_STORAGE"))
    }

    @Test
    fun phase63PreservesReleaseAndStorageBoundaries() {
        val manifest = source("PROJECT_MANIFEST.json")
        val androidManifest = source("app/src/main/AndroidManifest.xml")
        val doc = source("docs/architecture/PHASE-63-RELEASE-READINESS-SUPPORT-BUNDLE-SEAL.md")

        assertTrue(manifest.contains("\"field_bugfix_phase_63\""))
        assertTrue(manifest.contains("\"room_schema_unchanged\": 14"))
        assertTrue(manifest.contains("\"automatic_transfer_start\": false"))
        assertTrue(manifest.contains("\"automatic_deletion\": false"))
        assertTrue(manifest.contains("\"automatic_upload\": false"))
        assertTrue(manifest.contains("\"all_files_permission_added\": false"))
        assertFalse(androidManifest.contains("MANAGE_EXTERNAL_STORAGE"))
        assertTrue(doc.contains("does not upload, start transfers, delete files, add storage permissions, persist session values, or reopen Debug Workbench"))
    }

    @Test
    fun finalGatesRunPhase63AndEarlierValidatorsAcceptLaterOverlay() {
        val finalGate = source("tools/run-final-release-gate.sh")
        val phase48Gate = source("tools/run-phase-48-final-release-gate.sh")
        val phase62Validator = source("tools/validate-phase62-real-device-operational-smoke-seal.py")
        val phase63Validator = source("tools/validate-phase63-release-readiness-support-bundle-seal.py")

        assertTrue(finalGate.contains("validate-phase63-release-readiness-support-bundle-seal.py"))
        assertTrue(phase48Gate.contains("validate-phase63-release-readiness-support-bundle-seal.py"))
        assertTrue(phase62Validator.contains("xdm_android_phase63_release_readiness_support_bundle_seal_r2_overlay.zip"))
        assertTrue(phase63Validator.contains("Phase63 release readiness support bundle seal validator passed"))
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
