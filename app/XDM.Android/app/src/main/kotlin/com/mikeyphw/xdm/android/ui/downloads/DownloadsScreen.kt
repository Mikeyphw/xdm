package com.mikeyphw.xdm.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.mikeyphw.xdm.android.model.BackendRecommendation
import com.mikeyphw.xdm.android.model.BackendCapabilityRow
import com.mikeyphw.xdm.android.model.BackendMigrationRecord
import com.mikeyphw.xdm.android.model.BackupRestoreReport
import com.mikeyphw.xdm.android.model.BrowserIntegrationStatus
import com.mikeyphw.xdm.android.model.ChecksumAlgorithm
import com.mikeyphw.xdm.android.model.ChecksumResult
import com.mikeyphw.xdm.android.model.ClipboardInboxItem
import com.mikeyphw.xdm.android.model.VerificationRecord
import com.mikeyphw.xdm.android.model.VerificationStatus
import com.mikeyphw.xdm.android.model.BackendType
import com.mikeyphw.xdm.android.model.Download
import com.mikeyphw.xdm.android.model.DownloadIntakeKind
import com.mikeyphw.xdm.android.model.ConversionPreset
import com.mikeyphw.xdm.android.model.DestinationRule
import com.mikeyphw.xdm.android.model.DestinationRuleMatch
import com.mikeyphw.xdm.android.model.DownloadTag
import com.mikeyphw.xdm.android.model.DownloadTagAssignment
import com.mikeyphw.xdm.android.model.DownloadState
import com.mikeyphw.xdm.android.model.DownloadDashboard
import com.mikeyphw.xdm.android.model.DownloadDashboardOrdering
import com.mikeyphw.xdm.android.model.DownloadDashboardPlanner
import com.mikeyphw.xdm.android.model.DownloadDashboardSection
import com.mikeyphw.xdm.android.model.DownloadReviewPlanner
import com.mikeyphw.xdm.android.model.DownloadReviewReadiness
import com.mikeyphw.xdm.android.model.ExternalUrlPolicy
import com.mikeyphw.xdm.android.model.DuplicateUrlAction
import com.mikeyphw.xdm.android.model.DuplicateUrlRule
import com.mikeyphw.xdm.android.model.HistoryManagementPolicy
import com.mikeyphw.xdm.android.model.HistoryManagementReport
import com.mikeyphw.xdm.android.model.OrganizationPowerToolsReport
import com.mikeyphw.xdm.android.model.OperationalActivitySummary
import com.mikeyphw.xdm.android.model.PostProcessingSettings
import com.mikeyphw.xdm.android.model.ProtocolExpansionReport
import com.mikeyphw.xdm.android.model.ProxyCredentialSettings
import com.mikeyphw.xdm.android.model.ReleasePackagingReport
import com.mikeyphw.xdm.android.model.displayName
import com.mikeyphw.xdm.android.model.DestinationPermission
import com.mikeyphw.xdm.android.model.FilenameConflictPolicy
import com.mikeyphw.xdm.android.model.FinalizationJournal
import com.mikeyphw.xdm.android.model.AutomationCommandStatus
import com.mikeyphw.xdm.android.model.MediaCaptureStatus
import com.mikeyphw.xdm.android.model.MediaCaptureRecord
import com.mikeyphw.xdm.android.model.MediaResolutionStatus
import com.mikeyphw.xdm.android.model.MediaVariant
import com.mikeyphw.xdm.android.model.MediaVariantKind
import com.mikeyphw.xdm.android.media.MediaDownloadPlanner
import com.mikeyphw.xdm.android.media.MediaDownloadPlan
import com.mikeyphw.xdm.android.media.MediaTrackSelection
import com.mikeyphw.xdm.android.media.MediaResolverWorkspace
import com.mikeyphw.xdm.android.media.MediaResolverWorkspacePlanner
import com.mikeyphw.xdm.android.media.MediaResolverStage
import com.mikeyphw.xdm.android.media.MediaResolverHistoryRow
import com.mikeyphw.xdm.android.media.MediaResolverFormatRow
import com.mikeyphw.xdm.android.media.MediaResolverTrackRow
import com.mikeyphw.xdm.android.media.MediaVariantPickerGroup
import com.mikeyphw.xdm.android.media.YtDlpMetadataProbeResult
import com.mikeyphw.xdm.android.media.OfflineMediaLibrarySummary
import com.mikeyphw.xdm.android.media.MediaDownloadStrategy
import com.mikeyphw.xdm.android.media.MediaExecutionLibraryPlanner
import com.mikeyphw.xdm.android.media.MediaExecutionJob
import com.mikeyphw.xdm.android.media.MediaExecutionStage
import com.mikeyphw.xdm.android.media.MediaExternalJobSnapshot
import com.mikeyphw.xdm.android.media.MediaExecutionEnginePlan
import com.mikeyphw.xdm.android.media.MediaDispatchDashboard
import com.mikeyphw.xdm.android.media.MediaDispatchPlan
import com.mikeyphw.xdm.android.media.MediaDispatchReadiness
import com.mikeyphw.xdm.android.media.MediaExecutionDispatcher
import com.mikeyphw.xdm.android.media.MediaQueueTelemetryDeck
import com.mikeyphw.xdm.android.media.MediaQueueTelemetryPlanner
import com.mikeyphw.xdm.android.media.MediaQueueTelemetryTone
import com.mikeyphw.xdm.android.media.MediaQueueActionAvailability
import com.mikeyphw.xdm.android.media.MediaQueueActionDashboard
import com.mikeyphw.xdm.android.media.MediaQueueActionKind
import com.mikeyphw.xdm.android.media.MediaQueueActionPlan
import com.mikeyphw.xdm.android.media.MediaQueueActionPlanner
import com.mikeyphw.xdm.android.media.MediaWorkerBridgeDashboard
import com.mikeyphw.xdm.android.media.MediaWorkerBridgeKind
import com.mikeyphw.xdm.android.media.MediaWorkerBridgePlanner
import com.mikeyphw.xdm.android.media.MediaWorkerBridgeReadiness
import com.mikeyphw.xdm.android.media.MediaWorkerBridgeRequest
import com.mikeyphw.xdm.android.media.MediaTermuxRuntimeAdapter
import com.mikeyphw.xdm.android.media.TermuxRuntimeDashboard
import com.mikeyphw.xdm.android.media.TermuxRuntimeLaunchPlan
import com.mikeyphw.xdm.android.media.MediaNativeDirectDownloadPlanner
import com.mikeyphw.xdm.android.media.NativeDirectDashboard
import com.mikeyphw.xdm.android.media.NativeDirectDownloadRequestPlan
import com.mikeyphw.xdm.android.media.OfflineMediaLibraryItem
import com.mikeyphw.xdm.android.media.MediaOfflineLibraryV2Planner
import com.mikeyphw.xdm.android.media.OfflineLibraryV2Dashboard
import com.mikeyphw.xdm.android.media.OfflineLibraryV2Filter
import com.mikeyphw.xdm.android.media.OfflineLibraryV2Health
import com.mikeyphw.xdm.android.media.MediaPlayerDiagnosticsPlanner
import com.mikeyphw.xdm.android.media.MediaPlayerDiagnosticBucket
import com.mikeyphw.xdm.android.media.MediaPlayerDiagnosticReport
import com.mikeyphw.xdm.android.media.MediaCaptureQualityPlanner
import com.mikeyphw.xdm.android.media.MediaCaptureQualityDashboard
import com.mikeyphw.xdm.android.media.CaptureQualityDisposition
import com.mikeyphw.xdm.android.media.MediaSessionPrivacyAuditPlanner
import com.mikeyphw.xdm.android.media.MediaSessionPrivacyAuditDashboard
import com.mikeyphw.xdm.android.media.MediaPrivacySeverity
import com.mikeyphw.xdm.android.media.MediaMobilePolishPlanner
import com.mikeyphw.xdm.android.media.MediaMobilePolishDashboard
import com.mikeyphw.xdm.android.media.MediaMobileSectionPriority
import com.mikeyphw.xdm.android.media.MediaMobilePolishSignal
import com.mikeyphw.xdm.android.media.MediaFinalValidationGatePlanner
import com.mikeyphw.xdm.android.media.MediaFinalValidationDashboard
import com.mikeyphw.xdm.android.media.MediaFinalValidationSeverity
import com.mikeyphw.xdm.android.storage.DestinationCatalog
import com.mikeyphw.xdm.android.model.QueueDefinition
import com.mikeyphw.xdm.android.model.QueueIntelligenceSummary
import com.mikeyphw.xdm.android.model.QueueNetworkRequirement
import com.mikeyphw.xdm.android.model.QueueRetryStrategy
import com.mikeyphw.xdm.android.model.RecoveryAction
import com.mikeyphw.xdm.android.model.RecoveryClassification
import com.mikeyphw.xdm.android.model.RecoveryRecord
import com.mikeyphw.xdm.android.model.ScheduleRule
import com.mikeyphw.xdm.android.model.SavedSearch
import com.mikeyphw.xdm.android.scheduler.ActiveTransferSummary
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.LocalContext
import com.mikeyphw.xdm.android.model.PrivacyDiagnosticsRedactor
import com.mikeyphw.xdm.android.model.redactedDiagnosticLine
import com.mikeyphw.xdm.android.model.ReleaseReadinessSeverity
import com.mikeyphw.xdm.android.model.FinalReleaseGateSeverity
import com.mikeyphw.xdm.android.model.ReleaseSecuritySeverity
import com.mikeyphw.xdm.android.util.formatBytes
import com.mikeyphw.xdm.android.util.formatSpeed
import com.mikeyphw.xdm.android.termux.TermuxRootMode
import com.mikeyphw.xdm.android.termux.TermuxBridgeStatus
import com.mikeyphw.xdm.android.termux.TermuxAria2CockpitStatus
import com.mikeyphw.xdm.android.termux.TermuxAria2DaemonState
import com.mikeyphw.xdm.android.termux.TermuxMediaJobStatus
import com.mikeyphw.xdm.android.termux.TermuxMediaPipelineStatus
import com.mikeyphw.xdm.android.termux.PostProcessingAutomationStatus
import com.mikeyphw.xdm.android.termux.PostProcessingAutomationEventStatus



