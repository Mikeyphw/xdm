package com.mikeyphw.xdm.android

import com.mikeyphw.xdm.android.model.DebugRedactor
import com.mikeyphw.xdm.android.model.DownloadIntakeDraft
import com.mikeyphw.xdm.android.model.DownloadIntakeKind
import com.mikeyphw.xdm.android.model.DownloadIntakeOrigin
import com.mikeyphw.xdm.android.model.DownloadReviewPlanner
import java.net.URLEncoder

/** D4 browser handoff debugger summary. It explains state and copyable test data without opening schemes or queueing downloads. */
data class BrowserBridgeDebugReport(
    val readinessLabel: String,
    val schemeLabel: String,
    val exportLabel: String,
    val extensionLabel: String,
    val lastAcceptedLabel: String,
    val lastRejectedLabel: String,
    val generationLabel: String,
    val compatibilityLabels: List<String>,
    val boundaryLabel: String,
    val copyText: String,
)

/** D4 Add Download debugger summary. It mirrors the review planner and never creates a transfer. */
data class AddDownloadDebugReport(
    val statusLabel: String,
    val summary: String,
    val rows: List<DebugReportRow>,
    val boundaryLabel: String,
    val copyText: String,
)

data class DebugReportRow(
    val label: String,
    val value: String,
)

object BrowserBridgeDebugReporter {
    fun summarize(
        status: BrowserBridgeIntegrationStatus,
        diagnostics: BrowserBridgeDiagnosticsPreferences,
        scheme: String,
    ): BrowserBridgeDebugReport {
        val readiness = if (status.isReady) "Ready" else "Needs attention"
        val compatibility = status.compatibilityIssues
            .map(BrowserBridgeDiagnosticsRedactor::sanitize)
            .ifEmpty { listOf("Current") }
        val copy = buildString {
            appendLine("XDM Browser Bridge Debugger")
            appendLine("Readiness: $readiness")
            appendLine("Scheme: ${status.schemeState.displayLabel} - ${BrowserBridgeDiagnosticsRedactor.sanitize(status.schemeDetail)}")
            appendLine("Export access: ${status.safState.displayLabel} - ${BrowserBridgeDiagnosticsRedactor.sanitize(status.safDetail)}")
            appendLine("Extension: ${status.detectorVersion} / contract ${status.contractVersion}")
            compatibility.forEach { issue -> appendLine("Compatibility: $issue") }
            appendLine("Last accepted: ${BrowserBridgeDiagnosticsRedactor.sanitize(diagnostics.lastAcceptedSummary.ifBlank { "None recorded" })}")
            appendLine("Last rejected: ${BrowserBridgeDiagnosticsRedactor.sanitize(diagnostics.lastRejectedSummary.ifBlank { "None recorded" })}")
            appendLine("Last generation: ${BrowserBridgeDiagnosticsRedactor.sanitize(diagnostics.lastGenerationMessage.ifBlank { diagnostics.lastGenerationPhase })}")
            appendLine("Capture test: secure v2 handoff only; use the current generated Firefox extension to exercise encrypted capture.")
            appendLine("Add Download test URI: ${addDownloadTestUri(scheme)}")
            appendLine("Boundary: copy-only diagnostics; no custom scheme is opened from this debugger.")
        }.trimEnd()
        return BrowserBridgeDebugReport(
            readinessLabel = readiness,
            schemeLabel = "Scheme ${status.schemeState.displayLabel}: ${BrowserBridgeDiagnosticsRedactor.sanitize(status.schemeDetail)}",
            exportLabel = "Export ${status.safState.displayLabel}: ${BrowserBridgeDiagnosticsRedactor.sanitize(status.safDetail)}",
            extensionLabel = "Extension ${status.detectorVersion} / contract ${status.contractVersion}",
            lastAcceptedLabel = diagnostics.lastAcceptedSummary.ifBlank { "No accepted handoff recorded" },
            lastRejectedLabel = diagnostics.lastRejectedSummary.ifBlank { "No rejected handoff recorded" },
            generationLabel = diagnostics.lastGenerationMessage.ifBlank { "No generation result recorded" },
            compatibilityLabels = compatibility,
            boundaryLabel = "Copy-only diagnostics. This debugger does not open browser schemes, run probes, or start downloads.",
            copyText = copy,
        )
    }

