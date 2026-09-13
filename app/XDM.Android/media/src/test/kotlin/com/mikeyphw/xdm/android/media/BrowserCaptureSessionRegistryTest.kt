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

    @Test
    fun equalRevisionMergesCandidatesInsteadOfReplacingTheSession() {
        val root = Files.createTempDirectory("browser-capture-session-equal").toFile()
        try {
            val registry = BrowserCaptureSessionRegistry(root)
            val base = BrowserCaptureSessionSummary(
                sessionId = "browser-session",
                revision = 12,
                pageTitle = "Title",
                pageHost = "example.test",
                createdAtEpochMs = 10,
                updatedAtEpochMs = 20,
                totalCandidateCount = 1,
                importedCandidateCount = 1,
                truncated = false,
                candidates = listOf(BrowserCaptureCandidateSummary("capture-a", "media-a", "strong", "video", "video", listOf("a"))),
            )
            registry.record(base)
            registry.record(
                base.copy(
                    updatedAtEpochMs = 30,
                    totalCandidateCount = 2,
                    importedCandidateCount = 1,
                    candidates = listOf(BrowserCaptureCandidateSummary("capture-b", "media-b", "strong", "audio", "audio", listOf("b"))),
                ),
            )

            val restored = registry.snapshot().single()
            assertEquals(12L, restored.revision)
            assertEquals(setOf("capture-a", "capture-b"), restored.captureIds)
            assertEquals(2, restored.totalCandidateCount)
            assertEquals(2, restored.importedCandidateCount)
        } finally {
            root.deleteRecursively()
        }
    }

}
