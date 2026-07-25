package com.mikeyphw.xdm.android.media

import com.mikeyphw.xdm.android.model.MediaSourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaCaptureIntakePlannerTest {
    private val planner = MediaCaptureIntakePlanner(MediaCaptureService { 42_000L })

    @Test
    fun plansHlsCaptureFromBrowserNeutralFacts() {
        val facts = MediaRequestFacts(
            url = "https://cdn.example.test/live/master.m3u8",
            mimeType = "application/vnd.apple.mpegurl",
            contentLength = 8_192L,
            pageUrl = "https://example.test/watch/7",
            pageTitle = "Concert",
            headers = mapOf("Referer" to "https://example.test/watch/7"),
        )
        val intake = planner.plan(facts) ?: error("Expected media intake")

        assertEquals(facts, intake.facts)
        assertEquals(MediaSourceKind.HlsPlaylist, intake.candidate.kind)
        assertEquals("Concert", intake.record.title)
        assertEquals("https://example.test/watch/7", intake.record.pageUrl)
        assertEquals(42_000L, intake.record.createdAtEpochMs)
        assertTrue(intake.candidate.variants.isNotEmpty())
    }

    @Test
    fun rejectsOrdinaryWebPages() {
        assertNull(
            planner.plan(
                MediaRequestFacts(
                    url = "https://example.test/articles/news",
                    mimeType = "text/html",
                ),
            ),
        )
    }
}
