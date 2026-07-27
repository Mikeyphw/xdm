package com.mikeyphw.xdm.android.media

import com.mikeyphw.xdm.android.model.MediaSourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaBatchInputParserTest {
    @Test
    fun parserAcceptsLfAndCrlfExtractsTextUrlsAndDedupes() {
        val result = MediaBatchInputParser().parse(
            """
            https://cdn.example.test/movie.mp4
            Some JSON {"url":"https://cdn.example.test/live/master.m3u8?token=abc"}
            duplicate https://cdn.example.test/movie.mp4
            https://video.example.test/watch/episode
            javascript:alert(1)
            nothing useful here
            """.trimIndent(),
        )

        assertEquals(3, result.acceptedCount)
        assertEquals(1, result.duplicateCount)
        assertEquals(2, result.invalidCount)
        assertEquals(2, result.mediaReadyCount)
        assertEquals(1, result.pageInspectionCount)
        assertTrue(result.accepted.any { it.kind == MediaSourceKind.ProgressiveMedia })
        assertTrue(result.accepted.any { it.kind == MediaSourceKind.HlsPlaylist })
        assertTrue(result.accepted.any { it.needsPageInspection })
        assertTrue(result.summaryLabel.contains("3 accepted"))
    }

    @Test
    fun plannerCreatesRecordsOnlyForConcreteMediaCandidates() {
        val planner = MediaBatchIntakePlanner(MediaCaptureService(clock = { 46L }))
        val plan = planner.plan(
            """
            https://cdn.example.test/movie.mp4
            https://video.example.test/watch/episode
            https://cdn.example.test/audio/theme.mp3
            """.trimIndent(),
            pageTitle = "Batch",
            pageUrl = "https://video.example.test/source",
        )

        assertEquals(3, plan.parse.acceptedCount)
        assertEquals(1, plan.parse.pageInspectionCount)
        assertEquals(2, plan.records.size)
        assertEquals(2, plan.variants.size)
        assertTrue(plan.records.all { it.pageUrl == "https://video.example.test/source" })
        assertTrue(plan.hasMediaReady)
    }

    @Test
    fun parserRejectsUnsafeSchemesAndCapsInput() {
        val parser = MediaBatchInputParser(maxInputChars = 24, maxUrls = 10)
        val result = parser.parse("ftp://example.test/file.mp4\nhttps://cdn.example.test/video.mp4")

        assertTrue(result.inputTruncated)
        assertEquals(0, result.acceptedCount)
        assertFalse(result.rejectedLinesText.contains("https://cdn.example.test/video.mp4"))
        assertEquals("Only HTTP and HTTPS media links are supported", result.rejected.single().reason)
    }
}
