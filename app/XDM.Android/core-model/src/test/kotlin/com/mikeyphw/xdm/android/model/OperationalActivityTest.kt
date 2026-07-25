package com.mikeyphw.xdm.android.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OperationalActivityTest {
    private val now = 1_800_000_000_000L

    @Test
    fun `queue holds become explainable attention events`() {
        val events = OperationalActivityPlanner.timeline(
            storedEvents = emptyList(),
            queueDecisions = listOf(
                QueueDecisionEvent(
                    id = "d1:1:Hold",
                    downloadId = "d1",
                    fileName = "linux.iso",
                    disposition = QueueLaunchDisposition.Hold,
                    reason = QueueHoldReason.WifiRequired,
                    title = "Waiting for Wi-Fi",
                    detail = "This queue is configured to run only on Wi-Fi.",
                    createdAtEpochMs = now,
                ),
            ),
            downloads = emptyList(),
            recoveryRecords = emptyList(),
            verificationRecords = emptyList(),
            finalizationJournals = emptyList(),
            automationCommands = emptyList(),
            nowEpochMs = now,
        )
        val event = events.single()
        assertEquals(OperationalActivityCategory.Network, event.category)
        assertEquals("Start anyway", event.actionLabel)
        assertTrue(event.unresolved)
    }

    @Test
    fun `current failed downloads appear under attention`() {
        val failed = download(
            state = DownloadState.Failed,
            error = "401 authentication required",
        )
        val events = OperationalActivityPlanner.timeline(
            storedEvents = emptyList(),
            queueDecisions = emptyList(),
            downloads = listOf(failed),
            recoveryRecords = emptyList(),
            verificationRecords = emptyList(),
            finalizationJournals = emptyList(),
            automationCommands = emptyList(),
            nowEpochMs = now,
        )
        assertTrue(events.any { it.unresolved && it.title == "Authentication required" })
    }

    @Test
    fun `filters match safe operational fields`() {
        val events = listOf(
            OperationalActivityEvent(
                id = "one",
                fileName = "linux.iso",
                category = OperationalActivityCategory.Transfer,
                severity = OperationalActivitySeverity.Success,
                title = "Download completed",
                detail = "Finalization passed.",
                engine = "Native",
                createdAtEpochMs = now,
            ),
            OperationalActivityEvent(
                id = "two",
                fileName = "movie.mkv",
                category = OperationalActivityCategory.Network,
                severity = OperationalActivitySeverity.Warning,
                title = "Waiting for Wi-Fi",
                detail = "Metered network.",
                createdAtEpochMs = now,
                unresolved = true,
            ),
        )
        val filtered = OperationalActivityPlanner.filter(
            events,
            OperationalActivityFilter(query = "movie", attentionOnly = true, timeRange = OperationalActivityTimeRange.All),
            nowEpochMs = now,
        )
        assertEquals(listOf("two"), filtered.map { it.id })
    }

    @Test
    fun `diagnostics export redacts secrets and credential query values`() {
        val event = OperationalActivityEvent(
            id = "secret",
            category = OperationalActivityCategory.Handoff,
            severity = OperationalActivitySeverity.Error,
            title = "Authorization: Bearer abcdefghijklmnop",
            detail = "https://example.com/file?token=secret-value&quality=high cookie=session-value",
            createdAtEpochMs = now,
            unresolved = true,
        )
        val export = OperationalActivityPlanner.diagnosticsExport(
            OperationalDiagnosticsContext("0.20.0-rc08", 21, "Android 16", 14, listOf("Native", "aria2"), now),
            listOf(event),
        )
        assertTrue("<redacted>" in export)
        assertFalse("abcdefghijklmnop" in export)
        assertFalse("secret-value" in export)
        assertFalse("session-value" in export)
    }

    @Test
    fun `redacted placeholders remain accepted`() {
        val export = OperationalActivityPlanner.diagnosticsExport(
            OperationalDiagnosticsContext("0.20.0-rc08", 21, "Android 16", 14, listOf("Native"), now),
            listOf(
                OperationalActivityEvent(
                    id = "safe",
                    category = OperationalActivityCategory.System,
                    severity = OperationalActivitySeverity.Info,
                    title = "Safe export",
                    detail = "authorization=<redacted> cookie=<redacted>",
                    createdAtEpochMs = now,
                ),
            ),
        )
        assertTrue("authorization=<redacted>" in export)
        assertTrue("cookie=<redacted>" in export)
    }

    private fun download(state: DownloadState, error: String? = null) = Download(
        id = "d1",
        fileName = "file.bin",
        sourceUrl = "https://example.com/file.bin",
        destinationUri = "content://downloads",
        state = state,
        backend = BackendType.Native,
        bytesReceived = 0,
        totalBytes = 100,
        speedBytesPerSecond = 0,
        queueId = "default",
        priority = 0,
        createdAtEpochMs = now - 10_000,
        updatedAtEpochMs = now,
        errorMessage = error,
    )
}
