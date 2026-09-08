package com.mikeyphw.xdm.android.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MediaCaptureIdentityMc01Test {
    @Test
    fun captureIdentityPreservesCaseSensitivePathAndQuery() {
        assertNotEquals(
            MediaCaptureService.captureIdFor("https://cdn.example/Video.mp4?quality=HD"),
            MediaCaptureService.captureIdFor("https://CDN.example/video.mp4?quality=HD"),
        )
        assertNotEquals(
            MediaCaptureService.captureIdFor("https://cdn.example/Video.mp4?quality=HD"),
            MediaCaptureService.captureIdFor("https://cdn.example/Video.mp4?quality=hd"),
        )
    }

    @Test
    fun credentialRefreshAndRequestRetryKeepOneLogicalCapture() {
        val first = MediaCaptureService.captureIdFor("https://cdn.example/Video.mp4?token=one&quality=HD")
        val refreshed = MediaCaptureService.captureIdFor("https://CDN.example/Video.mp4?token=two&quality=HD")
        assertEquals(first, refreshed)
        assertEquals(
            MediaCaptureService.browserCaptureIdFor("https://cdn.example/Video.mp4?sig=one", "session-a", "req-a"),
            MediaCaptureService.browserCaptureIdFor("https://cdn.example/Video.mp4?sig=two", "session-b", "req-b"),
        )
    }
}
