package com.mikeyphw.xdm.android.media

import com.mikeyphw.xdm.android.model.MediaSourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSniffingEngineTest {
    @Test
    fun snifferExtractsJsonHtmlEscapedCssAndRelativeMediaUrls() {
        val engine = MediaSniffingEngine(MediaCaptureService(clock = { 47L }))
        val plan = engine.sniff(
            MediaSniffingInput(
                url = "https://watch.example.test/player/episode",
                pageUrl = "https://watch.example.test/player/episode",
                pageTitle = "Episode 47",
                bodyPrefix = """
                    <video src="/assets/preview.mp4"></video>
                    <style>.hero { background-image: url('/trailers/feature.webm'); }</style>
                    <script>{"manifest":"https:\/\/cdn.example.test\/show\/master.m3u8?token=abc&sig=keep"}</script>
                    <a href="https://cdn.example.test/adserver/segment-1.ts">ad</a>
                    {"dash":"https\u003A\u002F\u002Fcdn.example.test\u002Fshow\u002Fmanifest.mpd"}
                """.trimIndent(),
                source = MediaSniffingSource.AppPageProbe,
            ),
        )

        assertEquals(4, plan.candidates.size)
        assertTrue(plan.candidates.first().kind == MediaSourceKind.HlsPlaylist || plan.candidates.first().kind == MediaSourceKind.DashManifest)
        assertTrue(plan.candidates.filter { it.kind == MediaSourceKind.HlsPlaylist || it.kind == MediaSourceKind.DashManifest }.all { it.rank > plan.candidates.last().rank })
        assertTrue(plan.candidates.any { it.url == "https://watch.example.test/assets/preview.mp4" })
        assertTrue(plan.candidates.any { it.url == "https://watch.example.test/trailers/feature.webm" })
        assertTrue(plan.candidates.any { it.url.contains("token=abc&sig=keep") })
        assertTrue(plan.candidates.any { it.kind == MediaSourceKind.DashManifest })
        assertFalse(plan.candidates.any { it.url.endsWith("segment-1.ts") })
        assertEquals(4, plan.records.size)
        assertTrue(plan.summary.contains("4 candidates"))
    }

    @Test
    fun bodySignaturesDetectManifestEvenWithoutExtension() {
        val engine = MediaSniffingEngine(MediaCaptureService(clock = { 47L }))
        val hls = engine.sniff(
            MediaSniffingInput(
                url = "https://cdn.example.test/api/playlist?id=42&signature=secret",
                mimeType = "text/plain",
                bodyPrefix = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000\nlow.m3u8",
                source = MediaSniffingSource.NetworkObservation,
            ),
        )
        val dash = engine.sniff(
            MediaSniffingInput(
                url = "https://cdn.example.test/api/manifest?id=42",
                bodyPrefix = "<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\"></MPD>",
                source = MediaSniffingSource.NetworkObservation,
            ),
        )

        assertEquals(MediaSourceKind.HlsPlaylist, hls.candidates.single().kind)
        assertEquals(MediaSourceKind.DashManifest, dash.candidates.single().kind)
        assertTrue(hls.diagnostics.joinToString("\n").contains("signature=<redacted>"))
        assertFalse(hls.diagnostics.joinToString("\n").contains("signature=secret"))
    }

    @Test
    fun snifferRedactsHeadersAndDoesNotExposeCredentials() {
        val plan = MediaSniffingEngine().sniff(
            MediaSniffingInput(
                url = "https://cdn.example.test/movie.mp4?token=rawsecret",
                requestHeaders = mapOf("Authorization" to "Bearer rawsecret", "Cookie" to "sid=rawsecret"),
                source = MediaSniffingSource.BrowserExtension,
            ),
        )
        val diagnostics = plan.diagnostics.joinToString("\n")

        assertEquals(1, plan.candidates.size)
        assertTrue(diagnostics.contains("token=<redacted>"))
        assertFalse(diagnostics.contains("rawsecret"))
        assertFalse(diagnostics.contains("Bearer"))
        assertFalse(diagnostics.contains("Cookie"))
    }
}
