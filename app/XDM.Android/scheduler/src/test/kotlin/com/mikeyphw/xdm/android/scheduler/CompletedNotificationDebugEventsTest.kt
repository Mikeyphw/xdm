package com.mikeyphw.xdm.android.scheduler

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompletedNotificationDebugEventsTest {
    @Test
    fun fallbackEventRedactsUriAndFingerprintsDownloadId() {
        val event = CompletedNotificationDebugEvents.fallback(
            downloadId = "download-1234567890",
            reason = "no-viewer",
            uri = "https://cdn.example.com/movie.mp4?token=secret-token",
            mimeType = "video/mp4",
            timestampMillis = 1L,
        )
        val json = event.toJsonLine()

        assertTrue(json.contains("fallback-to-xdm-details"))
        assertTrue(json.contains("token=<redacted>"))
        assertFalse(json.contains("secret-token"))
        assertFalse(json.contains("download-1234567890"))
    }
}
