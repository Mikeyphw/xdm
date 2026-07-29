package com.mikeyphw.xdm.android.model

/** User-visible health for the Debug Workbench shell. This policy is UI-only and does not change downloader behavior. */
enum class DebugWorkbenchCheckState { Pass, Warning, Fail }

fun DebugArea.supportLabel(): String = when (this) {
    DebugArea.BrowserBridge -> "Browser bridge"
    DebugArea.ExternalIntent -> "External handoff"
    DebugArea.AddDownload -> "Add Download"
    DebugArea.MediaSniffing -> "Media sniffing"
    DebugArea.TransferPlanner -> "Transfer planner"
    DebugArea.Scheduler -> "Scheduler"
    DebugArea.Notification -> "Notifications"
    DebugArea.FileOpen -> "Completed-file open"
    DebugArea.Extension -> "Browser extension"
    DebugArea.Validation -> "Validation"
}

fun DebugWorkbenchCheckState.displayLabel(): String = when (this) {
    DebugWorkbenchCheckState.Pass -> "Pass"
    DebugWorkbenchCheckState.Warning -> "Note"
    DebugWorkbenchCheckState.Fail -> "Needs attention"
}

data class DebugWorkbenchCheck(
    val id: String,
    val title: String,
    val state: DebugWorkbenchCheckState,
    val detail: String,
)

data class DebugWorkbenchShellReport(
    val sessionLabel: String,
    val recorderStorageLabel: String,
    val retentionLabel: String,
    val overallLabel: String,
    val supportBundleLabel: String,
    val checks: List<DebugWorkbenchCheck>,
    val debugAreas: List<DebugArea>,
) {
    val passingChecks: Int get() = checks.count { it.state == DebugWorkbenchCheckState.Pass }
    val warningChecks: Int get() = checks.count { it.state == DebugWorkbenchCheckState.Warning }
    val failingChecks: Int get() = checks.count { it.state == DebugWorkbenchCheckState.Fail }

    fun toClipboardReport(): String = buildString {
        appendLine("XDM Debug Workbench")
        appendLine("Status: $overallLabel")
        appendLine("Session: $sessionLabel")
        appendLine("Storage: $recorderStorageLabel")
        appendLine("Retention: $retentionLabel")
        appendLine("Support bundle: $supportBundleLabel")
        appendLine("Checks: $passingChecks passing, $warningChecks warnings, $failingChecks failing")
        appendLine("Areas: ${debugAreas.joinToString { it.supportLabel() }}")
        checks.forEach { check -> appendLine("- ${check.title}: ${check.state.displayLabel()} - ${check.detail}") }
    }.trimEnd()
}

object DebugWorkbenchShellPolicy {
    fun evaluate(
        recorderInstalled: Boolean,
        redactionReady: Boolean,
        supportBundleReady: Boolean,
        instrumentationHooksReady: Boolean,
        supportReportAvailable: Boolean,
        developerOptionsEnabled: Boolean,
        activeDownloads: Int,
        mediaCaptures: Int,
        automationHandoffs: Int,
    ): DebugWorkbenchShellReport {
        val checks = listOf(
            DebugWorkbenchCheck(
                id = "recorder",
                title = "Event recorder",
                state = if (recorderInstalled) DebugWorkbenchCheckState.Pass else DebugWorkbenchCheckState.Fail,
                detail = if (recorderInstalled) "Rolling JSONL sink is installed and app-private." else "Recorder provider is missing.",
            ),
            DebugWorkbenchCheck(
                id = "redaction",
                title = "Redaction",
                state = if (redactionReady) DebugWorkbenchCheckState.Pass else DebugWorkbenchCheckState.Fail,
                detail = if (redactionReady) "Cookie, Authorization, token, session, signature, and key-like values are redacted." else "Redaction gate is unavailable.",
            ),
            DebugWorkbenchCheck(
                id = "support-bundle",
                title = "Support bundle skeleton",
                state = if (supportBundleReady) DebugWorkbenchCheckState.Pass else DebugWorkbenchCheckState.Warning,
                detail = if (supportBundleReady) "Bundle export is local-only and user-shared." else "Bundle export is not wired yet.",
            ),
            DebugWorkbenchCheck(
                id = "hooks",
                title = "Instrumentation hooks",
                state = if (instrumentationHooksReady) DebugWorkbenchCheckState.Pass else DebugWorkbenchCheckState.Warning,
                detail = if (instrumentationHooksReady) "Add Download, browser bridge, media sniffing, batch intake, external review, and file-open fallback hooks exist." else "Runtime hooks are not available.",
            ),
            DebugWorkbenchCheck(
                id = "support-report",
                title = "Copyable report",
                state = if (supportReportAvailable) DebugWorkbenchCheckState.Pass else DebugWorkbenchCheckState.Warning,
                detail = if (supportReportAvailable) "A redacted support report is available for copy." else "No support report has been generated yet.",
            ),
            DebugWorkbenchCheck(
                id = "developer-boundary",
                title = "Developer boundary",
                state = if (developerOptionsEnabled) DebugWorkbenchCheckState.Pass else DebugWorkbenchCheckState.Warning,
                detail = if (developerOptionsEnabled) "Developer tools are visible; Debug Workbench stays in Settings." else "Developer tools are hidden; Debug Workbench remains safe for support use.",
            ),
        )
        val failed = checks.count { it.state == DebugWorkbenchCheckState.Fail }
        val warnings = checks.count { it.state == DebugWorkbenchCheckState.Warning }
        val overall = when {
            failed > 0 -> "Needs attention"
            warnings > 0 -> "Ready with notes"
            else -> "Ready"
        }
        return DebugWorkbenchShellReport(
            sessionLabel = "Private rolling session • $activeDownloads active transfers • $mediaCaptures media captures • $automationHandoffs handoffs",
            recorderStorageLabel = "current.jsonl in app-private files/debug-sessions",
            retentionLabel = "2 MiB active session • last 5 rotated sessions retained",
            overallLabel = overall,
            supportBundleLabel = "Local ZIP skeleton: debug-session.jsonl, debug-metadata.txt, redaction-report.txt",
            checks = checks,
            debugAreas = DebugArea.entries,
        )
    }
}
