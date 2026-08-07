package com.mikeyphw.xdm.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mikeyphw.xdm.android.model.BrowserIntegrationStatus
import com.mikeyphw.xdm.android.model.ClipboardInboxItem
import com.mikeyphw.xdm.android.model.PrivacyDiagnosticsRedactor

enum class DeveloperToolSection(val label: String) {
    RuntimeEngines("Runtime and engines"),
    TermuxAria2("Termux and aria2"),
    MediaPipeline("Media pipeline"),
    DispatchWorkers("Dispatch and workers"),
    PrivacyCleanup("Privacy and cleanup"),
    ValidationRelease("Validation and release"),
    IntakeClipboard("Intake and clipboard"),
    LogsExports("Redacted logs and exports"),
}

@Composable
@UiSurface(UiAudience.Developer, "Inspect gated redacted runtime, engine, media, and release diagnostics")
fun DeveloperToolsWorkspace(
    state: MainUiState,
    browserStatus: BrowserIntegrationStatus,
    clipboardInbox: List<ClipboardInboxItem>,
    onRunAria2SmokeTest: () -> Unit,
    onRepairAria2: () -> Unit,
    onRunTermuxProbe: () -> Unit,
    onRunTermuxRootProbe: () -> Unit,
    onCollectRootDiagnostics: () -> Unit,
    onKillStuckAria2WithRoot: () -> Unit,
    onStartTermuxAria2Daemon: () -> Unit,
    onStopTermuxAria2Daemon: () -> Unit,
    onProbeTermuxAria2Daemon: () -> Unit,
    onRefreshTermuxAria2Tasks: () -> Unit,
    onPauseAllTermuxAria2Tasks: () -> Unit,
    onResumeAllTermuxAria2Tasks: () -> Unit,
    onSaveTermuxAria2Session: () -> Unit,
    onRetryPostProcessing: () -> Unit,
    onClearPostProcessingEvents: () -> Unit,
    onClearTermuxMediaJobs: () -> Unit,
    onPauseTermuxMediaJob: (String) -> Unit,
    onResumeTermuxMediaJob: (String) -> Unit,
    onCancelTermuxMediaJob: (String) -> Unit,
    onForceCancelTermuxMediaJob: (String) -> Unit,
    onRetryTermuxMediaJob: (String) -> Unit,
    onRecoverTermuxMediaPublication: (String) -> Unit,
    onScanClipboardText: (String) -> Unit,
    onAcceptClipboardItem: (ClipboardInboxItem) -> Unit,
    onDismissClipboardItem: (ClipboardInboxItem) -> Unit,
    onOpenRecentActivity: () -> Unit,
    onClearActivityHistory: () -> Unit,
) {
    var sectionName by rememberSaveable { mutableStateOf(DeveloperToolSection.RuntimeEngines.name) }
    val section = runCatching { DeveloperToolSection.valueOf(sectionName) }
        .getOrDefault(DeveloperToolSection.RuntimeEngines)

    Column(Modifier.fillMaxSize()) {
        XdmListCard(
            compact = true,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            XdmCardTitle("Developer workspace")
            XdmSupportingText(
                "Technical controls are intentionally separated from normal download, media, library, activity, and settings flows. Copied output is redacted.",
                maxLines = 4,
            )
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(DeveloperToolSection.entries) { item ->
                FilterChip(
                    selected = section == item,
                    onClick = { sectionName = item.name },
                    label = { Text(item.label) },
                )
            }
        }
        when (section) {
            DeveloperToolSection.RuntimeEngines -> RuntimeAndEnginesSection(state, onRunAria2SmokeTest, onRepairAria2)
            DeveloperToolSection.TermuxAria2 -> TermuxAndAria2Section(
                state = state,
                onRunTermuxProbe = onRunTermuxProbe,
                onRunTermuxRootProbe = onRunTermuxRootProbe,
                onCollectRootDiagnostics = onCollectRootDiagnostics,
                onKillStuckAria2WithRoot = onKillStuckAria2WithRoot,
                onStartTermuxAria2Daemon = onStartTermuxAria2Daemon,
                onStopTermuxAria2Daemon = onStopTermuxAria2Daemon,
                onProbeTermuxAria2Daemon = onProbeTermuxAria2Daemon,
                onRefreshTermuxAria2Tasks = onRefreshTermuxAria2Tasks,
                onPauseAllTermuxAria2Tasks = onPauseAllTermuxAria2Tasks,
                onResumeAllTermuxAria2Tasks = onResumeAllTermuxAria2Tasks,
                onSaveTermuxAria2Session = onSaveTermuxAria2Session,
                onRetryPostProcessing = onRetryPostProcessing,
                onClearPostProcessingEvents = onClearPostProcessingEvents,
                onClearTermuxMediaJobs = onClearTermuxMediaJobs,
                onPauseTermuxMediaJob = onPauseTermuxMediaJob,
                onResumeTermuxMediaJob = onResumeTermuxMediaJob,
                onCancelTermuxMediaJob = onCancelTermuxMediaJob,
                onForceCancelTermuxMediaJob = onForceCancelTermuxMediaJob,
                onRetryTermuxMediaJob = onRetryTermuxMediaJob,
                onRecoverTermuxMediaPublication = onRecoverTermuxMediaPublication,
            )
            DeveloperToolSection.MediaPipeline,
            DeveloperToolSection.DispatchWorkers,
            DeveloperToolSection.PrivacyCleanup,
            DeveloperToolSection.ValidationRelease -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item { ReleaseReadinessSection(state) }
                item { MediaDeveloperToolsSection(state, section) }
            }
            DeveloperToolSection.IntakeClipboard -> IntakeClipboardSection(
                browserStatus = browserStatus,
                clipboardInbox = clipboardInbox,
                onScanClipboardText = onScanClipboardText,
                onAcceptClipboardItem = onAcceptClipboardItem,
                onDismissClipboardItem = onDismissClipboardItem,
            )
            DeveloperToolSection.LogsExports -> LogsAndExportsSection(
                state = state,
                onOpenRecentActivity = onOpenRecentActivity,
                onClearActivityHistory = onClearActivityHistory,
            )
        }
    }
}

