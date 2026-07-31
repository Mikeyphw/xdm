package com.mikeyphw.xdm.android

import com.mikeyphw.xdm.android.model.DebugWorkbenchShellPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugWorkbenchD6RuntimeSelfTestSuiteTest {
    private val shell = DebugWorkbenchShellPolicy.evaluate(
        recorderInstalled = true,
        redactionReady = true,
        supportBundleReady = true,
        instrumentationHooksReady = true,
        supportReportAvailable = true,
        developerOptionsEnabled = true,
        activeDownloads = 1,
        mediaCaptures = 1,
        automationHandoffs = 1,
    )

    @Test
    fun suiteRunsStaticSelfTestsWithoutStartingWork() {
        val report = DebugWorkbenchRuntimeSelfTestSuite.run(
            shellReport = shell,
            browserBridge = BrowserBridgeIntegrationStatus(schemeState = BrowserBridgeSchemeState.Ready, safState = BrowserBridgeSafState.Ready),
            supportReportText = "ready",
            hasMediaCaptures = true,
        )

        assertTrue(report.checks.any { it.id == "media-sniffer" && it.statusLabel == RuntimeSelfTestLabels.Pass })
        assertTrue(report.checks.any { it.id == "redaction" && it.statusLabel == RuntimeSelfTestLabels.Pass })
        assertTrue(report.checks.any { it.id == "file-open-path" && it.statusLabel == RuntimeSelfTestLabels.Pass })
        assertFalse(report.copyText.contains("secret-token"))
        assertTrue(report.boundaryLabel.contains("does not start downloads"))
    }

    @Test
    fun suiteUsesNotesForMissingDeviceStateInsteadOfFailing() {
        val report = DebugWorkbenchRuntimeSelfTestSuite.run(shellReport = shell)

        assertTrue(report.statusLabel == "Ready with notes" || report.statusLabel == "Ready")
        assertTrue(report.checks.any { it.id == "browser-scheme" && it.statusLabel == RuntimeSelfTestLabels.Note })
        assertTrue(report.checks.any { it.id == "state-context" && it.statusLabel == RuntimeSelfTestLabels.Note })
    }

    @Test
    fun copyReportStaysSanitizedAndHumanReadable() {
        val report = DebugWorkbenchRuntimeSelfTestSuite.run(shellReport = shell, supportReportText = "Authorization: Bearer secret")

        assertTrue(report.copyText.contains("XDM Runtime Self-Test Suite"))
        assertFalse(report.copyText.contains("Bearer secret"))
        assertFalse(report.copyText.contains("RuntimeSelfTestLabels"))
    }


    @Test
    fun exportedSuiteReportIncludesCheckIds() {
        val report = DebugWorkbenchRuntimeSelfTestSuite.run(shellReport = shell, supportReportText = "ready")

        assertTrue(report.copyText.contains("Ran check IDs:"))
        assertTrue(report.copyText.contains("[media-sniffer]"))
        assertTrue(report.copyText.contains("[redaction]"))
        assertFalse(report.copyText.contains("RuntimeSelfTestLabels"))
    }
}
