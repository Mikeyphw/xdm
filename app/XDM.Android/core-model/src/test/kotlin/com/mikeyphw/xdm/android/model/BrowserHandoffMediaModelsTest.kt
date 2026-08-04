package com.mikeyphw.xdm.android.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserHandoffMediaModelsTest {
    @Test fun stableMediaIdSeparatesExactSignedUrlFromSessionIdentity() {
        val a = BrowserHandoffMediaPolicy.stableMediaId("https://site/watch", "https://embed.example/frame", "https://cdn.example/video.mp4?token=one", MediaTransferShape.DirectMedia)
        val b = BrowserHandoffMediaPolicy.stableMediaId("https://site/watch", "https://embed.example/frame", "https://cdn.example/video.mp4?token=two", MediaTransferShape.DirectMedia)
        assertEquals(a, b)
    }

    @Test fun iframeRefererIsPreservedOverTopPage() {
        val context = BrowserFrameContext("https://top.example/watch", "https://player.example/frame", "https://cdn.example/master.m3u8")
        assertEquals("https://player.example/frame", context.effectiveReferer)
        assertTrue(context.preservesIframeContext)
    }

    @Test fun drmClassificationRequiresEvidenceNotSubstrings() {
        val harmless = BrowserHandoffMediaPolicy.classifyProtection(null, null, null, null)
        assertFalse(harmless.protected)
        val dash = BrowserHandoffMediaPolicy.classifyProtection(null, "urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed", null, null)
        assertTrue(dash.protected)
        assertEquals(DrmEvidenceKind.DashContentProtection, dash.evidence.first().kind)
    }

    @Test fun fallbackAfterBytesIsReviewFirst() {
        val decision = BackendFallbackDecision.forCategory(BackendPreparationFailureCategory.RuntimeUnavailable, bytesWritten = 10)
        assertFalse(decision.safeToFallback)
        assertTrue(decision.reviewFirst)
        assertTrue(decision.preservePartialBytes)
    }
}
