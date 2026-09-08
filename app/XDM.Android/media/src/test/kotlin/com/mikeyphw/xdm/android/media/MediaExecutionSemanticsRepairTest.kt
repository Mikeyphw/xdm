package com.mikeyphw.xdm.android.media

import com.mikeyphw.xdm.android.model.MediaResolutionStatus
import com.mikeyphw.xdm.android.model.MediaTransferShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaExecutionSemanticsRepairTest {
    @Test
    fun progressiveMp4IsReadyWithoutPlaylistVariants() {
        val service = MediaCaptureService(clock = { 1_000L })
        val candidate = requireNotNull(service.candidateFor(
            url = "https://cdn.example.test/video.mp4",
            pageUrl = "https://example.test/watch",
            mimeTypeHint = "video/mp4",
            contentLength = 13L * 1024L * 1024L,
        ))
        val record = service.recordFor(candidate)
        val plan = MediaDownloadPlanner().plan(record, emptyList())
        val summary = MediaConsumerWorkspacePlanner().summarizeCapture(record, emptyList(), MediaTrackSelection())

        assertEquals(MediaTransferShape.DirectMedia, plan.transferShape)
        assertEquals(MediaDownloadStrategy.Native, plan.strategy)
        assertEquals(MediaResolutionStatus.Resolved, record.resolutionStatus)
        assertEquals(MediaConsumerState.Ready, summary.state)
        assertTrue(summary.canDownload)
        assertEquals("Direct", summary.selectedQuality)
    }

    @Test
    fun refererAndUserAgentDoNotClaimCredentialOrExpiryContext() {
        val service = MediaCaptureService(clock = { 2_000L })
        val record = service.recordFor(requireNotNull(service.candidateFor(
            url = "https://cdn.example.test/video.mp4",
            pageUrl = "https://example.test/watch",
            mimeTypeHint = "video/mp4",
        )))
        val plan = MediaDownloadPlanner().plan(
            record,
            emptyList(),
            sessionHeaders = listOf(MediaSessionHeader("User-Agent", "Browser")),
        )
        val spec = MediaExecutionLibraryPlanner().queueSpec(
            capture = record,
            variants = emptyList(),
            destinationUri = "xdm://filesystem/downloads",
            sessionHeaders = listOf(MediaSessionHeader("User-Agent", "Browser")),
        )

        assertTrue(plan.sessionHandoff.needsSession)
        assertFalse(plan.needsCookieContext)
        assertFalse(spec.isExpiringUrl)
        assertEquals(MediaTransferShape.DirectMedia, spec.transferShape)
        assertEquals(com.mikeyphw.xdm.android.model.BackendType.Native, spec.requestedBackend)
    }

    @Test
    fun cookieIsCredentialContextWithoutChangingDirectMediaShape() {
        val service = MediaCaptureService(clock = { 3_000L })
        val record = service.recordFor(requireNotNull(service.candidateFor(
            url = "https://cdn.example.test/video.mp4",
            pageUrl = "https://example.test/watch",
            mimeTypeHint = "video/mp4",
        )))
        val plan = MediaDownloadPlanner().plan(
            record,
            emptyList(),
            sessionHeaders = listOf(MediaSessionHeader("Cookie", "sid=secret")),
        )

        assertTrue(plan.needsCookieContext)
        assertEquals(MediaTransferShape.DirectMedia, plan.transferShape)
        assertEquals(MediaDownloadStrategy.Native, plan.strategy)
    }
}