@Composable
@UiSurface(UiAudience.User, "Manage downloads and transfer state")
fun DownloadsScreen(
    downloads: List<Download>,
    compact: Boolean,
    active: ActiveTransferSummary,
    queueIntelligence: QueueIntelligenceSummary,
    activitySummary: OperationalActivitySummary,
    capabilities: List<BackendCapabilityRow>,
    checksumResults: List<ChecksumResult>,
    verificationRecords: List<VerificationRecord>,
    historyReport: HistoryManagementReport,
    organizationReport: OrganizationPowerToolsReport,
    tags: List<DownloadTag>,
    tagAssignments: List<DownloadTagAssignment>,
    savedSearches: List<SavedSearch>,
    onTogglePause: (Download) -> Unit,
    onMigrateBackend: (Download) -> Unit,
    onRemoveHistory: (Download) -> Unit,
    onClearFinishedHistory: () -> Unit,
    onArchiveDownloads: (List<Download>, Boolean) -> Unit,
    onBulkPause: (List<Download>) -> Unit,
    onBulkResume: (List<Download>) -> Unit,
    onCreateTag: (String) -> Unit,
    onAssignTag: (Download, DownloadTag) -> Unit,
    onSaveSearch: (String, String, DownloadState?, Boolean) -> Unit,
    onDeleteSavedSearch: (SavedSearch) -> Unit,
    onPauseAll: () -> Unit,
    onResumeAll: () -> Unit,
    onPreviewPostProcessing: (Download) -> Unit,
    onRunPostProcessing: (Download) -> Unit,
    onEvaluateQueueIntelligence: () -> Unit,
    onStartIgnoringQueuePolicy: (Download) -> Unit,
    onOpenActivityAttention: () -> Unit,
    onOpenActivityDecisions: () -> Unit,
) {
    val context = LocalContext.current
    var filter by remember { mutableStateOf<DownloadState?>(null) }
    var query by remember { mutableStateOf("") }
    var ordering by remember { mutableStateOf(DownloadDashboardOrdering.Smart) }
    var showHistoryTools by remember { mutableStateOf(false) }
    var includeArchived by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    val visible = downloads
        .filter { includeArchived || !it.archived }
        .filter { download -> filter == null || download.state == filter }
        .filter { download -> query.isBlank() || download.matchesQuery(query) }
    val dashboard = DownloadDashboardPlanner.plan(visible, ordering)
    val overviewDashboard = DownloadDashboardPlanner.plan(downloads.filter { includeArchived || !it.archived })
    val selectedDownloads = visible.filter { it.id in selectedIds }

    Column(Modifier.fillMaxSize()) {
        DownloadListSummary(
            downloads = downloads,
            active = active,
            queueIntelligence = queueIntelligence,
            activitySummary = activitySummary,
            historyReport = historyReport,
            dashboard = overviewDashboard,
            showHistoryTools = showHistoryTools,
            onToggleHistoryTools = { showHistoryTools = !showHistoryTools },
            onCopyHistory = { copyTextToClipboard(context, "XDM history index", HistoryManagementPolicy.exportIndex(downloads)) },
            onClearFinished = onClearFinishedHistory,
            onPauseAll = onPauseAll,
            onResumeAll = onResumeAll,
            onEvaluateQueueIntelligence = onEvaluateQueueIntelligence,
            onOpenActivityAttention = onOpenActivityAttention,
            onOpenActivityDecisions = onOpenActivityDecisions,
        )
        OrganizationPowerToolsCard(
            report = organizationReport,
            tags = tags,
            tagAssignments = tagAssignments,
            savedSearches = savedSearches,
            visible = visible,
            selected = selectedDownloads,
            query = query,
            filter = filter,
            includeArchived = includeArchived,
            onIncludeArchivedChanged = { includeArchived = it },
            onSelectAllVisible = { selectedIds = visible.map { it.id }.toSet() },
            onClearSelection = { selectedIds = emptySet() },
            onArchiveSelected = { archived -> onArchiveDownloads(selectedDownloads, archived); selectedIds = emptySet() },
            onBulkPause = { onBulkPause(selectedDownloads) },
            onBulkResume = { onBulkResume(selectedDownloads) },
            onCreateTag = onCreateTag,
            onAssignTag = { tag -> selectedDownloads.forEach { onAssignTag(it, tag) } },
            onSaveSearch = onSaveSearch,
            onDeleteSavedSearch = onDeleteSavedSearch,
        )
        DownloadListControls(
            query = query,
            onQueryChanged = { query = it },
            filter = filter,
            onFilterChanged = { filter = it },
            ordering = ordering,
            onOrderingChanged = { ordering = it },
            dashboard = overviewDashboard,
            downloads = downloads,
        )
        if (visible.isEmpty()) {
            val title = if (downloads.isEmpty()) "No downloads" else "No matching downloads"
            val description = if (downloads.isEmpty()) {
                "Add a URL to create the first download."
            } else {
                "Change the search, dashboard ordering, or state filter to widen the list."
            }
            EmptyFeatureScreen(title, description)
        } else {
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp)) {
                dashboard.sections.forEach { section ->
                    item(key = "dashboard-${section.bucket.name}") {
                        DownloadDashboardSectionHeader(section)
                    }
                    items(section.downloads, key = Download::id) { download ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            FilterChip(
                                selected = download.id in selectedIds,
                                onClick = {
                                    selectedIds = if (download.id in selectedIds) selectedIds - download.id else selectedIds + download.id
                                },
                                label = { Text(if (download.id in selectedIds) "Selected" else "Select") },
                            )
                        }
                        DownloadCard(
                            download,
                            compact,
                            capabilities,
                            checksumResults,
                            verificationRecords,
                            onTogglePause,
                            onMigrateBackend,
                            onRemoveHistory,
                            onPreviewPostProcessing,
                            onRunPostProcessing,
                            onStartIgnoringQueuePolicy,
                        )
                    }
                }
            }
        }
    }
}
@Composable
internal fun DownloadListSummary(
    downloads: List<Download>,
    active: ActiveTransferSummary,
    queueIntelligence: QueueIntelligenceSummary,
    activitySummary: OperationalActivitySummary,
    historyReport: HistoryManagementReport,
    dashboard: DownloadDashboard,
    showHistoryTools: Boolean,
    onToggleHistoryTools: () -> Unit,
    onCopyHistory: () -> Unit,
    onClearFinished: () -> Unit,
    onPauseAll: () -> Unit,
    onResumeAll: () -> Unit,
    onEvaluateQueueIntelligence: () -> Unit,
    onOpenActivityAttention: () -> Unit,
    onOpenActivityDecisions: () -> Unit,
) {
    val failed = dashboard.summary.needsAttention
    val completed = dashboard.summary.completed
    val paused = downloads.count { it.state == DownloadState.Paused }
    XdmFlatCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    XdmCardTitle("Download overview", modifier = Modifier.semantics { heading() })
                    XdmSupportingText(
                        "${dashboard.summary.total} total • ${dashboard.summary.active} active • ${dashboard.summary.queued} queued • $completed complete",
                        maxLines = 2,
                    )
                }
                if (active.activeCount > 0) {
                    Button(onClick = onPauseAll) { Text("Pause all") }
                } else if (paused > 0) {
                    Button(onClick = onResumeAll) { Text("Resume all") }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (dashboard.summary.active > 0) XdmStatusBadge("${dashboard.summary.active} active", tone = XdmStatusTone.Info)
                if (dashboard.summary.queued > 0) XdmStatusBadge("${dashboard.summary.queued} queued", tone = XdmStatusTone.Neutral)
                if (failed > 0) XdmStatusBadge("$failed need attention", tone = XdmStatusTone.Error)
                if (dashboard.summary.aggregateSpeedBytesPerSecond > 0) XdmMetricText(dashboard.summary.aggregateSpeedBytesPerSecond.formatSpeed())
                TextButton(onClick = onToggleHistoryTools) { Text(if (showHistoryTools) "Hide history tools" else "History tools") }
            }
            XdmFlatCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            XdmCardTitle("Queue intelligence")
                            XdmSupportingText(queueIntelligence.message, maxLines = 2)
                        }
                        TextButton(onClick = onEvaluateQueueIntelligence) { Text("Evaluate now") }
                    }
                    XdmActionFlowRow {
                        if (queueIntelligence.started > 0) XdmStatusBadge("${queueIntelligence.started} started", tone = XdmStatusTone.Success)
                        if (queueIntelligence.heldForNetwork > 0) XdmStatusBadge("${queueIntelligence.heldForNetwork} network", tone = XdmStatusTone.Info)
                        if (queueIntelligence.heldForPower > 0) XdmStatusBadge("${queueIntelligence.heldForPower} power", tone = XdmStatusTone.Neutral)
                        if (queueIntelligence.heldForStorage > 0) XdmStatusBadge("${queueIntelligence.heldForStorage} storage", tone = XdmStatusTone.Error)
                        if (queueIntelligence.heldForSchedule > 0) XdmStatusBadge("${queueIntelligence.heldForSchedule} schedule", tone = XdmStatusTone.Neutral)
                        if (queueIntelligence.waitingForRetry > 0) XdmStatusBadge("${queueIntelligence.waitingForRetry} retry", tone = XdmStatusTone.Neutral)
                    }
                }
            }
            XdmFlatCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    XdmCardTitle("Operational health")
                    XdmSupportingText("Activity keeps transfer failures and queue decisions searchable without crowding the download list.", maxLines = 2)
                    XdmActionFlowRow {
                        if (activitySummary.unresolved > 0) XdmStatusBadge("${activitySummary.unresolved} need attention", tone = XdmStatusTone.Error)
                        if (activitySummary.policyHolds > 0) XdmStatusBadge("${activitySummary.policyHolds} policy holds", tone = XdmStatusTone.Warning)
                        if (activitySummary.networkHolds > 0) XdmStatusBadge("${activitySummary.networkHolds} network", tone = XdmStatusTone.Info)
                        if (activitySummary.storageHolds > 0) XdmStatusBadge("${activitySummary.storageHolds} storage", tone = XdmStatusTone.Warning)
                    }
                    XdmActionFlowRow {
                        TextButton(onClick = onOpenActivityAttention, enabled = activitySummary.unresolved > 0) { Text("Open attention") }
                        TextButton(onClick = onOpenActivityDecisions, enabled = activitySummary.policyHolds > 0) { Text("Queue decisions") }
                    }
                }
            }
            if (showHistoryTools) {
                XdmSupportingText(historyReport.summary)
                XdmActionFlowRow {
                    TextButton(onClick = onCopyHistory, enabled = downloads.isNotEmpty()) { Text("Copy history index") }
                    TextButton(onClick = onClearFinished, enabled = historyReport.removableHistory > 0) { Text("Clear finished history") }
                }
                XdmMetadataText("History management only removes app records; downloaded files stay in their destination.")
            }
        }
    }
}
@Composable
internal fun OrganizationPowerToolsCard(
    report: OrganizationPowerToolsReport,
    tags: List<DownloadTag>,
    tagAssignments: List<DownloadTagAssignment>,
    savedSearches: List<SavedSearch>,
    visible: List<Download>,
    selected: List<Download>,
    query: String,
    filter: DownloadState?,
    includeArchived: Boolean,
    onIncludeArchivedChanged: (Boolean) -> Unit,
    onSelectAllVisible: () -> Unit,
    onClearSelection: () -> Unit,
    onArchiveSelected: (Boolean) -> Unit,
    onBulkPause: () -> Unit,
    onBulkResume: () -> Unit,
    onCreateTag: (String) -> Unit,
    onAssignTag: (DownloadTag) -> Unit,
    onSaveSearch: (String, String, DownloadState?, Boolean) -> Unit,
    onDeleteSavedSearch: (SavedSearch) -> Unit,
) {
    var tagName by remember { mutableStateOf("") }
    var searchName by remember { mutableStateOf("") }
    var toolsExpanded by remember { mutableStateOf(false) }
    XdmFlatCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    XdmCardTitle("Organization and history tools")
                    XdmSupportingText(report.summary, maxLines = 2)
                }
                TextButton(onClick = { toolsExpanded = !toolsExpanded }) { Text(if (toolsExpanded) "Hide" else "Show") }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                XdmMetadataText("Archived downloads", modifier = Modifier.weight(1f))
                Switch(
                    checked = includeArchived,
                    onCheckedChange = onIncludeArchivedChanged,
                    modifier = Modifier.semantics { stateDescription = if (includeArchived) "Archived downloads shown" else "Archived downloads hidden" },
                )
            }
            XdmMetadataText("${visible.size} visible • ${selected.size} selected • ${tagAssignments.size} tag assignments")
            if (!toolsExpanded) {
                XdmMetadataText("Bulk actions, tags, and saved searches stay tucked away until needed.")
            } else {
                XdmActionFlowRow {
                    TextButton(onClick = onSelectAllVisible, enabled = visible.isNotEmpty()) { Text("Select visible") }
                    if (selected.isNotEmpty()) {
                        TextButton(onClick = onClearSelection) { Text("Clear selection") }
                        TextButton(onClick = { onBulkPause() }) { Text("Pause selected") }
                        TextButton(onClick = { onBulkResume() }) { Text("Resume selected") }
                        TextButton(onClick = { onArchiveSelected(true) }) { Text("Archive selected") }
                        TextButton(onClick = { onArchiveSelected(false) }) { Text("Unarchive selected") }
                    }
                }
                if (selected.isEmpty()) {
                    XdmMetadataText("Select one or more downloads to reveal bulk pause, resume, archive, and tag actions.")
                }
                OutlinedTextField(tagName, { tagName = it }, label = { Text("New tag") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Button(
                    onClick = { onCreateTag(tagName); tagName = "" },
                    enabled = tagName.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Create tag") }
                if (tags.isNotEmpty()) {
                    XdmActionFlowRow {
                        tags.forEach { tag ->
                            FilterChip(
                                selected = selected.any { download -> tagAssignments.any { it.downloadId == download.id && it.tagId == tag.id } },
                                onClick = { onAssignTag(tag) },
                                enabled = selected.isNotEmpty(),
                                label = { Text(tag.name) },
                            )
                        }
                    }
                }
                OutlinedTextField(searchName, { searchName = it }, label = { Text("Saved search name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Button(
                    onClick = { onSaveSearch(searchName, query, filter, includeArchived); searchName = "" },
                    enabled = searchName.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Save search") }
                savedSearches.take(4).forEach { search ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        XdmMetadataText("${search.name}: ${search.query.ifBlank { "All downloads" }}${search.state?.let { " • ${it.uiLabel()}" }.orEmpty()}", modifier = Modifier.weight(1f))
                        TextButton(onClick = { onDeleteSavedSearch(search) }) { Text("Delete") }
                    }
                }
            }
        }
    }
}
@Composable
internal fun DownloadListControls(
    query: String,
    onQueryChanged: (String) -> Unit,
    filter: DownloadState?,
    onFilterChanged: (DownloadState?) -> Unit,
    ordering: DownloadDashboardOrdering,
    onOrderingChanged: (DownloadDashboardOrdering) -> Unit,
    dashboard: DownloadDashboard,
    downloads: List<Download>,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChanged,
            label = { Text("Search downloads") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            supportingText = { Text("Search by file name, label, URL, destination, or backend.") },
        )
        XdmActionFlowRow {
            DownloadFilterChip(
                label = "All ${downloads.size}",
                selected = filter == null,
                onClick = { onFilterChanged(null) },
            )
            listOf(DownloadState.Downloading, DownloadState.Queued, DownloadState.Completed, DownloadState.Failed).forEach { state ->
                val count = downloads.count { it.state == state }
                DownloadFilterChip(
                    label = "${state.uiLabel()} $count",
                    selected = filter == state,
                    onClick = { onFilterChanged(state) },
                )
            }
        }
        XdmActionFlowRow {
            DownloadDashboardOrdering.entries.forEach { value ->
                FilterChip(
                    selected = ordering == value,
                    onClick = { onOrderingChanged(value) },
                    label = { Text(value.label) },
                    modifier = Modifier.semantics { stateDescription = if (ordering == value) "Ordered by ${value.label}" else "Not ordered by ${value.label}" },
                )
            }
        }
        XdmMetadataText(
            "${dashboard.summary.active} active • ${dashboard.summary.queued} queued • ${dashboard.summary.needsAttention} need attention • ${dashboard.summary.completed} completed",
        )
    }
}
@Composable
internal fun DownloadFilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        modifier = Modifier.semantics { stateDescription = if (selected) "$label selected" else "$label not selected" },
    )
}
@Composable
internal fun DownloadDashboardSectionHeader(section: DownloadDashboardSection) {
    XdmFlatCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                XdmCardTitle(section.bucket.label, modifier = Modifier.semantics { heading() })
                XdmSupportingText(section.description, maxLines = 2)
            }
            XdmStatusBadge(
                section.count.toString(),
                tone = when (section.bucket) {
                    com.mikeyphw.xdm.android.model.DownloadDashboardBucket.NeedsAttention -> XdmStatusTone.Error
                    com.mikeyphw.xdm.android.model.DownloadDashboardBucket.Active -> XdmStatusTone.Info
                    com.mikeyphw.xdm.android.model.DownloadDashboardBucket.Completed -> XdmStatusTone.Success
                    else -> XdmStatusTone.Neutral
                },
            )
        }
    }
}
