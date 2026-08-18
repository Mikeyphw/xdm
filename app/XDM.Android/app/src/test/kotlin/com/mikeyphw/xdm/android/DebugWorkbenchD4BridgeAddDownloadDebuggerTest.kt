package com.mikeyphw.xdm.android

import com.mikeyphw.xdm.android.storage.DestinationUris
import com.mikeyphw.xdm.android.model.DownloadIntakeOrigin
import com.mikeyphw.xdm.android.model.DownloadIntakePlanner
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugWorkbenchD4BridgeAddDownloadDebuggerTest {
    @Test
    fun browserBridgeDebuggerCopiesSafeReportWithoutOpeningSchemes() {
        val status = BrowserBridgeIntegrationStatus(
            schemeState = BrowserBridgeSchemeState.Ready,
            schemeDetail = "xdmdownload_debug is registered",
            safState = BrowserBridgeSafState.Ready,
            safDetail = "Export folder retained",
        )
        val diagnostics = BrowserBridgeDiagnosticsPreferences(
            lastAcceptedSummary = "capture • video/mp4 • https://cdn.example.test/path/master.m3u8?token=secret",
            lastRejectedSummary = "Unsafe URL token=secret",
            lastGenerationMessage = "Bearer abcdefghijklmnopqrstuvwxyz",
        )

        val report = BrowserBridgeDebugReporter.summarize(status, diagnostics, "xdmdownload_debug")

        assertTrue(report.readinessLabel.contains("Ready"))
        assertTrue(report.boundaryLabel.contains("does not open browser schemes"))
        assertTrue(report.copyText.contains("Capture test: secure v2 handoff only"))
        assertTrue(report.copyText.contains("Add Download test URI"))
        assertFalse(report.copyText.contains("abcdefghijklmnopqrstuvwxyz"))
        assertFalse(report.copyText.contains("token=secret"))
    }

    @Test
    fun addDownloadDebuggerExplainsActiveDraftWithoutQueueing() {
        val planner = DownloadIntakePlanner(idFactory = { prefix -> "$prefix-d4" })
        val draft = planner.fromExternal(
            url = "https://example.test/watch?id=42&token=secret",
            fileName = "",
            sourceLabel = "Browser extension",
            origin = DownloadIntakeOrigin.BrowserExtension,
            pageTitle = "Watch page",
            pageUrl = "https://example.test/watch?id=42",
        ) ?: error("Expected draft")

        val report = AddDownloadDebugReporter.summarize(draft, DestinationUris.PUBLIC_DOWNLOADS)

        assertTrue(report.statusLabel.contains("Draft"))
        assertTrue(report.rows.any { it.label == "Origin" && it.value == "Browser extension" })
        assertTrue(report.rows.any { it.label == "Page context" && it.value == "Available" })
        assertTrue(report.boundaryLabel.contains("Nothing is queued"))
        assertTrue(report.copyText.contains("review-only"))
        assertFalse(report.copyText.contains("token=secret"))
    }
}
