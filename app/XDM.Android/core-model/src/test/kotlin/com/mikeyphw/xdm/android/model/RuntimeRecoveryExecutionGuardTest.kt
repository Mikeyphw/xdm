package com.mikeyphw.xdm.android.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeRecoveryExecutionGuardTest {
    @Test
    fun partialFailuresOpenRecoveryBeforeRetryOrMethodSwitch() {
        val download = sampleDownload(bytesReceived = 64_000L)

        val retry = RuntimeRecoveryExecutionGuard.decide(download, RuntimeFailureRecoveryActionKind.RetryWithCurrentSetup)
        val aria2 = RuntimeRecoveryExecutionGuard.decide(download, RuntimeFailureRecoveryActionKind.TryAria2)

        assertEquals(RuntimeRecoveryExecutionMode.OpenRecoveryFirst, retry.mode)
        assertEquals("Review before retry", retry.buttonLabel)
        assertFalse(retry.allowsImmediateCallback)
        assertEquals(RuntimeRecoveryExecutionMode.OpenRecoveryFirst, aria2.mode)
        assertTrue(RuntimeRecoveryExecutionGuard.summary(listOf(retry, aria2)).contains("Recovery Doctor"))
    }

    @Test
    fun cleanFailuresCanRetryOnlyAfterExplicitTap() {
        val decision = RuntimeRecoveryExecutionGuard.decide(sampleDownload(), RuntimeFailureRecoveryActionKind.RetryWithCurrentSetup)

        assertEquals(RuntimeRecoveryExecutionMode.ExecuteNow, decision.mode)
        assertEquals("Retry current request", decision.buttonLabel)
        assertEquals("Reviewed retry", decision.executionLabel)
        assertTrue(decision.allowsImmediateCallback)
        assertTrue(decision.safetyNote.contains("explicit tap"))
    }

    @Test
    fun capturedSessionRetryIsAlwaysReviewFirst() {
        val decision = RuntimeRecoveryExecutionGuard.decide(
            sampleDownload(errorMessage = "Server access was denied (HTTP 403). Authentication required."),
            RuntimeFailureRecoveryActionKind.RetryWithCapturedSession,
        )

        assertEquals(RuntimeRecoveryExecutionMode.ReviewFirst, decision.mode)
        assertEquals("Review captured session", decision.buttonLabel)
        assertFalse(decision.allowsImmediateCallback)
        assertFalse(decision.safetyNote.contains("Cookie:"))
        assertFalse(decision.safetyNote.contains("Authorization:"))
    }

    @Test
    fun guidanceActionsNeverStartBackgroundWork() {
        val refresh = RuntimeRecoveryExecutionGuard.decide(sampleDownload(), RuntimeFailureRecoveryActionKind.RefreshFromBrowser)
        val ytdlp = RuntimeRecoveryExecutionGuard.decide(sampleDownload(), RuntimeFailureRecoveryActionKind.TryYtDlp)

        assertEquals(RuntimeRecoveryExecutionMode.GuidanceOnly, refresh.mode)
        assertEquals(RuntimeRecoveryExecutionMode.GuidanceOnly, ytdlp.mode)
        assertFalse(refresh.allowsImmediateCallback)
        assertFalse(ytdlp.allowsImmediateCallback)
    }

    @Test
    fun reportsStayCopyOnlyAndRedacted() {
        val decision = RuntimeRecoveryExecutionGuard.decide(sampleDownload(), RuntimeFailureRecoveryActionKind.CopyRedactedReport)

        assertEquals(RuntimeRecoveryExecutionMode.CopyOnly, decision.mode)
        assertTrue(decision.allowsImmediateCallback)
        assertTrue(decision.safetyNote.contains("cookies"))
        assertFalse(decision.safetyNote.contains("https://"))
        assertFalse(decision.safetyNote.contains("Bearer secret"))
    }

    private fun sampleDownload(
        state: DownloadState = DownloadState.Failed,
        bytesReceived: Long = 0L,
        errorMessage: String? = "Download failed",
    ) = Download(
        id = "download-58",
        fileName = "release.zip",
        sourceUrl = "https://files.example.test/release.zip",
        destinationUri = "content://downloads/public_downloads/58",
        state = state,
        backend = BackendType.Native,
        bytesReceived = bytesReceived,
        totalBytes = 1024L,
        speedBytesPerSecond = 0L,
        queueId = null,
        priority = 0,
        createdAtEpochMs = 1L,
        updatedAtEpochMs = 2L,
        errorMessage = errorMessage,
        mimeType = "application/zip",
    )
}