@Composable
private fun RuntimeAndEnginesSection(
    state: MainUiState,
    onRunAria2SmokeTest: () -> Unit,
    onRepairAria2: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            XdmListCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        XdmCardTitle("Packaged runtime")
                        XdmSupportingText(state.aria2Diagnostics.detail, maxLines = 4)
                    }
                    StatusPill(state.aria2Diagnostics.status, if (state.aria2Diagnostics.status.contains("ready", true)) XdmStatusTone.Success else XdmStatusTone.Info)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onRunAria2SmokeTest, enabled = state.aria2Diagnostics.canRunSmokeTest && !state.aria2Diagnostics.smokeTestRunning) {
                        Text(if (state.aria2Diagnostics.smokeTestRunning) "Testing…" else "Run aria2 smoke test")
                    }
                    TextButton(onClick = onRepairAria2, enabled = state.aria2Diagnostics.canRepair && !state.aria2Diagnostics.smokeTestRunning) {
                        Text("Repair aria2")
                    }
                }
            }
        }
        item { XdmSectionHeader("Backend matrix") }
        items(state.backendCapabilities, key = { it.backend.name }) { row ->
            XdmListCard(compact = true) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        XdmCardTitle(row.backend.name)
                        XdmSupportingText(row.summary, maxLines = 3)
                    }
                    StatusPill(if (row.available) "Available" else "Unavailable", if (row.available) XdmStatusTone.Success else XdmStatusTone.Warning)
                }
                XdmMetadataText("Protocols ${row.protocols.sorted().joinToString()} • SAF ${row.saf} • media ${row.media}", maxLines = 2)
            }
        }
        if (state.backendMigrations.isNotEmpty()) {
            item { XdmSectionHeader("Recent backend migrations") }
            items(state.backendMigrations.take(6), key = { it.id }) { migration ->
                XdmListCard(compact = true) {
                    XdmCardTitle("${migration.sourceBackend.name} → ${migration.targetBackend.name}")
                    XdmSupportingText(migration.message, maxLines = 3)
                    XdmMetadataText(migration.stage.name)
                }
            }
        }
    }
}

