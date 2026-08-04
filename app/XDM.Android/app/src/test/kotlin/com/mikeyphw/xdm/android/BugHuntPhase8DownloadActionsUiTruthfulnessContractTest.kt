package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BugHuntPhase8DownloadActionsUiTruthfulnessContractTest {
    private val root = androidRoot()

    @Test
    fun actionsUseRealCapabilitiesAndOffMainStorageBoundary() {
        val planner = source("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DownloadActionPlanner.kt")
        val manager = source("app/src/main/kotlin/com/mikeyphw/xdm/android/DownloadArtifactActions.kt")
        val viewModel = source("app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt")
        assertTrue(planner.contains("DownloadActionContext"))
        assertTrue(planner.contains("DownloadState.Verifying") && planner.contains("details(primary = true)"))
        assertTrue(manager.contains("withContext(Dispatchers.IO)"))
        assertTrue(manager.contains("DocumentsContract.renameDocument"))
        assertTrue(manager.contains("DocumentsContract.deleteDocument"))
        assertTrue(manager.contains("safeOwnedFile"))
        assertTrue(viewModel.contains("queueIntelligenceCoordinator.requestStart"))
        assertTrue(viewModel.contains("repository.deleteDownload(current.id)"))
        assertTrue(viewModel.contains("repository.finalizationForDownload(current.id)"))
        assertTrue(viewModel.contains("destinationUri = originalDestination"))
        assertTrue(viewModel.contains("inspectResumeCapability"))
        assertFalse(viewModel.contains("fun startNow(download: Download) {\n        togglePause"))
    }

    @Test
    fun listAndDetailsUseOneTruthModel() {
        val row = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadRow.kt")
        val details = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadDetails.kt")
        val workspace = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadsWorkspace.kt")
        val truth = source("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DownloadUiTruth.kt")
        assertTrue(row.contains("DownloadUiTruthPlanner.truth"))
        assertTrue(details.contains("DownloadUiTruthPlanner.truth"))
        assertTrue(workspace.contains("Paused(\"Paused\")"))
        assertTrue(truth.contains("context.verificationPassed() -> \"Verified and ready\""))
        assertTrue(truth.contains("DownloadState.Downloading && download.speedBytesPerSecond > 0L"))
        assertFalse(row.contains("Next in queue"))
        assertFalse(details.contains("Request data: Protected and redacted"))
    }

    @Test
    fun releaseGatesRunPhase8Validator() {
        val validator = "tools/validate-bug-hunt-phase8-download-actions-ui-truthfulness.py"
        assertTrue(source("tools/run-final-release-gate.sh").contains(validator))
        assertTrue(source(".github/workflows/android.yml").contains(validator))
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
