package com.mikeyphw.xdm.android.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugWorkbenchShellModelsTest {
    @Test
    fun reportSummarizesRecorderRedactionAndSupportBundleReadiness() {
        val report = DebugWorkbenchShellPolicy.evaluate(
            recorderInstalled = true,
            redactionReady = true,
            supportBundleReady = true,
            instrumentationHooksReady = true,
            supportReportAvailable = true,
            developerOptionsEnabled = true,
            activeDownloads = 2,
            mediaCaptures = 3,
            automationHandoffs = 4,
        )

        assertEquals("Ready", report.overallLabel)
        assertEquals(6, report.passingChecks)
        assertTrue(report.sessionLabel.contains("2 active transfers"))
        assertTrue(report.sessionLabel.contains("3 media captures"))
        assertTrue(report.sessionLabel.contains("4 handoffs"))
        assertTrue(report.recorderStorageLabel.contains("current.jsonl"))
        assertTrue(report.retentionLabel.contains("2 MiB"))
        assertTrue(report.supportBundleLabel.contains("debug-session.jsonl"))
        assertTrue(report.debugAreas.contains(DebugArea.MediaSniffing))
    }

    @Test
    fun clipboardReportIsHumanReadableAndContainsNoPlaceholderCommands() {
        val report = DebugWorkbenchShellPolicy.evaluate(
            recorderInstalled = true,
            redactionReady = true,
            supportBundleReady = true,
            instrumentationHooksReady = true,
            supportReportAvailable = false,
            developerOptionsEnabled = false,
            activeDownloads = 0,
            mediaCaptures = 0,
            automationHandoffs = 0,
        )
        val text = report.toClipboardReport()
        assertTrue(text.contains("XDM Debug Workbench"))
        assertTrue(text.contains("Support bundle"))
        assertTrue(text.contains("Redaction"))
        assertFalse(text.contains("onClick = {}"))
    }
}
