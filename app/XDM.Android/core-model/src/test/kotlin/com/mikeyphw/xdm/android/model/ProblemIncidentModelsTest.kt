package com.mikeyphw.xdm.android.model

import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProblemIncidentModelsTest {
    @Test
    fun incidentsAreRedactedDeduplicatedAndNotificationCooledDown() {
        val root = createTempDirectory("xdm-problems").toFile()
        try {
            val store = FileProblemIncidentStore(
                rootDirectory = root,
                retainedIncidents = 8,
                notificationCooldownMs = 1_000L,
            )
            val draft = ProblemIncidentDraft(
                area = DebugArea.MediaResolver,
                severity = DebugSeverity.Error,
                title = "Media resolver failed",
                summary = "GET https://cdn.example/video.m3u8?token=secret-token Bearer abcdefghijklmnop",
                suggestedAction = "Retry the capture",
                operationId = "media-op-1",
                downloadId = "download-1",
                dedupeKey = "resolver-open-failed",
            )

            val first = store.upsert(draft, nowEpochMs = 100L)
            assertTrue(first.shouldNotify)
            store.markNotified(first.incident.id, nowEpochMs = 100L)

            val second = store.upsert(draft, nowEpochMs = 200L)
            assertEquals(first.incident.id, second.incident.id)
            assertEquals(2, second.incident.occurrenceCount)
            assertFalse(second.shouldNotify)

            val exported = store.exportText()
            assertTrue(exported.contains("token=<redacted>"))
            assertFalse(exported.contains("secret-token"))
            assertFalse(exported.contains("abcdefghijklmnop"))

            val third = store.upsert(draft, nowEpochMs = 1_500L)
            assertTrue(third.shouldNotify)
            assertEquals(3, third.incident.occurrenceCount)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun resolvedIncidentsCanBeClearedWithoutTouchingActiveOnes() {
        val root = createTempDirectory("xdm-problems-clear").toFile()
        try {
            val store = FileProblemIncidentStore(root, retainedIncidents = 8)
            val one = store.upsert(
                ProblemIncidentDraft(DebugArea.Storage, title = "Storage unavailable", summary = "Folder is not writable", dedupeKey = "storage"),
                nowEpochMs = 10L,
            ).incident
            val two = store.upsert(
                ProblemIncidentDraft(DebugArea.WebView, title = "Renderer stopped", summary = "Renderer exited", dedupeKey = "renderer"),
                nowEpochMs = 20L,
            ).incident

            store.resolve(one.id)
            assertEquals(1, store.clearResolved())
            val remaining = store.loadAll()
            assertEquals(listOf(two.id), remaining.map { it.id })
            assertFalse(remaining.single().resolved)
        } finally {
            root.deleteRecursively()
        }
    }
}
