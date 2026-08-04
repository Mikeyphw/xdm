package com.mikeyphw.xdm.android.media

import com.mikeyphw.xdm.android.model.BrowserHeaderObservationKind
import com.mikeyphw.xdm.android.model.MediaSessionEvictionReason
import com.mikeyphw.xdm.android.model.MediaSourceKind
import com.mikeyphw.xdm.android.model.PageObservationProof
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserHandoffMediaCoordinatorTest {
    @Test fun rotatingSignedUrlRefreshesSameStableSession() {
        val coordinator = BrowserHandoffMediaCoordinator(clock = { 1_000 })
        val first = coordinator.rememberBrowserRevision(
            requestUrl = "https://cdn.example/video.mp4?token=one",
            topPageUrl = "https://site.example/watch",
            frameUrl = "https://player.example/embed",
            kind = MediaSourceKind.ProgressiveMedia,
            mimeType = "video/mp4",
            proposedHeaders = mapOf("Cookie" to "sid=1"),
            finalHeaders = null,
            revision = 1,
            expiresAtEpochMs = 10_000,
        )
        val second = coordinator.rememberBrowserRevision(
            requestUrl = "https://cdn.example/video.mp4?token=two",
            topPageUrl = "https://site.example/watch",
            frameUrl = "https://player.example/embed",
            kind = MediaSourceKind.ProgressiveMedia,
            mimeType = "video/mp4",
            proposedHeaders = mapOf("Cookie" to "sid=2"),
            finalHeaders = mapOf("Cookie" to "sid=2", "Referer" to "https://player.example/embed"),
            revision = 2,
            expiresAtEpochMs = 20_000,
        )
        assertEquals(first.stableMediaId, second.stableMediaId)
        assertEquals(BrowserHeaderObservationKind.FinalSent, second.finalHeaders.kind)
        assertEquals("sid=2", second.usableHeaders["Cookie"])
    }

    @Test fun pageObservationRequiresLiveNonce() {
        val coordinator = BrowserHandoffMediaCoordinator(clock = { 5_000 })
        assertFalse(coordinator.authenticatePageObservation(null))
        assertFalse(coordinator.authenticatePageObservation(PageObservationProof("short", "pkg", 1, 10_000)))
        assertTrue(coordinator.authenticatePageObservation(PageObservationProof("0123456789abcdef", "pkg", 1, 10_000)))
    }

    @Test fun fileBackedStorePersistsFinalHeadersAndDeclaredStableId() {
        val root = createTempDir(prefix = "xdm-browser-handoff")
        try {
            val coordinator = BrowserHandoffMediaCoordinator(store = FileBackedBrowserHandoffMediaSessionStore(root), clock = { 1_000 })
            val session = coordinator.rememberBrowserRevision(
                requestUrl = "https://cdn.example/video.mp4?token=one",
                topPageUrl = "https://site.example/watch",
                frameUrl = "https://player.example/embed",
                kind = MediaSourceKind.ProgressiveMedia,
                mimeType = "video/mp4",
                proposedHeaders = mapOf("Cookie" to "sid=proposed"),
                finalHeaders = mapOf("Cookie" to "sid=final"),
                revision = 77,
                expiresAtEpochMs = 10_000,
                declaredStableMediaId = "media-session-declared77",
            )
            assertEquals("media-session-declared77", session.stableMediaId)
            val restored = BrowserHandoffMediaCoordinator(store = FileBackedBrowserHandoffMediaSessionStore(root), clock = { 2_000 })
                .sessionFor("media-session-declared77")
            assertEquals("sid=final", restored?.usableHeaders?.get("Cookie"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun pageObservationProofIsRequiredWhenRequested() {
        val coordinator = BrowserHandoffMediaCoordinator(clock = { 1_000 })
        var rejected = false
        try {
            coordinator.rememberBrowserRevision(
                requestUrl = "https://cdn.example/video.mp4",
                topPageUrl = "https://site.example/watch",
                frameUrl = null,
                kind = MediaSourceKind.ProgressiveMedia,
                mimeType = "video/mp4",
                proposedHeaders = emptyMap(),
                finalHeaders = null,
                revision = 1,
                expiresAtEpochMs = 10_000,
                requirePageObservationProof = true,
            )
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
    }

}
