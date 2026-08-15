package com.mikeyphw.xdm.android.media

import com.mikeyphw.xdm.android.model.BrowserCaptureCandidateSummary
import com.mikeyphw.xdm.android.model.BrowserCaptureSessionSummary
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserCaptureSessionRegistryTest {
    @Test
    fun persistsOnlyNonSecretGroupingMetadataAndRestoresIt() {
        val root = Files.createTempDirectory("browser-capture-session-registry").toFile()
        try {
            val registry = BrowserCaptureSessionRegistry(root)
            registry.record(
                BrowserCaptureSessionSummary(
                    sessionId = "browser-7-session",
                    revision = 42,
                    pageTitle = "Example video",
                    pageHost = "example.test",
                    createdAtEpochMs = 100,
                    updatedAtEpochMs = 200,
                    totalCandidateCount = 2,
                    importedCandidateCount = 2,
                    truncated = false,
                    candidates = listOf(
                        BrowserCaptureCandidateSummary("capture-1", "media-1", "strong", "manifest", "hls", listOf("playback", "web-request")),
                        BrowserCaptureCandidateSummary("capture-2", "media-2", "strong", "direct-video", "video", listOf("fetch")),
                    ),
                ),
            )

            val restored = BrowserCaptureSessionRegistry(root).snapshot().single()
            assertEquals("browser-7-session", restored.sessionId)
            assertEquals(setOf("capture-1", "capture-2"), restored.captureIds)
            assertEquals(2, restored.totalCandidateCount)

            val persisted = root.walkTopDown().filter { it.isFile }.joinToString("\n") { it.readText() }
            assertFalse(persisted.contains("https://"))
            assertFalse(persisted.contains("cookie", ignoreCase = true))
            assertFalse(persisted.contains("authorization", ignoreCase = true))
            assertTrue(persisted.contains("capture-1"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun removingLastCaptureRemovesItsSession() {
        val root = Files.createTempDirectory("browser-capture-session-remove").toFile()
        try {
            val registry = BrowserCaptureSessionRegistry(root)
            registry.record(
                BrowserCaptureSessionSummary(
                    sessionId = "browser-session",
                    revision = 1,
                    pageTitle = "Title",
                    pageHost = "example.test",
                    createdAtEpochMs = 1,
                    updatedAtEpochMs = 1,
                    totalCandidateCount = 1,
                    importedCandidateCount = 1,
                    truncated = false,
                    candidates = listOf(BrowserCaptureCandidateSummary("capture-1", "media-1", "strong", "video", "video")),
                ),
            )
            registry.removeCapture("capture-1")
            assertTrue(registry.snapshot().isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }
    @Test
    fun staleRevisionCannotReplaceNewerDurableSession() {
        val root = Files.createTempDirectory("browser-capture-session-stale").toFile()
        try {
            val registry = BrowserCaptureSessionRegistry(root)
            val newer = BrowserCaptureSessionSummary(
                sessionId = "browser-session",
                revision = 9,
                pageTitle = "Newer",
                pageHost = "example.test",
                createdAtEpochMs = 1,
                updatedAtEpochMs = 9,
                totalCandidateCount = 1,
                importedCandidateCount = 1,
                truncated = false,
                candidates = listOf(BrowserCaptureCandidateSummary("capture-new", "media-new", "strong", "video", "video")),
            )
            registry.record(newer)
            registry.record(newer.copy(revision = 8, pageTitle = "Stale", updatedAtEpochMs = 10))

            val restored = BrowserCaptureSessionRegistry(root).snapshot().single()
            assertEquals(9L, restored.revision)
            assertEquals("Newer", restored.pageTitle)
            assertEquals("capture-new", restored.candidates.single().captureId)
        } finally {
            root.deleteRecursively()
        }
    }

}
