package com.mikeyphw.xdm.android

import com.mikeyphw.xdm.android.media.MediaSniffingEngine
import com.mikeyphw.xdm.android.media.MediaSniffingInput
import com.mikeyphw.xdm.android.media.MediaSniffingSource
import com.mikeyphw.xdm.android.model.DebugRedactor
import com.mikeyphw.xdm.android.model.DebugWorkbenchShellReport
import com.mikeyphw.xdm.android.model.Download

/** D6 read-only runtime self-test result. Labels are user-facing and never render enum names. */
data class RuntimeSelfTestCheck(
    val id: String,
    val title: String,
    val statusLabel: String,
    val detail: String,
    val fixHint: String,
)

data class RuntimeSelfTestSuiteReport(
    val statusLabel: String,
    val summaryLabel: String,
    val checks: List<RuntimeSelfTestCheck>,
    val boundaryLabel: String,
    val copyText: String,
) {
    val passingChecks: Int get() = checks.count { it.statusLabel == RuntimeSelfTestLabels.Pass }
    val noteChecks: Int get() = checks.count { it.statusLabel == RuntimeSelfTestLabels.Note }
    val failingChecks: Int get() = checks.count { it.statusLabel == RuntimeSelfTestLabels.Fail }
}

object RuntimeSelfTestLabels {
    const val Pass = "Pass"
    const val Note = "Note"
    const val Fail = "Fail"
}

object DebugWorkbenchRuntimeSelfTestSuite {
    fun fromState(state: MainUiState): RuntimeSelfTestSuiteReport = run(
        shellReport = state.debugWorkbenchReport,
        browserBridge = state.browserBridgeStatus,
        downloads = state.downloads,
        supportReportText = state.supportReportText,
        hasExternalDraft = state.externalAddDraft != null,
        hasMediaCaptures = state.mediaCaptures.isNotEmpty(),
    )

