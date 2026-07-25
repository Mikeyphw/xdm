package com.mikeyphw.xdm.android.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloaderExperienceTest {
    @Test
    fun reviewPlannerKeepsManualEntryReviewFirst() {
        val empty = DownloadReviewPlanner.plan("", destinationUri = "xdm://downloads")
        assertEquals(DownloadReviewReadiness.MissingLink, empty.readiness)
        assertFalse(empty.canStartDirectly)

        val invalid = DownloadReviewPlanner.plan("javascript:alert(1)", destinationUri = "xdm://downloads")
        assertEquals(DownloadReviewReadiness.InvalidLink, invalid.readiness)
        assertFalse(invalid.canStartDirectly)

        val file = DownloadReviewPlanner.plan("https://example.com/app.apk", destinationUri = "xdm://downloads")
        assertEquals(DownloadIntakeKind.DirectFile, file.kind)
        assertEquals(DownloadReviewReadiness.Ready, file.readiness)
        assertTrue(file.canStartDirectly)
        assertFalse(file.mediaInspectionRecommended)
    }

    @Test
    fun adaptiveAndPageLinksRecommendExplicitMediaInspection() {
        val playlist = DownloadReviewPlanner.plan("https://cdn.example/live/master.m3u8", destinationUri = "xdm://downloads")
        assertEquals(DownloadIntakeKind.AdaptiveMedia, playlist.kind)
        assertEquals(DownloadReviewReadiness.ChoiceRecommended, playlist.readiness)
        assertTrue(playlist.canInspectAsMedia)
        assertTrue(playlist.canStartDirectly)

        val page = DownloadReviewPlanner.plan("https://example.com/watch/42", destinationUri = "xdm://downloads")
        assertEquals(DownloadIntakeKind.PageOrUnknown, page.kind)
        assertTrue(page.mediaInspectionRecommended)
        assertEquals("Review choice", page.primaryActionLabel)
    }

    @Test
    fun destinationIsARequiredReviewStep() {
        val plan = DownloadReviewPlanner.plan("https://example.com/archive.zip")
        assertEquals(DownloadReviewReadiness.ChooseDestination, plan.readiness)
        assertFalse(plan.canStartDirectly)
        assertEquals(listOf(true, false, false), plan.steps.map { it.complete })
    }

    @Test
    fun dashboardGroupsEveryDownloadIntoStableSections() {
        val downloads = listOf(
            download("failed", DownloadState.Failed, error = "HTTP 401 authentication required"),
            download("active", DownloadState.Downloading, speed = 4_000),
            download("queued", DownloadState.WaitingForNetwork),
            download("done", DownloadState.Completed),
            download("cancelled", DownloadState.Cancelled),
        )
        val dashboard = DownloadDashboardPlanner.plan(downloads)
        assertEquals(5, dashboard.summary.total)
        assertEquals(1, dashboard.summary.needsAttention)
        assertEquals(1, dashboard.summary.active)
        assertEquals(1, dashboard.summary.queued)
        assertEquals(1, dashboard.summary.completed)
        assertEquals(1, dashboard.summary.history)
        assertEquals(4_000L, dashboard.summary.aggregateSpeedBytesPerSecond)
        assertEquals(DownloadDashboardBucket.entries, dashboard.sections.map { it.bucket })
    }

    @Test
    fun attentionSignalsExplainLikelyRecoveryAction() {
        assertEquals(
            DownloadAttentionKind.Authentication,
            DownloadDashboardPlanner.attentionSignal(download("auth", DownloadState.Failed, error = "HTTP 403"))?.kind,
        )
        assertEquals(
            DownloadAttentionKind.Storage,
            DownloadDashboardPlanner.attentionSignal(download("space", DownloadState.Failed, error = "No space left on device"))?.kind,
        )
        assertEquals(
            DownloadAttentionKind.Recovery,
            DownloadDashboardPlanner.attentionSignal(download("recovery", DownloadState.RecoveryRequired))?.kind,
        )
        assertEquals(null, DownloadDashboardPlanner.attentionSignal(download("active", DownloadState.Downloading)))
    }

    private fun download(
        id: String,
        state: DownloadState,
        error: String? = null,
        speed: Long = 0,
    ) = Download(
        id = id,
        fileName = "$id.bin",
        sourceUrl = "https://example.com/$id.bin",
        destinationUri = "xdm://downloads",
        state = state,
        backend = BackendType.Native,
        bytesReceived = 0,
        totalBytes = 100,
        speedBytesPerSecond = speed,
        queueId = "default",
        priority = 0,
        createdAtEpochMs = 1,
        updatedAtEpochMs = 2,
        errorMessage = error,
    )
}
