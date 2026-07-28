package com.mikeyphw.xdm.android.media

import com.mikeyphw.xdm.android.model.DebugArea
import com.mikeyphw.xdm.android.model.DebugEvent
import com.mikeyphw.xdm.android.model.DebugEventRecorder
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaDebugEventRecorderTest {
    @Test
    fun sniffingEngineEmitsSanitizedDebugEventWhenRecorderIsProvided() {
        val events = mutableListOf<DebugEvent>()
        val recorder = object : DebugEventRecorder {
            override fun record(event: DebugEvent) { events += event }
        }
        val engine = MediaSniffingEngine(debugRecorder = recorder)

        val plan = engine.sniff(
            MediaSniffingInput(
                url = "https://cdn.example.com/master.m3u8?token=secret-token",
                bodyPrefix = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nvideo.m3u8",
                pageUrl = "https://watch.example/show?session=secret-session",
                source = MediaSniffingSource.SharedText,
            ),
        )

        assertTrue(plan.records.isNotEmpty())
        assertTrue(events.any { it.area == DebugArea.MediaSniffing && it.action == "shared-sniff" })
        val json = events.joinToString("\n") { it.toJsonLine() }
        assertTrue(json.contains("token=<redacted>"))
        assertTrue(json.contains("session=<redacted>"))
        assertFalse(json.contains("secret-token"))
        assertFalse(json.contains("secret-session"))
    }

    @Test
    fun mediaBatchPlannerEmitsReviewFirstSummaryEvent() {
        val events = mutableListOf<DebugEvent>()
        val recorder = object : DebugEventRecorder {
            override fun record(event: DebugEvent) { events += event }
        }
        val planner = MediaBatchIntakePlanner(debugRecorder = recorder)

        planner.plan("https://cdn.example.com/video.mp4?sig=secret-sig\nnot a url")

        val json = events.joinToString("\n") { it.toJsonLine() }
        assertTrue(json.contains("batch-intake"))
        assertTrue(json.contains("acceptedCount"))
        assertFalse(json.contains("secret-sig"))
    }
}
