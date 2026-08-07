package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase64FinalAndroidDownloaderRcSealContractTest {
    private val root = androidRoot()

    @Test
    fun finalRcSealCoversAllReleaseReadinessTracks() {
        val seal = source("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/FinalAndroidDownloaderRcSeal.kt")
        val test = source("core-model/src/test/kotlin/com/mikeyphw/xdm/android/model/FinalAndroidDownloaderRcSealPlannerTest.kt")

        assertTrue(seal.contains("FinalAndroidDownloaderRcSealPlanner"))
        assertTrue(seal.contains("Debug Workbench D-series sealed"))
        assertTrue(seal.contains("Operational hardening sealed"))
        assertTrue(seal.contains("Runtime recovery flow sealed"))
        assertTrue(seal.contains("Final validators harmonized"))
        assertTrue(seal.contains("Real-device smoke represented"))
        assertTrue(seal.contains("Support bundle readiness sealed"))
        assertTrue(seal.contains("Browser-free downloader boundary"))
        assertTrue(test.contains("finalRcSealIsReadyWhenAllDownloaderReadinessSignalsArePresent"))
        assertTrue(test.contains("finalRcSealHoldsWhenValidationOrPrivacyBoundariesAreMissing"))
    }

    @Test
    fun finalRcManifestPreservesDownloaderOnlyBoundaries() {
        val manifest = source("PROJECT_MANIFEST.json")
        val androidManifest = source("app/src/main/AndroidManifest.xml")
        val doc = source("docs/architecture/PHASE-64-FINAL-ANDROID-DOWNLOADER-RC-SEAL.md")

        assertTrue(manifest.contains("\"field_bugfix_phase_64\""))
        assertTrue(manifest.contains("\"runtime_foundation_2026_phase55_56\""))
        assertTrue(manifest.contains("\"next_phase\": \"complete\""))
        assertTrue(manifest.contains("\"room_schema_unchanged\": 14"))
        assertTrue(manifest.contains("\"automatic_transfer_start\": false"))
        assertTrue(manifest.contains("\"automatic_deletion\": false"))
        assertTrue(manifest.contains("\"automatic_upload\": false"))
        assertTrue(manifest.contains("\"all_files_permission_added\": false"))
        assertTrue(manifest.contains("\"built_in_browser_resurrected\": false"))
        assertTrue(androidManifest.contains("MANAGE_EXTERNAL_STORAGE"))
        assertTrue(doc.contains("No built-in browser resurrection"))
        assertTrue(doc.contains("Deferred validation: apply with --no-validate, then run the full gate once this final overlay is applied"))
    }

    @Test
    fun finalGatesRunPhase64AndEarlierValidatorsAcceptFinalOverlay() {
        val finalGate = source("tools/run-final-release-gate.sh")
        val phase48Gate = source("tools/run-phase-48-final-release-gate.sh")
        val phase63Validator = source("tools/validate-phase63-release-readiness-support-bundle-seal.py")
        val phase64Validator = source("tools/validate-phase64-final-android-downloader-rc-seal.py")

        assertTrue(finalGate.contains("validate-phase64-final-android-downloader-rc-seal.py"))
        assertTrue(phase48Gate.contains("validate-phase64-final-android-downloader-rc-seal.py"))
        assertTrue(phase63Validator.contains("xdm_android_phase64_final_android_downloader_rc_seal_r2_overlay.zip"))
        assertTrue(phase64Validator.contains("Phase64 final Android downloader RC seal validator passed"))
    }

    @Test
    fun finalRcSealKeepsNormalUiAndReportsPrivate() {
        val seal = source("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/FinalAndroidDownloaderRcSeal.kt")
        val validator = source("tools/validate-phase64-final-android-downloader-rc-seal.py")

        assertTrue(seal.contains("Private values: full links, raw headers, cookies, authorization values, bearer tokens, signatures, credential query values, and browser session values are redacted."))
        assertFalse(seal.contains("https://example.test"))
        assertFalse(seal.contains("Cookie:"))
        assertFalse(seal.contains("Authorization:"))
        assertFalse(seal.contains("Bearer secret"))
        assertFalse(seal.contains("upload("))
        assertFalse(seal.contains("startTransfer"))
        assertFalse(validator.contains("session=secret"))
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