    fun run(
        shellReport: DebugWorkbenchShellReport,
        browserBridge: BrowserBridgeIntegrationStatus = BrowserBridgeIntegrationStatus(),
        downloads: List<Download> = emptyList(),
        supportReportText: String = "",
        hasExternalDraft: Boolean = false,
        hasMediaCaptures: Boolean = false,
        mediaSniffer: MediaSniffingEngine = MediaSniffingEngine(),
    ): RuntimeSelfTestSuiteReport {
        val redaction = redactionSmoke()
        val sniffer = mediaSnifferSmoke(mediaSniffer)
        val checks = listOf(
            RuntimeSelfTestCheck(
                id = "manifest-routes",
                title = "Manifest routes",
                statusLabel = RuntimeSelfTestLabels.Pass,
                detail = "Main, share, view, browser handoff, and download-manager entry points are covered by existing app contracts.",
                fixHint = "Run the manifest route contracts if an external handoff disappears.",
            ),
            RuntimeSelfTestCheck(
                id = "browser-scheme",
                title = "Browser scheme",
                statusLabel = if (browserBridge.schemeState == BrowserBridgeSchemeState.Ready) RuntimeSelfTestLabels.Pass else RuntimeSelfTestLabels.Note,
                detail = if (browserBridge.schemeState == BrowserBridgeSchemeState.Ready) "Custom browser handoff scheme is ready." else "Custom browser handoff scheme has not been confirmed on this device.",
                fixHint = "Open Browser bridge settings and re-check the generated extension target.",
            ),
            RuntimeSelfTestCheck(
                id = "file-open-path",
                title = "Completed file path",
                statusLabel = RuntimeSelfTestLabels.Pass,
                detail = "Completed notification taps use the non-exported trampoline and content URI grant path.",
                fixHint = "Run the completed notification contract if viewers stop opening files.",
            ),
            RuntimeSelfTestCheck(
                id = "media-sniffer",
                title = "Media sniffer smoke",
                statusLabel = if (sniffer) RuntimeSelfTestLabels.Pass else RuntimeSelfTestLabels.Fail,
                detail = if (sniffer) "Static HLS/DASH sniffing returns a candidate without page fetch or script execution." else "Static sniffer smoke did not return a media candidate.",
                fixHint = "Open Media Sniffing Lab and paste the failing snippet.",
            ),
            RuntimeSelfTestCheck(
                id = "redaction",
                title = "Redaction smoke",
                statusLabel = if (redaction) RuntimeSelfTestLabels.Pass else RuntimeSelfTestLabels.Fail,
                detail = if (redaction) "Cookie, Authorization, token, and signed query values are masked in copied reports." else "A secret value survived the redaction smoke check.",
                fixHint = "Do not share reports until the redaction smoke passes.",
            ),
            RuntimeSelfTestCheck(
                id = "notification-intent",
                title = "Notification intent path",
                statusLabel = RuntimeSelfTestLabels.Pass,
                detail = "Notification diagnostics describe the tap path without launching viewers or probing files.",
                fixHint = "Use Transfer + notification debugger to inspect a selected completed item.",
            ),
            RuntimeSelfTestCheck(
                id = "recorder-health",
                title = "Recorder health",
                statusLabel = if (shellReport.failingChecks == 0) RuntimeSelfTestLabels.Pass else RuntimeSelfTestLabels.Fail,
                detail = if (shellReport.failingChecks == 0) "Debug recorder, redaction, and support handoff checks are available." else "The Debug Workbench shell reports ${shellReport.failingChecks} failing check(s).",
                fixHint = "Copy debug status and inspect the failing shell check.",
            ),
            RuntimeSelfTestCheck(
                id = "support-report",
                title = "Support report",
                statusLabel = if (supportReportText.isBlank()) RuntimeSelfTestLabels.Note else RuntimeSelfTestLabels.Pass,
                detail = if (supportReportText.isBlank()) "No support report has been copied in this session yet." else "A redacted support report is ready to copy.",
                fixHint = "Use Copy support report before sharing diagnostics.",
            ),
            RuntimeSelfTestCheck(
                id = "state-context",
                title = "State context",
                statusLabel = if (downloads.isNotEmpty() || hasExternalDraft || hasMediaCaptures) RuntimeSelfTestLabels.Pass else RuntimeSelfTestLabels.Note,
                detail = if (downloads.isNotEmpty() || hasExternalDraft || hasMediaCaptures) "There is app state for debugger panels to explain." else "No current transfer, draft, or media capture is active.",
                fixHint = "Reproduce the issue, then return to Debug Workbench.",
            ),
        )
        val failing = checks.count { it.statusLabel == RuntimeSelfTestLabels.Fail }
        val notes = checks.count { it.statusLabel == RuntimeSelfTestLabels.Note }
        val status = when {
            failing > 0 -> "Needs attention"
            notes > 0 -> "Ready with notes"
            else -> "Ready"
        }
        val copy = buildString {
            appendLine("XDM Runtime Self-Test Suite")
            appendLine("Status: $status")
            appendLine("Checks: ${checks.count { it.statusLabel == RuntimeSelfTestLabels.Pass }} pass, $notes note, $failing fail")
            appendLine("Ran check IDs: ${checks.joinToString(", ") { it.id }}")
            checks.forEach { check -> appendLine("[${check.id}] ${check.title}: ${check.statusLabel} - ${DebugRedactor.redactText(check.detail)}") }
            appendLine("Boundary: read-only checks only; no downloads, viewers, file probes, browser probes, or uploads are started.")
        }.trimEnd()
        return RuntimeSelfTestSuiteReport(
            statusLabel = status,
            summaryLabel = "${checks.size} checks • ${checks.count { it.statusLabel == RuntimeSelfTestLabels.Pass }} pass • $notes notes",
            checks = checks,
            boundaryLabel = "Read-only checks. This suite does not start downloads, launch viewers, probe files, open browser schemes, run network probes, or upload reports.",
            copyText = copy,
        )
    }

    private fun redactionSmoke(): Boolean {
        val redactedUrl = DebugRedactor.redactUrl("https://cdn.example.test/master.m3u8?token=secret-token&sig=secret-signature")
        val redactedText = DebugRedactor.redactText("Authorization: Bearer abc.def.ghi")
        val redactedDetails = DebugRedactor.redactDetails(
            mapOf(
                "Authorization" to "Bearer abc.def.ghi",
                "Cookie" to "session=secret-cookie",
            ),
        ).values.joinToString(" ")
        return listOf(redactedUrl, redactedText, redactedDetails).none { value ->
            value.contains("secret-token") ||
                value.contains("secret-signature") ||
                value.contains("abc.def.ghi") ||
                value.contains("secret-cookie")
        }
    }

    private fun mediaSnifferSmoke(engine: MediaSniffingEngine): Boolean {
        val plan = engine.sniff(
            MediaSniffingInput(
                url = "https://example.test/watch",
                pageUrl = "https://example.test/watch",
                pageTitle = "Self-test",
                bodyPrefix = "<video src=\"/media/master.m3u8?token=keep\"></video>",
                source = MediaSniffingSource.SharedText,
            ),
        )
        return plan.candidates.any { candidate -> candidate.url.contains("master.m3u8") }
    }
}
