package com.mikeyphw.xdm.android.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSniffingLabTest {
    @Test
    fun labRunsSharedStaticSnifferAndRedactsCopyReport() {
        val report = MediaSniffingLab.inspect(
            MediaSniffingLabRequest(
                rawInput = """
                    <video src="/preview.mp4"></video>
                    {"hls":"https://cdn.example.test/master.m3u8?token=secret-token&sig=rawsig"}
                """.trimIndent(),
                baseUrl = "https://watch.example.test/show?id=42&session=secret-session",
                mimeTypeHint = "text/html",
                source = MediaSniffingSource.SharedText,
            ),
            engine = MediaSniffingEngine(MediaCaptureService(clock = { 47L })),
        )

        assertTrue(report.hasCandidates)
        assertTrue(report.candidateRows.any { it.kindLabel == "HLS manifest" })
        assertTrue(report.candidateRows.any { it.redactedUrl.contains("token=<redacted>") })
        assertFalse(report.copyText.contains("secret-token"))
        assertFalse(report.copyText.contains("secret-session"))
        assertTrue(report.copyText.contains("no arbitrary JavaScript execution"))
        assertTrue(report.copyText.contains("no DRM bypass"))
        assertTrue(report.diagnostics.any { it.contains("shared MediaSniffingEngine") })
    }

    @Test
    fun labDoesNotNeedNetworkProbeForDirectManifest() {
        val report = MediaSniffingLab.inspect(
            MediaSniffingLabRequest(
                rawInput = "https://cdn.example.test/live/master.m3u8?signature=secret",
                source = MediaSniffingSource.ManualPage,
            ),
        )

        assertEquals("1 candidate(s)", report.statusLabel)
        assertTrue(report.primaryCandidateLabel.contains("HLS manifest"))
        assertTrue(report.copyText.contains("signature=<redacted>"))
        assertFalse(report.copyText.contains("secret"))
        assertTrue(report.copyText.contains("no network page probe"))
    }

    @Test
    fun blankLabInputReturnsIdleReport() {
        val report = MediaSniffingLab.inspect(MediaSniffingLabRequest(rawInput = "   "))

        assertFalse(report.hasCandidates)
        assertEquals("Waiting for input", report.statusLabel)
        assertTrue(report.copyText.contains("Waiting for input"))
    }
}