@Composable
private fun TermuxAndAria2Section(
    state: MainUiState,
    onRunTermuxProbe: () -> Unit,
    onRunTermuxRootProbe: () -> Unit,
    onCollectRootDiagnostics: () -> Unit,
    onKillStuckAria2WithRoot: () -> Unit,
    onStartTermuxAria2Daemon: () -> Unit,
    onStopTermuxAria2Daemon: () -> Unit,
    onProbeTermuxAria2Daemon: () -> Unit,
    onRefreshTermuxAria2Tasks: () -> Unit,
    onPauseAllTermuxAria2Tasks: () -> Unit,
    onResumeAllTermuxAria2Tasks: () -> Unit,
    onSaveTermuxAria2Session: () -> Unit,
    onRetryPostProcessing: () -> Unit,
    onClearPostProcessingEvents: () -> Unit,
    onClearTermuxMediaJobs: () -> Unit,
    onPauseTermuxMediaJob: (String) -> Unit,
    onResumeTermuxMediaJob: (String) -> Unit,
    onCancelTermuxMediaJob: (String) -> Unit,
    onForceCancelTermuxMediaJob: (String) -> Unit,
    onRetryTermuxMediaJob: (String) -> Unit,
    onRecoverTermuxMediaPublication: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            TermuxBridgeDiagnosticsCard(
                termux = state.termuxBridge,
                onRunProbe = onRunTermuxProbe,
                onRunRootProbe = onRunTermuxRootProbe,
                onCollectRootDiagnostics = onCollectRootDiagnostics,
                onKillStuckAria2WithRoot = onKillStuckAria2WithRoot,
            )
        }
        item {
            TermuxAria2CockpitCard(
                aria2 = state.termuxAria2,
                onStart = onStartTermuxAria2Daemon,
                onStop = onStopTermuxAria2Daemon,
                onProbe = onProbeTermuxAria2Daemon,
                onRefreshTasks = onRefreshTermuxAria2Tasks,
                onPauseAll = onPauseAllTermuxAria2Tasks,
                onResumeAll = onResumeAllTermuxAria2Tasks,
                onSaveSession = onSaveTermuxAria2Session,
            )
        }
        item {
            TermuxMediaPipelineCard(
                pipeline = state.termuxMediaPipeline,
                onClearCompleted = onClearTermuxMediaJobs,
                onPause = onPauseTermuxMediaJob,
                onResume = onResumeTermuxMediaJob,
                onCancel = onCancelTermuxMediaJob,
                onForceCancel = onForceCancelTermuxMediaJob,
                onRetry = onRetryTermuxMediaJob,
                onRecoverPublication = onRecoverTermuxMediaPublication,
            )
        }
        item {
            PostProcessingAutomationCard(
                automation = state.postProcessingAutomation,
                onEnabledChanged = null,
                onRetryFailed = onRetryPostProcessing,
                onClearEvents = onClearPostProcessingEvents,
            )
        }
    }
}

