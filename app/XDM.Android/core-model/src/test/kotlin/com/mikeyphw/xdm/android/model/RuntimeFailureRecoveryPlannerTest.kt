package com.mikeyphw.xdm.android.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeFailureRecoveryPlannerTest {
    @Test
    fun forbiddenFailuresPrioritizeBrowserRefreshWithoutLeakingSecrets() {
        val download = sampleDownload(
            sourceUrl = "https://protected.example.test/video.mp4?token=super-secret&expires=999",
            errorMessage = "Server access was denied (HTTP 403). Authorization: Bearer secret-token-value Cookie: sid=secret",
        )

        val plan = RuntimeFailureRecoveryPlanner.evaluate(download) ?: error("missing plan")

        assertEquals("Server requires browser access", plan.causeLabel)
        assertEquals("Refresh from browser", plan.recommendedActionLabel)
        assertTrue(plan.actions.any { it.label == "Retry with captured session" })
        assertTrue(plan.actions.any { it.label == "Try yt-dlp" })
        val rendered = plan.renderedForTest()
        assertFalse(rendered.contains("super-secret"))
        assertFalse(rendered.contains("secret-token-value"))
        assertFalse(rendered.contains("sid=secret"))
        assertFalse(rendered.contains("https://protected.example.test/video.mp4"))
        assertFalse(rendered.contains("Authorization:"))
        assertFalse(rendered.contains("Cookie:"))
    }

    @Test
    fun recoveryRequiredOpensRecoveryDoctorBeforeRetrying() {
        val download = sampleDownload(
            state = DownloadState.RecoveryRequired,
            bytesReceived = 42_000L,
            errorMessage = "Missing partial file; ownership remains quarantined.",
        )

        val plan = RuntimeFailureRecoveryPlanner.evaluate(download) ?: error("missing plan")

        assertEquals("Recovery state needs review", plan.causeLabel)
        assertEquals("Open Recovery Doctor", plan.recommendedActionLabel)
        assertTrue(plan.steps.any { it.label == "Saved data" && it.status == "Needs review" })
        assertTrue(plan.actions.first().kind == RuntimeFailureRecoveryActionKind.OpenRecoveryDoctor)
    }

    @Test
    fun mediaFailuresPreferYtDlpInspection() {
        val download = sampleDownload(
            sourceUrl = "https://video.example.test/watch/abc",
            fileName = "watch",
            mimeType = "text/html",
            errorMessage = "playlist extraction failed",
        )

        val plan = RuntimeFailureRecoveryPlanner.evaluate(download) ?: error("missing plan")

        assertEquals("Media inspection recommended", plan.causeLabel)
        assertEquals("Try yt-dlp", plan.recommendedActionLabel)
        assertTrue(plan.guidance.contains("Inspect media first"))
    }

    @Test
    fun storageFailuresOfferVisibilityCheckAndRecoveryDoctor() {
        val download = sampleDownload(
            state = DownloadState.Completed,
            destinationUri = "content://downloads/public_downloads/42",
            errorMessage = "Android blocked access to the saved file; storage visibility unknown.",
        )

        val plan = RuntimeFailureRecoveryPlanner.evaluate(download) ?: error("missing plan")

        assertEquals("Storage visibility needs review", plan.causeLabel)
        assertEquals("Re-check storage visibility", plan.recommendedActionLabel)
        assertTrue(plan.actions.any { it.kind == RuntimeFailureRecoveryActionKind.OpenRecoveryDoctor })
    }

    @Test
    fun healthyCompletedDownloadsDoNotShowFailureRecovery() {
        val download = sampleDownload(state = DownloadState.Completed, errorMessage = null)

        assertEquals(null, RuntimeFailureRecoveryPlanner.evaluate(download))
    }

    private fun RuntimeFailureRecoveryPlan.renderedForTest(): String = listOf(
        title,
        sourceSiteLabel,
        causeLabel,
        impactLabel,
        recommendedActionLabel,
        guidance,
        steps.joinToString("\n") { it.label + it.status + it.guidance },
        actions.joinToString("\n") { it.label + it.guidance },
        redactedReport,
    ).joinToString("\n")

    private fun sampleDownload(
        state: DownloadState = DownloadState.Failed,
        sourceUrl: String = "https://files.example.test/release.zip",
        fileName: String = "release.zip",
        mimeType: String? = "application/zip",
        destinationUri: String = "content://downloads/public_downloads/7",
        bytesReceived: Long = 0L,
        totalBytes: Long? = 1024L,
        errorMessage: String? = "Download failed",
        backend: BackendType = BackendType.Native,
    ) = Download(
        id = "download-57",
        fileName = fileName,
        sourceUrl = sourceUrl,
        destinationUri = destinationUri,
        state = state,
        backend = backend,
        bytesReceived = bytesReceived,
        totalBytes = totalBytes,
        speedBytesPerSecond = 0L,
        queueId = null,
        priority = 0,
        createdAtEpochMs = 1L,
        updatedAtEpochMs = 2L,
        errorMessage = errorMessage,
        mimeType = mimeType,
    )
}
