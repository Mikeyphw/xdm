package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase57RuntimeFailureRecoveryUxContractTest {
    private val root = androidRoot()

    @Test
    fun downloadDetailsWiresRecoveryCardToExistingSafeActions() {
        val details = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadDetails.kt")
        val downloads = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadsScreen.kt")

        assertTrue(details.contains("RuntimeFailureRecoveryPlanner.evaluate(download)"))
        assertTrue(details.contains("RuntimeFailureRecoveryCard"))
        assertTrue(details.contains("runRuntimeRecoveryAction"))
        assertTrue(details.contains("RuntimeFailureRecoveryActionKind.RefreshFromBrowser"))
        assertTrue(details.contains("RuntimeFailureRecoveryActionKind.TryYtDlp"))
        assertTrue(details.contains("RuntimeFailureRecoveryActionKind.RecheckStorageVisibility"))
        assertTrue(details.contains("RuntimeFailureRecoveryActionKind.OpenRecoveryDoctor"))
        assertTrue(details.contains("RuntimeFailureRecoveryActionKind.CopyRedactedReport"))
        assertTrue(details.contains("onOpenActivityAttention = onOpenActivityAttention"))
        assertTrue(downloads.contains("onOpenActivityAttention = onOpenActivityAttention"))
    }

    @Test
    fun plannerKeepsFailureGuidancePureAndRedacted() {
        val planner = source("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/RuntimeFailureRecovery.kt")
        val tests = source("core-model/src/test/kotlin/com/mikeyphw/xdm/android/model/RuntimeFailureRecoveryPlannerTest.kt")

        assertTrue(planner.contains("object RuntimeFailureRecoveryPlanner"))
        assertTrue(planner.contains("Server requires browser access"))
        assertTrue(planner.contains("Browser session may be stale"))
        assertTrue(planner.contains("Media inspection recommended"))
        assertTrue(planner.contains("Storage visibility needs review"))
        assertTrue(planner.contains("Recovery state needs review"))
        assertTrue(planner.contains("Try another transfer method"))
        assertTrue(planner.contains("Refresh from browser"))
        assertTrue(planner.contains("Try yt-dlp"))
        assertTrue(planner.contains("Re-check storage visibility"))
        assertTrue(planner.contains("Open Recovery Doctor"))
        assertTrue(planner.contains("Copy redacted report"))
        assertTrue(planner.contains("PrivacyDiagnosticsRedactor.redactUrl"))
        assertTrue(planner.contains("Private values: cookies, authorization values, bearer tokens, signatures, and credential query values are redacted."))
        assertFalse(planner.contains("startTransfer"))
        assertFalse(planner.contains("enqueueTransfer"))
        assertFalse(planner.contains("delete()"))
        assertTrue(tests.contains("forbiddenFailuresPrioritizeBrowserRefreshWithoutLeakingSecrets"))
        assertTrue(tests.contains("recoveryRequiredOpensRecoveryDoctorBeforeRetrying"))
        assertTrue(tests.contains("storageFailuresOfferVisibilityCheckAndRecoveryDoctor"))
    }

    @Test
    fun phase57DoesNotAddDangerousPermissionsRoutesOrSchemaChanges() {
        val manifest = source("PROJECT_MANIFEST.json")
        val androidManifest = source("app/src/main/AndroidManifest.xml")
        val doc = source("docs/architecture/PHASE-57-RUNTIME-FAILURE-RECOVERY-UX.md")
        val validator = source("tools/validate-phase57-runtime-failure-recovery-ux.py")
        val finalGate = source("tools/run-final-release-gate.sh")

        assertTrue(manifest.contains("\"field_bugfix_phase_57\""))
        assertTrue(manifest.contains("\"room_schema_unchanged\": 14"))
        assertTrue(manifest.contains("\"top_level_route_added\": false"))
        assertTrue(manifest.contains("\"automatic_deletion\": false"))
        assertTrue(manifest.contains("\"all_files_permission_added\": false"))
        assertTrue(androidManifest.contains("MANAGE_EXTERNAL_STORAGE"))
        assertTrue(doc.contains("does not start transfers automatically"))
        assertTrue(validator.contains("Phase 57 runtime failure recovery UX validator passed"))
        assertTrue(finalGate.contains("validate-phase57-runtime-failure-recovery-ux.py"))
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