@Composable
private fun IntakeClipboardSection(
    browserStatus: BrowserIntegrationStatus,
    clipboardInbox: List<ClipboardInboxItem>,
    onScanClipboardText: (String) -> Unit,
    onAcceptClipboardItem: (ClipboardInboxItem) -> Unit,
    onDismissClipboardItem: (ClipboardInboxItem) -> Unit,
) {
    var clipboardText by rememberSaveable { mutableStateOf("") }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            XdmListCard {
                XdmCardTitle("External intake")
                XdmSupportingText(browserStatus.summary, maxLines = 3)
                XdmActionFlowRow {
                    StatusPill(if (browserStatus.shareHandoff) "Share ready" else "Share off", if (browserStatus.shareHandoff) XdmStatusTone.Success else XdmStatusTone.Warning)
                    StatusPill(if (browserStatus.viewHandoff) "Open-link ready" else "Open-link off", if (browserStatus.viewHandoff) XdmStatusTone.Success else XdmStatusTone.Warning)
                    StatusPill("${browserStatus.rejectedHandoffs} rejected", if (browserStatus.rejectedHandoffs > 0) XdmStatusTone.Warning else XdmStatusTone.Neutral)
                }
            }
        }
        item {
            XdmListCard {
                XdmCardTitle("Clipboard scanner")
                androidx.compose.material3.OutlinedTextField(
                    value = clipboardText,
                    onValueChange = { clipboardText = it },
                    label = { Text("Text containing links") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 5,
                )
                Button(
                    onClick = {
                        onScanClipboardText(clipboardText)
                        clipboardText = ""
                    },
                    enabled = clipboardText.isNotBlank(),
                ) { Text("Scan links") }
            }
        }
        if (clipboardInbox.isEmpty()) {
            item {
                XdmListCard(compact = true) {
                    XdmCardTitle("Clipboard inbox is empty")
                    XdmSupportingText("Detected links appear here only after an explicit scan.", maxLines = 2)
                }
            }
        } else {
            items(clipboardInbox, key = { it.id }) { item ->
                XdmListCard(compact = true) {
                    XdmCardTitle(item.title ?: "Detected link")
                    XdmMetadataText(item.status)
                    XdmSupportingText(PrivacyDiagnosticsRedactor.redactUrl(item.url) ?: "Redacted link", maxLines = 2)
                    XdmActionFlowRow {
                        Button(onClick = { onAcceptClipboardItem(item) }) { Text("Review") }
                        TextButton(onClick = { onDismissClipboardItem(item) }) { Text("Dismiss") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReleaseReadinessSection(state: MainUiState) {
    XdmListCard {
        XdmCardTitle("Application release readiness")
        XdmSupportingText(
            "Redacted app-integrity, update-compatibility, and final-release checks. These are engineering signals, not normal download status.",
            maxLines = 4,
        )
        XdmMetadataText("App integrity: ${state.releaseSecurityReport.summary}", maxLines = 2)
        state.releaseSecurityReport.findings.take(3).forEach { finding ->
            XdmMetadataText("${finding.severity.name}: ${finding.title}", maxLines = 2)
        }
        XdmMetadataText("Update compatibility: ${state.installUpdateReadinessReport.summary}", maxLines = 2)
        state.installUpdateReadinessReport.checks.take(3).forEach { check ->
            XdmMetadataText("${check.severity.name}: ${check.title}", maxLines = 2)
        }
        XdmMetadataText("Final gate: ${state.finalReleaseGateReport.summary}", maxLines = 2)
        state.finalReleaseGateReport.checks.take(3).forEach { check ->
            XdmMetadataText("${check.severity.name}: ${check.title}", maxLines = 2)
        }
    }
}

@Composable
private fun LogsAndExportsSection(
    state: MainUiState,
    onOpenRecentActivity: () -> Unit,
    onClearActivityHistory: () -> Unit,
) {
    val context = LocalContext.current
    val selfTestReport = DebugWorkbenchRuntimeSelfTestSuite.fromState(state)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            XdmListCard {
                XdmCardTitle("Redacted support report")
                XdmSupportingText("Safe to copy for troubleshooting. Credential-like headers, cookies, tokens, signatures, and sensitive query values are removed.", maxLines = 4)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { copyTextToClipboard(context, "XDM support report", state.supportReportText) }) { Text("Copy support report") }
                    Button(onClick = { shareTextReport(context, "XDM support report", state.supportReportText) }) { Text("Export support report") }
                }
                Button(onClick = { shareTextReport(context, "XDM runtime self-test report", selfTestReport.copyText) }) { Text("Export self-test report") }
            }
        }
        item {
            OperationalDiagnosticsHeader(
                summary = state.activitySummary,
                diagnosticsExport = state.activityDiagnosticsExport,
                onOpenTimeline = onOpenRecentActivity,
                onClearHistory = onClearActivityHistory,
            )
        }
        item {
            XdmListCard(compact = true) {
                XdmCardTitle("Privacy boundary")
                XdmSupportingText("Clearing Activity removes dismissible history only. Downloads, files, queue definitions, schedules, and unresolved recovery records remain intact.", maxLines = 4)
            }
        }
    }
}
