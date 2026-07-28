package com.mikeyphw.xdm.android.model

import java.io.File
import java.util.zip.ZipFile
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugEventModelsTest {
    @Test
    fun redactorRemovesSecretsFromEventJson() {
        val event = DebugEvent(
            sessionId = "test",
            timestampMillis = 1L,
            area = DebugArea.MediaSniffing,
            severity = DebugSeverity.Info,
            action = "sniff https://cdn.example/video.m3u8?token=secret-token",
            result = "Bearer abcdefghijklmnopqrstuvwxyz",
            safeDetails = mapOf(
                "url" to "https://cdn.example/video.m3u8?token=secret-token&quality=1080",
                "Authorization" to "Bearer abcdefghijklmnopqrstuvwxyz",
                "Cookie" to "sid=secret-cookie",
            ),
        )

        val json = event.toJsonLine()

        assertTrue(json.contains("token=<redacted>"))
        assertTrue(json.contains("Authorization\":\"<redacted>"))
        assertTrue(json.contains("Cookie\":\"<redacted>"))
        assertFalse(json.contains("secret-token"))
        assertFalse(json.contains("secret-cookie"))
        assertFalse(json.contains("abcdefghijklmnopqrstuvwxyz"))
    }

    @Test
    fun rollingRecorderWritesBoundedJsonlAndExportsSanitizedBundle() {
        val root = createTempDirectory("xdm-debug-d1").toFile()
        try {
            val recorder = RollingJsonlDebugEventRecorder(
                rootDirectory = root,
                sessionId = "unit",
                maxSessionBytes = 384L,
                retainedSessions = 2,
                clock = object {
                    var value = 100L
                    fun next(): Long = value++
                }::next,
            )
            repeat(8) { index ->
                recorder.record(
                    area = DebugArea.AddDownload,
                    action = "intake-$index",
                    result = "draft-created",
                    safeDetails = mapOf("url" to "https://example.com/file$index.mp4?sig=very-secret"),
                )
            }

            val timeline = recorder.copySanitizedTimeline()
            assertTrue(timeline.contains("sig=<redacted>"))
            assertFalse(timeline.contains("very-secret"))
            assertTrue(File(root, "sessions").listFiles().orEmpty().size <= 2)

            val bundle = recorder.exportSupportBundle(
                File(root, "support.zip"),
                metadata = mapOf("Authorization" to "Bearer abcdefghijklmnop", "version" to "test"),
            )
            ZipFile(bundle).use { zip ->
                val names = zip.entries().asSequence().map { it.name }.toSet()
                assertTrue("debug-session.jsonl" in names)
                assertTrue("debug-metadata.txt" in names)
                assertTrue("redaction-report.txt" in names)
                val metadataText = zip.getInputStream(zip.getEntry("debug-metadata.txt")).reader().readText()
                assertTrue(metadataText.contains("Authorization=<redacted>"))
                assertFalse(metadataText.contains("abcdefghijklmnop"))
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun downloadIntakePlannerEmitsSafeDebugEventsWhenRecorderIsProvided() {
        val events = mutableListOf<DebugEvent>()
        val recorder = object : DebugEventRecorder {
            override fun record(event: DebugEvent) { events += event }
        }
        val planner = DownloadIntakePlanner(
            idFactory = { "debug-draft" },
            debugRecorder = recorder,
        )

        val draft = planner.fromExternal(
            url = "https://example.com/video.mp4?token=secret-token",
            origin = DownloadIntakeOrigin.BrowserExtension,
            sourceLabel = "IronFox",
            mimeType = "video/mp4",
        )

        assertTrue(draft != null)
        assertTrue(events.any { it.area == DebugArea.AddDownload && it.result == "draft-created" })
        val json = events.joinToString("\n") { it.toJsonLine() }
        assertTrue(json.contains("token=<redacted>"))
        assertFalse(json.contains("secret-token"))
    }
}