    private fun addDownloadTestUri(scheme: String): String {
        val encoded = URLEncoder.encode("https://example.test/media/master.m3u8", Charsets.UTF_8.name())
        return "$scheme://add?v=1&url=$encoded&pageUrl=https%3A%2F%2Fexample.test%2Fwatch&pageTitle=Debug%20Probe"
    }
}

object AddDownloadDebugReporter {
    fun summarize(
        draft: DownloadIntakeDraft?,
        destinationUri: String,
    ): AddDownloadDebugReport {
        if (draft == null) {
            return AddDownloadDebugReport(
                statusLabel = "Idle",
                summary = "No external Add Download draft is active.",
                rows = listOf(
                    DebugReportRow("Current draft", "None active"),
                    DebugReportRow("Review gate", "Waiting for a share, browser handoff, clipboard, or manual entry"),
                    DebugReportRow("Queue behavior", "No transfer can start from this debugger"),
                ),
                boundaryLabel = "Review-only. The debugger cannot enqueue downloads.",
                copyText = buildString {
                    appendLine("XDM Add Download Debugger")
                    appendLine("Current draft: none")
                    appendLine("Boundary: review-only; no transfer can start from this debugger.")
                }.trimEnd(),
            )
        }
        val review = DownloadReviewPlanner.plan(
            url = draft.url,
            fileName = draft.fileName,
            mimeType = draft.mimeType,
            destinationUri = destinationUri,
            origin = draft.origin,
        )
        val inspection = when {
            review.canInspectAsMedia -> review.mediaInspectionActionLabel
            review.canStartDirectly -> "Direct review available"
            else -> "Needs review attention"
        }
        val rows = listOf(
            DebugReportRow("Origin", draft.origin.debugLabel()),
            DebugReportRow("Kind", draft.kind.debugLabel()),
            DebugReportRow("Review gate", review.title),
            DebugReportRow("Inspection", inspection),
            DebugReportRow("Destination", if (destinationUri.isBlank()) "Not selected" else "Selected"),
            DebugReportRow("Page context", if (draft.pageUrl.isNullOrBlank()) "Not provided" else "Available"),
            DebugReportRow("File name", draft.fileName.ifBlank { "Will be inferred" }),
        )
        return AddDownloadDebugReport(
            statusLabel = "Draft active",
            summary = review.guidance,
            rows = rows,
            boundaryLabel = "Review-only. Nothing is queued until the user confirms Add to queue.",
            copyText = buildString {
                appendLine("XDM Add Download Debugger")
                appendLine("Status: active draft")
                appendLine("URL: ${DebugRedactor.redactUrl(draft.url)}")
                rows.forEach { row -> appendLine("${row.label}: ${row.value}") }
                appendLine("Boundary: review-only; no transfer starts from this debugger.")
            }.trimEnd(),
        )
    }

    private fun DownloadIntakeOrigin.debugLabel(): String = when (this) {
        DownloadIntakeOrigin.ExternalShare -> "External share"
        DownloadIntakeOrigin.ExternalView -> "External view"
        DownloadIntakeOrigin.ExternalDownloadManager -> "Download manager"
        DownloadIntakeOrigin.BrowserExtension -> "Browser extension"
        DownloadIntakeOrigin.Automation -> "Automation command"
        DownloadIntakeOrigin.Clipboard -> "Clipboard"
        DownloadIntakeOrigin.ManualEntry -> "Manual entry"
        DownloadIntakeOrigin.BuiltInBrowserPage -> "Browser page"
        DownloadIntakeOrigin.BuiltInBrowserDownload -> "Browser download"
    }

    private fun DownloadIntakeKind.debugLabel(): String = when (this) {
        DownloadIntakeKind.DirectFile -> "Direct file"
        DownloadIntakeKind.DirectMedia -> "Direct media"
        DownloadIntakeKind.AdaptiveMedia -> "HLS or DASH media"
        DownloadIntakeKind.Torrent -> "Torrent"
        DownloadIntakeKind.PageOrUnknown -> "Page or unknown"
    }
}
