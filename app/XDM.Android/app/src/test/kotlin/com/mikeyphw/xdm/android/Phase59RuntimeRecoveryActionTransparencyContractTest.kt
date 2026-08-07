package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase59RuntimeRecoveryActionTransparencyContractTest {
    private val root = androidRoot()

    @Test
    fun recoveryCardShowsActionPreviewBeforeCallbacks() {
        val details = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadDetails.kt")
        val preview = source("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/RuntimeRecoveryActionPreview.kt")

        assertTrue(details.contains("RuntimeRecoveryActionPreviewPlanner.build(download, plan"))
        assertTrue(details.contains("Action preview"))
        assertTrue(details.contains("What happens"))
        assertTrue(details.contains("RuntimeRecoveryActionPreviewPlanner.redactedReportSection"))
        assertTrue(preview.contains("RuntimeRecoveryActionPreviewPlanner"))
        assertTrue(preview.contains("Explicit tap required"))
        assertTrue(preview.contains("Recovery Doctor required"))
        assertTrue(preview.contains("No background work"))
        assertTrue(preview.contains("Private values remain redacted"))
    }

    @Test
    fun actionTransparencyDoesNotExposePrivateRequestDataOrStartWork() {
        val preview = source("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/RuntimeRecoveryActionPreview.kt")
        val details = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadDetails.kt")

        assertFalse(preview.contains("startTransfer"))
        assertFalse(preview.contains("enqueueTransfer"))
        assertFalse(preview.contains("delete()"))
        assertFalse(preview.contains("MANAGE_EXTERNAL_STORAGE"))
        assertFalse(preview.contains("Cookie:"))
        assertFalse(preview.contains("Authorization:"))
        assertFalse(preview.contains("Bearer secret"))
        assertFalse(details.contains("MANAGE_EXTERNAL_STORAGE"))
    }

    @Test
    fun phase59PreservesSealedBoundariesAndReleaseGate() {
        val manifest = source("PROJECT_MANIFEST.json")
        val androidManifest = source("app/src/main/AndroidManifest.xml")
        val doc = source("docs/architecture/PHASE-59-RUNTIME-RECOVERY-ACTION-TRANSPARENCY.md")
        val validator = source("tools/validate-phase59-runtime-recovery-action-transparency.py")
        val finalGate = source("tools/run-final-release-gate.sh")

        assertTrue(manifest.contains("\"field_bugfix_phase_59\""))
        assertTrue(manifest.contains("\"room_schema_unchanged\": 14"))
        assertTrue(manifest.contains("\"automatic_transfer_start\": false"))
        assertTrue(manifest.contains("\"automatic_deletion\": false"))
        assertTrue(manifest.contains("\"all_files_permission_added\": false"))
        assertTrue(androidManifest.contains("MANAGE_EXTERNAL_STORAGE"))
        assertTrue(doc.contains("does not auto-start retries"))
        assertTrue(validator.contains("Phase 59 runtime recovery action transparency validator passed"))
        assertTrue(finalGate.contains("validate-phase59-runtime-recovery-action-transparency.py"))
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
