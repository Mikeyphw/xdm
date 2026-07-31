package com.mikeyphw.xdm.android.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeRecoveryActionPreviewPlannerTest {
    @Test
    fun partialRetryPreviewExplainsRecoveryDoctorBeforeRetry() {
        val download = sampleDownload(state = DownloadState.RecoveryRequired, bytesReceived = 42_000L)
        val plan = RuntimeFailureRecoveryPlanner.evaluate(download) ?: error("expected recovery plan")

        val previews = RuntimeRecoveryActionPreviewPlanner.build(download, plan)

        assertTrue(previews.any { it.reviewLabel == "Recovery Doctor required" })
        assertTrue(RuntimeRecoveryActionPreviewPlanner.summary(previews).contains("Recovery Doctor"))
        assertTrue(previews.any { it.outcomeLabel == "Opens Recovery Doctor first" })
    }

    @Test
    fun guidancePreviewDoesNotClaimBackgroundExecution() {
        val download = sampleDownload(errorMessage = "Server access was denied (HTTP 403). Authentication required.")
        val plan = RuntimeFailureRecoveryPlanner.evaluate(download) ?: error("expected recovery plan")

        val previews = RuntimeRecoveryActionPreviewPlanner.build(download, plan)

        assertTrue(previews.any { it.outcomeLabel == "Shows guidance only" })
        assertTrue(previews.any { it.reviewLabel == "No background work" })
        assertFalse(RuntimeRecoveryActionPreviewPlanner.redactedReportSection(previews).contains("https://"))
    }

    @Test
    fun previewReportRedactsHeaderLikeSafetyNotes() {
        val download = sampleDownload()
        val plan = RuntimeFailureRecoveryPlanner.evaluate(download) ?: error("expected recovery plan")
        val decision = RuntimeRecoveryExecutionDecision(
            actionKind = RuntimeFailureRecoveryActionKind.CopyRedactedReport,
            mode = RuntimeRecoveryExecutionMode.CopyOnly,
            buttonLabel = "Copy redacted report",
            executionLabel = "Report only",
            safetyNote = "Cookie: session=secret Authorization: Bearer secret.token",
            allowsImmediateCallback = true,
        )

        val preview = RuntimeRecoveryActionPreviewPlanner.build(download, plan, listOf(decision)).first { it.actionLabel == "Copy redacted report" }

        assertEquals("Copy only", preview.reviewLabel)
        assertFalse(preview.safetyLabel.contains("session=secret"))
        assertFalse(preview.safetyLabel.contains("secret.token"))
        assertTrue(preview.safetyLabel.contains("<redacted"))
    }

    private fun sampleDownload(
        state: DownloadState = DownloadState.Failed,
        bytesReceived: Long = 0L,
        errorMessage: String? = "Download failed",
    ) = Download(
        id = "download-59",
        fileName = "recovery-test.zip",
        sourceUrl = "https://files.example.test/recovery-test.zip?token=secret",
        destinationUri = "content://downloads/public_downloads/59",
        state = state,
        backend = BackendType.Native,
        bytesReceived = bytesReceived,
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
