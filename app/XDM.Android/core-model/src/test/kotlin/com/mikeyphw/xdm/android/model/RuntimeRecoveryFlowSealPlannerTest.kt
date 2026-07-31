package com.mikeyphw.xdm.android.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeRecoveryFlowSealPlannerTest {
    @Test
    fun failedDownloadSealCombinesPlannerGuardPreviewAndRedactedReport() {
        val download = sampleDownload(
            state = DownloadState.Failed,
            sourceUrl = "https://media.example.test/watch/file.mp4?token=secret",
            errorMessage = "Server access was denied (HTTP 403). Authentication required.",
        )

        val seal = RuntimeRecoveryFlowSealPlanner.evaluate(download)

        assertTrue(seal.sealed)
        assertTrue(seal.checks.any { it.label == "Recovery plan" && it.status == "Ready" })
        assertTrue(seal.checks.any { it.label == "Action guard" && it.status.contains("guarded") })
        assertTrue(seal.checks.any { it.label == "Action preview" && it.status == "Shown before tap" })
        assertTrue(seal.checks.any { it.label == "Support copy" && it.status == "Redacted" })
        assertTrue(seal.redactedSummary.contains("Actions guarded"))
        assertFalse(seal.redactedSummary.contains("https://"))
        assertFalse(seal.redactedSummary.contains("token=secret"))
    }

    @Test
    fun healthyDownloadSealDoesNotOfferRecoveryWork() {
        val download = sampleDownload(state = DownloadState.Completed, errorMessage = null)

        val seal = RuntimeRecoveryFlowSealPlanner.evaluate(download)

        assertTrue(seal.sealed)
        assertTrue(seal.recommendedActionLabel == "No recovery needed")
        assertTrue(seal.checks.any { it.status == "Manual only" })
        assertTrue(seal.redactedSummary.contains("No recovery action is required"))
    }

    private fun sampleDownload(
        state: DownloadState = DownloadState.Failed,
        sourceUrl: String = "https://files.example.test/recovery-test.zip?token=secret",
        errorMessage: String? = "Download failed",
    ) = Download(
        id = "download-60",
        fileName = "recovery-test.zip",
        sourceUrl = sourceUrl,
        destinationUri = "content://downloads/public_downloads/60",
        state = state,
        backend = BackendType.Native,
        bytesReceived = 0L,
        totalBytes = 4096L,
        speedBytesPerSecond = 0L,
        queueId = null,
        priority = 0,
        createdAtEpochMs = 1L,
        updatedAtEpochMs = 2L,
        errorMessage = errorMessage,
        mimeType = "application/zip",
    )
}
