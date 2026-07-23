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
import androidx.compose.material3.Card
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
import com.mikeyphw.xdm.android.model.ConversionPreset
import com.mikeyphw.xdm.android.model.DestinationRule
import com.mikeyphw.xdm.android.model.DestinationRuleMatch
import com.mikeyphw.xdm.android.model.DownloadTag
import com.mikeyphw.xdm.android.model.DownloadTagAssignment
import com.mikeyphw.xdm.android.model.DownloadState
import com.mikeyphw.xdm.android.model.DuplicateUrlAction
import com.mikeyphw.xdm.android.model.DuplicateUrlRule
import com.mikeyphw.xdm.android.model.HistoryManagementPolicy
import com.mikeyphw.xdm.android.model.HistoryManagementReport
import com.mikeyphw.xdm.android.model.OrganizationPowerToolsReport
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
import com.mikeyphw.xdm.android.media.MediaBrowserCaptureQualityPlanner
import com.mikeyphw.xdm.android.media.BrowserCaptureQualityDashboard
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
fun DownloadsScreen(
    downloads: List<Download>,
    compact: Boolean,
    active: ActiveTransferSummary,
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
) {
    val context = LocalContext.current
    var filter by remember { mutableStateOf<DownloadState?>(null) }
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(DownloadSort.Attention) }
    var showHistoryTools by remember { mutableStateOf(false) }
    var includeArchived by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    val visible = downloads
        .filter { includeArchived || !it.archived }
        .filter { download -> filter == null || download.state == filter }
        .filter { download -> query.isBlank() || download.matchesQuery(query) }
        .sortForUi(sort)
    val selectedDownloads = visible.filter { it.id in selectedIds }

    Column(Modifier.fillMaxSize()) {
        DownloadListSummary(
            downloads = downloads,
            active = active,
            historyReport = historyReport,
            showHistoryTools = showHistoryTools,
            onToggleHistoryTools = { showHistoryTools = !showHistoryTools },
            onCopyHistory = { copyTextToClipboard(context, "XDM history index", HistoryManagementPolicy.exportIndex(downloads)) },
            onClearFinished = onClearFinishedHistory,
            onPauseAll = onPauseAll,
            onResumeAll = onResumeAll,
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
            sort = sort,
            onSortChanged = { sort = it },
            downloads = downloads,
        )
        if (visible.isEmpty()) {
            val title = if (downloads.isEmpty()) "No downloads" else "No matching downloads"
            val description = if (downloads.isEmpty()) {
                "Add a URL to create the first download."
            } else {
                "Change the search, sort, or state filter to widen the list."
            }
            EmptyFeatureScreen(title, description)
        } else {
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp)) {
                items(visible, key = Download::id) { download ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(
                            selected = download.id in selectedIds,
                            onClick = {
                                selectedIds = if (download.id in selectedIds) selectedIds - download.id else selectedIds + download.id
                            },
                            label = { Text(if (download.id in selectedIds) "Selected" else "Select") },
                        )
                    }
                    DownloadCard(download, compact, capabilities, checksumResults, verificationRecords, onTogglePause, onMigrateBackend, onRemoveHistory, onPreviewPostProcessing, onRunPostProcessing)
                }
            }
        }
    }
}


@Composable
private fun DownloadListSummary(
    downloads: List<Download>,
    active: ActiveTransferSummary,
    historyReport: HistoryManagementReport,
    showHistoryTools: Boolean,
    onToggleHistoryTools: () -> Unit,
    onCopyHistory: () -> Unit,
    onClearFinished: () -> Unit,
    onPauseAll: () -> Unit,
    onResumeAll: () -> Unit,
) {
    val failed = downloads.count { it.state == DownloadState.Failed || it.state == DownloadState.RecoveryRequired }
    val completed = downloads.count { it.state == DownloadState.Completed }
    val paused = downloads.count { it.state == DownloadState.Paused }
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    XdmCardTitle("Download overview", modifier = Modifier.semantics { heading() })
                    XdmSupportingText(
                        "${downloads.size} total • ${active.activeCount} active • $completed complete • $failed need attention",
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
                if (active.activeCount > 0) XdmStatusBadge("${active.activeCount} active", tone = XdmStatusTone.Info)
                if (active.speedBytesPerSecond > 0) XdmMetricText(active.speedBytesPerSecond.formatSpeed())
                TextButton(onClick = onToggleHistoryTools) { Text(if (showHistoryTools) "Hide history tools" else "History tools") }
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
private fun OrganizationPowerToolsCard(
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
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
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
private fun DownloadListControls(
    query: String,
    onQueryChanged: (String) -> Unit,
    filter: DownloadState?,
    onFilterChanged: (DownloadState?) -> Unit,
    sort: DownloadSort,
    onSortChanged: (DownloadSort) -> Unit,
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
            DownloadSort.entries.forEach { value ->
                FilterChip(
                    selected = sort == value,
                    onClick = { onSortChanged(value) },
                    label = { Text(value.label) },
                    modifier = Modifier.semantics { stateDescription = if (sort == value) "Sorted by ${value.label}" else "Not sorted by ${value.label}" },
                )
            }
        }
    }
}

@Composable
private fun DownloadFilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        modifier = Modifier.semantics { stateDescription = if (selected) "$label selected" else "$label not selected" },
    )
}

@Composable
private fun DownloadCard(
    download: Download,
    compact: Boolean,
    capabilities: List<BackendCapabilityRow>,
    checksumResults: List<ChecksumResult>,
    verificationRecords: List<VerificationRecord>,
    onTogglePause: (Download) -> Unit,
    onMigrateBackend: (Download) -> Unit,
    onRemoveHistory: (Download) -> Unit,
    onPreviewPostProcessing: (Download) -> Unit,
    onRunPostProcessing: (Download) -> Unit,
) {
    val context = LocalContext.current
    var expanded by remember(download.id) { mutableStateOf(false) }
    Card(
        Modifier
            .fillMaxWidth()
            .semantics { contentDescription = download.accessibilitySummary() },
    ) {
        Column(Modifier.padding(if (compact) 10.dp else 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    XdmCardTitle(download.fileName, maxLines = 1)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        XdmStatusBadge(download.state.uiLabel(), tone = download.state.statusTone())
                        XdmMetadataText(download.backend.uiLabel(), maxLines = 1)
                    }
                }
                if (download.state in setOf(DownloadState.Downloading, DownloadState.Connecting, DownloadState.Paused, DownloadState.Failed)) {
                    IconButton(
                        onClick = { onTogglePause(download) },
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                    ) {
                        val paused = download.state == DownloadState.Paused || download.state == DownloadState.Failed
                        val action = if (paused) "Resume" else "Pause"
                        Icon(if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, "$action ${download.fileName}")
                    }
                }
            }
            val totalBytes = download.totalBytes
            if (totalBytes != null) {
                LinearProgressIndicator(
                    progress = { download.progressFraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { stateDescription = download.progressAccessibilitySummary() },
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    XdmMetricText("${download.bytesReceived.formatBytes()} / ${totalBytes.formatBytes()}")
                    XdmMetricText(if (download.speedBytesPerSecond > 0) download.speedBytesPerSecond.formatSpeed() else "Waiting")
                }
            } else {
                XdmMetadataText(download.destinationUri, maxLines = 1)
            }
            download.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, maxLines = if (expanded) Int.MAX_VALUE else 2) }
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide details" else "Details") }
            if (expanded) {
                DownloadDetails(
                    download = download,
                    capabilities = capabilities,
                    checksumResults = checksumResults,
                    verificationRecords = verificationRecords,
                    onMigrateBackend = onMigrateBackend,
                    onRemoveHistory = onRemoveHistory,
                    onCopyUrl = { copyTextToClipboard(context, "XDM source URL", download.sourceUrl) },
                    onCopyFileInfo = { copyTextToClipboard(context, "XDM file info", download.fileManagementSummary()) },
                    onPreviewPostProcessing = { onPreviewPostProcessing(download) },
                    onRunPostProcessing = { onRunPostProcessing(download) },
                )
            }
        }
    }
}

@Composable
private fun DownloadDetails(
    download: Download,
    capabilities: List<BackendCapabilityRow>,
    checksumResults: List<ChecksumResult>,
    verificationRecords: List<VerificationRecord>,
    onMigrateBackend: (Download) -> Unit,
    onRemoveHistory: (Download) -> Unit,
    onCopyUrl: () -> Unit,
    onCopyFileInfo: () -> Unit,
    onPreviewPostProcessing: () -> Unit,
    onRunPostProcessing: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        XdmMetadataText("Destination: ${download.destinationUri}", maxLines = 2)
        XdmMetadataText("Source: ${download.sourceUrl}", maxLines = 2)
        if (download.backendSelectionExplanation.isNotBlank()) {
            XdmSupportingText(download.backendSelectionExplanation, maxLines = 3)
        }
        download.mimeType?.takeIf { it.startsWith("video/") || it.startsWith("audio/") || it.contains("mpegurl") || it.contains("dash") }?.let {
            XdmMetadataText("Media type: $it")
        }
        val latestVerification = verificationRecords.firstOrNull { it.downloadId == download.id }
        val latestChecksum = checksumResults.firstOrNull { it.downloadId == download.id }
        if (download.state == DownloadState.Verifying || download.state == DownloadState.Repairing || latestVerification != null || latestChecksum != null) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val status = latestVerification?.status ?: if (download.state == DownloadState.Verifying) VerificationStatus.Running else VerificationStatus.Pending
                    XdmCardTitle("Verification: ${status.uiLabel()}")
                    latestChecksum?.let { checksum ->
                        val result = when (checksum.matchesExpectation) {
                            true -> "match"
                            false -> "mismatch"
                            null -> "recorded"
                        }
                        XdmMetadataText("${checksum.algorithm.uiLabel()}: $result")
                    }
                    latestVerification?.message?.let { XdmMetadataText(it, maxLines = 2) }
                }
            }
        }

        val targetBackend = when (download.backend) {
            BackendType.Native -> BackendType.Aria2
            BackendType.Aria2 -> BackendType.Native
            BackendType.Automatic -> null
        }
        val targetCapability = capabilities.firstOrNull { it.backend == targetBackend }
        val destinationScheme = download.destinationUri.substringBefore(':').lowercase()
        val documentDestination = destinationScheme in setOf("content", "xdm")
        val targetCompatible = targetCapability?.available == true && (!documentDestination || targetCapability.saf)
        if (
            download.state in setOf(DownloadState.Paused, DownloadState.Failed, DownloadState.RecoveryRequired) &&
            targetBackend != null &&
            targetCompatible
        ) {
            val target = targetBackend.uiLabel()
            Button(
                onClick = { onMigrateBackend(download) },
                modifier = Modifier.sizeIn(minWidth = 96.dp, minHeight = 48.dp),
            ) {
                Text(if (download.bytesReceived > 0) "Restart with $target" else "Switch to $target")
            }
            if (download.bytesReceived > 0) {
                XdmMetadataText("Existing partial bytes are preserved for recovery and are not reused silently.")
            }
        }
        if (download.state in setOf(DownloadState.Completed, DownloadState.Failed, DownloadState.Cancelled)) {
            XdmActionFlowRow {
                TextButton(onClick = onCopyUrl) { Text("Copy URL") }
                TextButton(onClick = onCopyFileInfo) { Text("Copy file info") }
                TextButton(onClick = onPreviewPostProcessing) { Text("Preview rules") }
                TextButton(onClick = onRunPostProcessing) { Text("Run rules") }
                TextButton(onClick = { onRemoveHistory(download) }) { Text("Remove history") }
            }
            XdmMetadataText("Post-processing actions are typed and can use Termux/root only through safe templates.")
        }
    }
}


@Composable
fun AddDownloadScreen(
    destinationUri: String,
    conflictPolicy: FilenameConflictPolicy,
    savedDestinations: List<DestinationPermission>,
    externalDraftId: String? = null,
    initialUrl: String? = null,
    initialFileName: String? = null,
    externalSourceLabel: String? = null,
    onDestinationChanged: (String) -> Unit,
    onSafDestinationSelected: (String) -> Unit,
    onConflictPolicyChanged: (FilenameConflictPolicy) -> Unit,
    onAdd: (String, String, BackendType, String, FilenameConflictPolicy, Boolean, String, ChecksumAlgorithm) -> Unit,
    recommend: (String, String, BackendType, String, FilenameConflictPolicy, Boolean) -> BackendRecommendation,
) {
    var url by remember { mutableStateOf(initialUrl.orEmpty()) }
    var name by remember { mutableStateOf(initialFileName.orEmpty()) }
    var backend by remember { mutableStateOf(BackendType.Automatic) }
    var allowFallback by remember { mutableStateOf(true) }
    var expectedChecksum by remember { mutableStateOf("") }
    var checksumAlgorithm by remember { mutableStateOf(ChecksumAlgorithm.Sha256) }
    var advancedExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(externalDraftId) {
        if (externalDraftId != null) {
            url = initialUrl.orEmpty()
            name = initialFileName.orEmpty()
        }
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { onSafDestinationSelected(it.toString()) }
    }
    val recommendation = url.takeIf(String::isNotBlank)?.let {
        recommend(url, name, backend, destinationUri, conflictPolicy, allowFallback)
    }
    val canSubmit = url.isNotBlank() && destinationUri.isNotBlank() && recommendation?.compatible != false

    Column(Modifier.fillMaxSize().imePadding()) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (externalDraftId != null) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            XdmCardTitle("Link received")
                            XdmSupportingText("Review the shared or browser-provided link, then start the download when ready.")
                            XdmMetadataText("Source: ${externalSourceLabel ?: "External app"}")
                            if (!initialFileName.isNullOrBlank()) XdmMetadataText("Filename suggestion: ${initialFileName.take(96)}")
                            XdmMetadataText("Cookies, tokens, and request headers stay redacted; XDM never auto-queues external handoffs.")
                        }
                    }
                }
            }
            item {
                XdmSupportingText("Paste a URL, choose where it should land, then start the transfer. Advanced backend and verification controls stay folded until needed.")
            }
            item {
                OutlinedTextField(url, { url = it }, label = { Text("URL") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            }
            item {
                OutlinedTextField(
                    name,
                    { name = it },
                    label = { Text("Filename") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    supportingText = { Text("Optional. XDM will infer a name from the URL when this is empty.") },
                )
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        XdmCardTitle("Destination")
                        XdmMetadataText(destinationUri.ifBlank { "Choose where completed files should be saved." }, maxLines = 2)
                        XdmActionFlowRow {
                            DestinationCatalog.available(Build.VERSION.SDK_INT).forEach { choice ->
                                FilterChip(selected = destinationUri == choice.uri, onClick = { onDestinationChanged(choice.uri) }, label = { Text(choice.label) })
                            }
                            savedDestinations.forEach { destination ->
                                FilterChip(selected = destinationUri == destination.uri, onClick = { onDestinationChanged(destination.uri) }, label = { Text(destination.displayName) })
                            }
                        }
                        Button(onClick = { folderPicker.launch(null) }) { Text("Choose folder or SD card") }
                    }
                }
            }
            recommendation?.let { recommendation ->
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                XdmCardTitle("Recommended backend", modifier = Modifier.weight(1f))
                                XdmStatusBadge(recommendation.backend.uiLabel(), tone = if (recommendation.compatible) XdmStatusTone.Success else XdmStatusTone.Error)
                            }
                            XdmSupportingText(recommendation.explanation)
                            if (!recommendation.compatible) {
                                Text(
                                    recommendation.compatibilityIssue ?: "This backend cannot start the transfer.",
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            } else {
                                val fallbackBackend = recommendation.fallbackBackend
                                if (recommendation.fallbackAllowed && fallbackBackend != null) {
                                    XdmMetadataText("Fallback: ${fallbackBackend.uiLabel()}, before task creation only")
                                }
                            }
                        }
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                XdmCardTitle("Advanced download options")
                                XdmMetadataText("Existing-file behavior, backend selection, fallback, and checksum verification.")
                            }
                            TextButton(onClick = { advancedExpanded = !advancedExpanded }) { Text(if (advancedExpanded) "Hide" else "Show") }
                        }
                        if (advancedExpanded) {
                            XdmCardTitle("Existing filename")
                            XdmActionFlowRow {
                                FilenameConflictPolicy.entries.forEach { value ->
                                    FilterChip(selected = conflictPolicy == value, onClick = { onConflictPolicyChanged(value) }, label = { Text(value.uiLabel()) })
                                }
                            }
                            XdmCardTitle("Backend")
                            XdmActionFlowRow {
                                BackendType.entries.forEach { value ->
                                    FilterChip(selected = backend == value, onClick = { backend = value }, label = { Text(value.uiLabel()) })
                                }
                            }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    XdmCardTitle("Compatible fallback")
                                    XdmMetadataText("Allowed only before a backend task owns the destination.")
                                }
                                Switch(checked = allowFallback, onCheckedChange = { allowFallback = it })
                            }
                            XdmCardTitle("Verification")
                            OutlinedTextField(
                                expectedChecksum,
                                { expectedChecksum = it },
                                label = { Text("Expected checksum") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                supportingText = { Text("Optional SHA-256 or SHA-512. A matching checksum is required before final completion.") },
                            )
                            XdmActionFlowRow {
                                ChecksumAlgorithm.entries.forEach { value ->
                                    FilterChip(selected = checksumAlgorithm == value, onClick = { checksumAlgorithm = value }, label = { Text(value.uiLabel()) })
                                }
                            }
                        }
                    }
                }
            }
        }
        Card(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                XdmMetadataText(if (canSubmit) "Ready to add to the default queue." else "Enter a valid URL and destination.")
                Button(
                    onClick = { onAdd(url, name, backend, destinationUri, conflictPolicy, allowFallback, expectedChecksum, checksumAlgorithm) },
                    enabled = canSubmit,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Start download") }
            }
        }
    }
}

@Composable
fun QueuesScreen(
    queues: List<QueueDefinition>,
    onCreateQueue: (String, Int) -> Unit,
    onUpdateQueue: (QueueDefinition, String, Int, Boolean) -> Unit,
    onToggleQueue: (QueueDefinition, Boolean) -> Unit,
    onDeleteQueue: (QueueDefinition) -> Unit,
) {
    var newQueueName by remember { mutableStateOf("") }
    var newQueueLimit by remember { mutableStateOf("3") }
    val newLimit = newQueueLimit.toIntOrNull()?.coerceIn(1, 16) ?: 3

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            XdmListCard {
                XdmCardTitle("Create queue")
                XdmSupportingText("Queues group downloads and set how many transfers may run at the same time.")
                OutlinedTextField(
                    value = newQueueName,
                    onValueChange = { newQueueName = it.take(48) },
                    label = { Text("Queue name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = newQueueLimit,
                    onValueChange = { newQueueLimit = it.filter { char -> char.isDigit() }.take(2) },
                    label = { Text("Concurrent downloads") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    supportingText = { Text("Use 1–16. Higher values may drain battery faster.") },
                )
                XdmActionFlowRow {
                    Button(
                        onClick = {
                            onCreateQueue(newQueueName, newLimit)
                            newQueueName = ""
                            newQueueLimit = "3"
                        },
                    ) { Text("Create queue") }
                    XdmMetadataText("Starts enabled")
                }
            }
        }
        if (queues.isEmpty()) {
            item {
                XdmListCard {
                    XdmCardTitle("No download queues")
                    XdmSupportingText("Create a queue to separate nightly, media, large-file, or low-priority downloads.")
                }
            }
        } else {
            items(queues, key = QueueDefinition::id) { queue ->
                QueueManagementCard(
                    queue = queue,
                    onUpdateQueue = onUpdateQueue,
                    onToggleQueue = onToggleQueue,
                    onDeleteQueue = onDeleteQueue,
                )
            }
        }
    }
}

@Composable
private fun QueueManagementCard(
    queue: QueueDefinition,
    onUpdateQueue: (QueueDefinition, String, Int, Boolean) -> Unit,
    onToggleQueue: (QueueDefinition, Boolean) -> Unit,
    onDeleteQueue: (QueueDefinition) -> Unit,
) {
    var editing by remember(queue.id) { mutableStateOf(false) }
    var draftName by remember(queue.id, editing) { mutableStateOf(queue.name) }
    var draftLimit by remember(queue.id, editing) { mutableStateOf(queue.maxConcurrent.toString()) }
    var draftEnabled by remember(queue.id, editing) { mutableStateOf(queue.isEnabled) }
    val draftLimitNumber = draftLimit.toIntOrNull()?.coerceIn(1, 16) ?: queue.maxConcurrent
    val dirty = draftName.trim() != queue.name || draftLimitNumber != queue.maxConcurrent || draftEnabled != queue.isEnabled

    XdmListCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                XdmCardTitle(queue.name)
                XdmMetadataText("Up to ${queue.maxConcurrent} concurrent download${if (queue.maxConcurrent == 1) "" else "s"}")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusPill(enabledLabel(queue.isEnabled), enabledTone(queue.isEnabled))
                Switch(
                    checked = queue.isEnabled,
                    onCheckedChange = { onToggleQueue(queue, it) },
                    modifier = Modifier.semantics { stateDescription = if (queue.isEnabled) "Queue enabled" else "Queue disabled" },
                )
            }
        }
        if (queue.id == "default") {
            XdmMetadataText("Default queue is protected so new downloads always have a landing place.")
        }
        if (editing) {
            OutlinedTextField(
                value = draftName,
                onValueChange = { draftName = it.take(48) },
                label = { Text("Queue name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = draftLimit,
                onValueChange = { draftLimit = it.filter { char -> char.isDigit() }.take(2) },
                label = { Text("Concurrent downloads") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                supportingText = { Text("Use 1–16. Current effective value: $draftLimitNumber") },
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                XdmSupportingText("Enabled", modifier = Modifier.weight(1f))
                Switch(checked = draftEnabled, onCheckedChange = { draftEnabled = it })
            }
            XdmActionFlowRow {
                Button(
                    onClick = {
                        onUpdateQueue(queue, draftName, draftLimitNumber, draftEnabled)
                        editing = false
                    },
                    enabled = dirty && draftName.isNotBlank(),
                ) { Text("Save queue") }
                TextButton(onClick = { editing = false }) { Text("Cancel") }
                TextButton(onClick = { onDeleteQueue(queue); editing = false }, enabled = queue.id != "default") { Text("Delete queue") }
            }
        } else {
            XdmActionFlowRow {
                TextButton(onClick = { editing = true }) { Text("Edit") }
                TextButton(onClick = { onDeleteQueue(queue) }, enabled = queue.id != "default") { Text("Delete") }
            }
        }
    }
}

@Composable
fun SchedulerScreen(
    rules: List<ScheduleRule>,
    queues: List<QueueDefinition>,
    onCreateSchedule: (String, String?, String) -> Unit,
    onUpdateSchedule: (ScheduleRule, String, String?, Boolean, String) -> Unit,
    onToggleSchedule: (ScheduleRule, Boolean) -> Unit,
    onDeleteSchedule: (ScheduleRule) -> Unit,
) {
    var newScheduleName by remember { mutableStateOf("") }
    var selectedQueueId by remember { mutableStateOf<String?>(queues.firstOrNull()?.id) }
    var unmeteredOnly by remember { mutableStateOf(true) }
    var chargingRequired by remember { mutableStateOf(false) }
    var minimumBattery by remember { mutableStateOf("30") }
    var startTime by remember { mutableStateOf("01:00") }
    var endTime by remember { mutableStateOf("06:00") }
    var days by remember { mutableStateOf("Weekdays") }
    val createConstraints = buildScheduleConstraintsJson(days, startTime, endTime, unmeteredOnly, chargingRequired, minimumBattery)

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            XdmListCard {
                XdmCardTitle("Create schedule")
                XdmSupportingText("Schedules enable queues automatically when time, power, battery, and network conditions line up.")
                OutlinedTextField(
                    value = newScheduleName,
                    onValueChange = { newScheduleName = it.take(48) },
                    label = { Text("Schedule name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                QueuePicker(queues = queues, selectedQueueId = selectedQueueId, onSelected = { selectedQueueId = it })
                ScheduleConditionEditor(
                    days = days,
                    onDaysChanged = { days = it },
                    startTime = startTime,
                    onStartTimeChanged = { startTime = it.take(5) },
                    endTime = endTime,
                    onEndTimeChanged = { endTime = it.take(5) },
                    unmeteredOnly = unmeteredOnly,
                    onUnmeteredOnlyChanged = { unmeteredOnly = it },
                    chargingRequired = chargingRequired,
                    onChargingRequiredChanged = { chargingRequired = it },
                    minimumBattery = minimumBattery,
                    onMinimumBatteryChanged = { minimumBattery = it.filter { char -> char.isDigit() }.take(3) },
                )
                Button(
                    onClick = {
                        onCreateSchedule(newScheduleName, selectedQueueId, createConstraints)
                        newScheduleName = ""
                    },
                ) { Text("Create schedule") }
            }
        }
        if (rules.isEmpty()) {
            item {
                XdmListCard {
                    XdmCardTitle("No schedules")
                    XdmSupportingText("Create a schedule to run queues only during safe windows, such as Wi‑Fi while charging overnight.")
                }
            }
        } else {
            items(rules, key = ScheduleRule::id) { rule ->
                ScheduleManagementCard(
                    rule = rule,
                    queues = queues,
                    onUpdateSchedule = onUpdateSchedule,
                    onToggleSchedule = onToggleSchedule,
                    onDeleteSchedule = onDeleteSchedule,
                )
            }
        }
    }
}

@Composable
private fun QueuePicker(queues: List<QueueDefinition>, selectedQueueId: String?, onSelected: (String?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        XdmMetadataText("Queue")
        XdmActionFlowRow {
            FilterChip(selected = selectedQueueId == null, onClick = { onSelected(null) }, label = { Text("All queues") })
            queues.forEach { queue ->
                FilterChip(selected = selectedQueueId == queue.id, onClick = { onSelected(queue.id) }, label = { Text(queue.name) })
            }
        }
    }
}

@Composable
private fun ScheduleConditionEditor(
    days: String,
    onDaysChanged: (String) -> Unit,
    startTime: String,
    onStartTimeChanged: (String) -> Unit,
    endTime: String,
    onEndTimeChanged: (String) -> Unit,
    unmeteredOnly: Boolean,
    onUnmeteredOnlyChanged: (Boolean) -> Unit,
    chargingRequired: Boolean,
    onChargingRequiredChanged: (Boolean) -> Unit,
    minimumBattery: String,
    onMinimumBatteryChanged: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(days, onDaysChanged, label = { Text("Days") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                startTime,
                onStartTimeChanged,
                label = { Text("Start") },
                modifier = Modifier.weight(1f),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            OutlinedTextField(
                endTime,
                onEndTimeChanged,
                label = { Text("End") },
                modifier = Modifier.weight(1f),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            XdmSupportingText("Unmetered network only", modifier = Modifier.weight(1f))
            Switch(checked = unmeteredOnly, onCheckedChange = onUnmeteredOnlyChanged)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            XdmSupportingText("Charging required", modifier = Modifier.weight(1f))
            Switch(checked = chargingRequired, onCheckedChange = onChargingRequiredChanged)
        }
        OutlinedTextField(
            minimumBattery,
            onMinimumBatteryChanged,
            label = { Text("Minimum battery %") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            supportingText = { Text("Leave blank to ignore battery level.") },
        )
    }
}

@Composable
private fun ScheduleManagementCard(
    rule: ScheduleRule,
    queues: List<QueueDefinition>,
    onUpdateSchedule: (ScheduleRule, String, String?, Boolean, String) -> Unit,
    onToggleSchedule: (ScheduleRule, Boolean) -> Unit,
    onDeleteSchedule: (ScheduleRule) -> Unit,
) {
    var editing by remember(rule.id) { mutableStateOf(false) }
    var draftName by remember(rule.id, editing) { mutableStateOf(rule.name) }
    var draftQueueId by remember(rule.id, editing) { mutableStateOf(rule.queueId) }
    var draftEnabled by remember(rule.id, editing) { mutableStateOf(rule.enabled) }
    var draftDays by remember(rule.id, editing) { mutableStateOf(scheduleString(rule.constraintsJson, "days", "Every day")) }
    var draftStart by remember(rule.id, editing) { mutableStateOf(scheduleString(rule.constraintsJson, "startTime", "")) }
    var draftEnd by remember(rule.id, editing) { mutableStateOf(scheduleString(rule.constraintsJson, "endTime", "")) }
    var draftUnmetered by remember(rule.id, editing) { mutableStateOf(scheduleBoolean(rule.constraintsJson, "unmetered", false)) }
    var draftCharging by remember(rule.id, editing) { mutableStateOf(scheduleBoolean(rule.constraintsJson, "charging", false)) }
    var draftBattery by remember(rule.id, editing) { mutableStateOf(scheduleInt(rule.constraintsJson, "minimumBatteryPercent")?.toString().orEmpty()) }
    val draftConstraints = buildScheduleConstraintsJson(draftDays, draftStart, draftEnd, draftUnmetered, draftCharging, draftBattery)

    XdmListCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                XdmCardTitle(rule.name)
                XdmMetadataText(rule.queueId?.let { id -> "Queue: ${queues.firstOrNull { it.id == id }?.name ?: id}" } ?: "All queues")
                XdmMetadataText(nextRunSummary(rule.enabled, rule.constraintsJson))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusPill(enabledLabel(rule.enabled), enabledTone(rule.enabled))
                Switch(
                    checked = rule.enabled,
                    onCheckedChange = { onToggleSchedule(rule, it) },
                    modifier = Modifier.semantics { stateDescription = if (rule.enabled) "Schedule enabled" else "Schedule disabled" },
                )
            }
        }
        scheduleConstraintSummary(rule.constraintsJson).forEach { summary -> XdmMetadataText(summary) }
        if (editing) {
            OutlinedTextField(draftName, { draftName = it.take(48) }, label = { Text("Schedule name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            QueuePicker(queues = queues, selectedQueueId = draftQueueId, onSelected = { draftQueueId = it })
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                XdmSupportingText("Enabled", modifier = Modifier.weight(1f))
                Switch(checked = draftEnabled, onCheckedChange = { draftEnabled = it })
            }
            ScheduleConditionEditor(
                days = draftDays,
                onDaysChanged = { draftDays = it },
                startTime = draftStart,
                onStartTimeChanged = { draftStart = it.take(5) },
                endTime = draftEnd,
                onEndTimeChanged = { draftEnd = it.take(5) },
                unmeteredOnly = draftUnmetered,
                onUnmeteredOnlyChanged = { draftUnmetered = it },
                chargingRequired = draftCharging,
                onChargingRequiredChanged = { draftCharging = it },
                minimumBattery = draftBattery,
                onMinimumBatteryChanged = { draftBattery = it.filter { char -> char.isDigit() }.take(3) },
            )
            XdmActionFlowRow {
                Button(
                    onClick = {
                        onUpdateSchedule(rule, draftName, draftQueueId, draftEnabled, draftConstraints)
                        editing = false
                    },
                    enabled = draftName.isNotBlank(),
                ) { Text("Save schedule") }
                TextButton(onClick = { editing = false }) { Text("Cancel") }
                TextButton(onClick = { onDeleteSchedule(rule); editing = false }) { Text("Delete schedule") }
            }
        } else {
            XdmActionFlowRow {
                TextButton(onClick = { editing = true }) { Text("Edit") }
                TextButton(onClick = { onDeleteSchedule(rule) }) { Text("Delete") }
            }
        }
    }
}

@Composable
fun MediaInboxScreen(
    captures: List<MediaCaptureRecord>,
    variants: List<MediaVariant>,
    downloads: List<Download>,
    termuxMediaPipeline: TermuxMediaPipelineStatus,
    postProcessingAutomation: PostProcessingAutomationStatus,
    onBrowserMediaRequest: (url: String, pageTitle: String?, pageUrl: String?, mimeType: String?) -> Unit,
    onOpenAddForBrowserUrl: (url: String, pageTitle: String?) -> Unit,
    onDownload: (MediaCaptureRecord, MediaTrackSelection) -> Unit,
    onResumeOrRetryDownload: (Download) -> Unit,
    onResolve: (MediaCaptureRecord) -> Unit,
    onSelectVariant: (MediaCaptureRecord, String) -> Unit,
    onRemove: (MediaCaptureRecord) -> Unit,
    onTermuxMetadata: (MediaCaptureRecord, MediaTrackSelection) -> Unit,
    onTermuxInspect: (MediaCaptureRecord) -> Unit,
    onTermuxYtDlpDownload: (MediaCaptureRecord, MediaTrackSelection) -> Unit,
    onTermuxConvert: (MediaCaptureRecord, ConversionPreset) -> Unit,
    onClearTermuxMediaJobs: () -> Unit,
    onPreviewPostProcessing: (MediaCaptureRecord) -> Unit,
    onRunPostProcessing: (MediaCaptureRecord) -> Unit,
) {
    val context = LocalContext.current
    val mediaPlanner = remember { MediaDownloadPlanner() }
    val executionPlanner = remember { MediaExecutionLibraryPlanner(mediaPlanner) }
    val dispatchPlanner = remember { MediaExecutionDispatcher() }
    val externalJobs = remember(termuxMediaPipeline.jobs) {
        termuxMediaPipeline.jobs.map { job ->
            MediaExternalJobSnapshot(
                id = job.id,
                captureId = job.captureId,
                kindLabel = job.kind.label,
                statusLabel = job.status.label,
                running = job.status == TermuxMediaJobStatus.Queued || job.status == TermuxMediaJobStatus.Running,
                completed = job.status == TermuxMediaJobStatus.Completed,
                failed = job.status == TermuxMediaJobStatus.Failed,
                output = job.output,
                message = job.message,
            )
        }
    }
    val librarySummary = remember(captures, variants) { mediaPlanner.summarizeOfflineLibrary(captures, variants) }
    val libraryItems = remember(captures, downloads, variants) { executionPlanner.offlineLibraryItems(captures, downloads, variants) }
    val executionJobs = remember(captures, downloads, variants, externalJobs) { executionPlanner.executionJobs(captures, downloads, variants, externalJobs) }
    val dispatchPlans = remember(captures, variants, termuxMediaPipeline.enabled) {
        captures.map { capture ->
            val captureVariants = variants.filter { it.captureId == capture.id }.sortedBy { it.position }
            val selection = MediaTrackSelection(videoVariantId = capture.selectedVariantId)
            val spec = executionPlanner.queueSpec(capture, captureVariants, selection, "content://downloads")
            val engine = executionPlanner.enginePlan(spec, Build.VERSION.SDK_INT)
            dispatchPlanner.dispatchPlan(
                spec = spec,
                enginePlan = engine,
                capture = capture,
                termuxReady = termuxMediaPipeline.enabled,
            )
        }
    }
    val dispatchDashboard = remember(dispatchPlans) { dispatchPlanner.aggregate(dispatchPlans) }
    val queueTelemetry = remember(dispatchPlans, executionJobs) { MediaQueueTelemetryPlanner().deck(dispatchPlans, executionJobs) }
    val queueActions = remember(queueTelemetry, dispatchPlans, executionJobs) { MediaQueueActionPlanner().dashboard(queueTelemetry, dispatchPlans, executionJobs) }
    val workerBridge = remember(captures, variants, termuxMediaPipeline.enabled, queueActions) {
        val bridgePlanner = MediaWorkerBridgePlanner()
        val actionPlanner = MediaQueueActionPlanner()
        val requests = captures.map { capture ->
            val captureVariants = variants.filter { it.captureId == capture.id }.sortedBy { it.position }
            val selection = MediaTrackSelection(videoVariantId = capture.selectedVariantId)
            val spec = executionPlanner.queueSpec(capture, captureVariants, selection, "content://downloads")
            val engine = executionPlanner.enginePlan(spec, Build.VERSION.SDK_INT)
            val dispatch = dispatchPlanner.dispatchPlan(spec, engine, capture, termuxReady = termuxMediaPipeline.enabled)
            val actionPlan = queueActions.plans.firstOrNull { it.captureId == capture.id } ?: actionPlanner.actionPlan(dispatch, null)
            bridgePlanner.request(spec, engine, dispatch, actionPlan)
        }
        bridgePlanner.dashboard(requests)
    }
    val termuxRuntime = remember(workerBridge, termuxMediaPipeline.enabled) {
        val tools = if (termuxMediaPipeline.enabled) setOf("yt-dlp", "aria2c", "ffmpeg", "ffprobe") else emptySet()
        val adapter = MediaTermuxRuntimeAdapter()
        adapter.dashboard(workerBridge.requests.map { request -> adapter.launchPlan(request, availableTools = tools) })
    }
    val nativeDirect = remember(workerBridge) {
        val planner = MediaNativeDirectDownloadPlanner()
        planner.dashboard(workerBridge.requests.map { request -> planner.plan(request, destinationUri = "content://downloads") })
    }
    val libraryV2 = remember(libraryItems, queueTelemetry) {
        val cleanupIds = queueTelemetry.rows.filter { it.cleanupArmed }.map { it.captureId }.toSet()
        MediaOfflineLibraryV2Planner().dashboard(libraryItems, cleanupArmedCaptureIds = cleanupIds)
    }
    val playerDiagnostics = remember(libraryItems) {
        val planner = MediaPlayerDiagnosticsPlanner()
        libraryItems.mapNotNull { item -> item.toPlaybackCandidate()?.let { planner.report(it) } }
    }
    val browserCaptureQuality = remember(captures, variants) {
        MediaBrowserCaptureQualityPlanner().dashboard(captures, variants)
    }
    val sessionPrivacyAudit = remember(captures, variants, libraryItems, executionJobs, termuxRuntime, nativeDirect, browserCaptureQuality) {
        MediaSessionPrivacyAuditPlanner().audit(
            captures = captures,
            variants = variants,
            libraryItems = libraryItems,
            executionJobs = executionJobs,
            diagnostics = listOf(termuxRuntime.summary, nativeDirect.summary, browserCaptureQuality.summary),
            cleanupLedger = executionJobs.associate { it.captureId to (it.stage == MediaExecutionStage.Completed || it.stage == MediaExecutionStage.Failed || it.stage == MediaExecutionStage.Blocked) },
        )
    }
    var showBrowser by remember { mutableStateOf(false) }
    val mediaMobilePolish = remember(captures, queueTelemetry, queueActions, libraryV2, playerDiagnostics, browserCaptureQuality, sessionPrivacyAudit, showBrowser) {
        MediaMobilePolishPlanner().dashboard(
            captures = captures,
            queueTelemetry = queueTelemetry,
            queueActions = queueActions,
            library = libraryV2,
            playerReports = playerDiagnostics,
            captureQuality = browserCaptureQuality,
            privacyAudit = sessionPrivacyAudit,
            compactPreferred = true,
            browserVisible = showBrowser,
            widthClassLabel = "phone",
        )
    }
    val mediaFinalValidation = remember(mediaMobilePolish, sessionPrivacyAudit, browserCaptureQuality, playerDiagnostics, libraryV2, termuxRuntime, nativeDirect) {
        MediaFinalValidationGatePlanner().dashboard(
            implementedPhases = (18..33).toList(),
            mediaMobilePolish = mediaMobilePolish,
            privacyAudit = sessionPrivacyAudit,
            captureQuality = browserCaptureQuality,
            playerReports = playerDiagnostics,
            library = libraryV2,
            termuxRuntime = termuxRuntime,
            nativeDirect = nativeDirect,
            fullValidationEnabled = true,
            noNewTopLevelRoutes = true,
            keepDebugSymbolsProtected = true,
            warningsAsErrors = true,
        )
    }

    Column(Modifier.fillMaxSize()) {
        XdmListCard(compact = true) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    XdmCardTitle("Media")
                    XdmSupportingText(
                        "Use the built-in browser to capture video, audio, HLS, and DASH links, or review saved media from browser/share handoffs.",
                        maxLines = 3,
                    )
                }
            }
            XdmActionFlowRow {
                FilterChip(selected = !showBrowser, onClick = { showBrowser = false }, label = { Text("Inbox") })
                FilterChip(selected = showBrowser, onClick = { showBrowser = true }, label = { Text("Browser") })
            }
        }

        if (showBrowser) {
            BrowserScreen(
                captures = captures,
                onMediaRequest = onBrowserMediaRequest,
                onOpenMediaInbox = { showBrowser = false },
                onOpenAddForUrl = onOpenAddForBrowserUrl,
                modifier = Modifier.weight(1f),
            )
        } else if (captures.isEmpty()) {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { TermuxMediaPipelineCard(termuxMediaPipeline, onClearTermuxMediaJobs) }
                item { MediaMobilePolishCard(mediaMobilePolish) }
                item { MediaFinalValidationGateCard(mediaFinalValidation) }
                item { MediaExecutionQueueCard(executionJobs) }
                item { MediaDispatchDashboardCard(dispatchDashboard, dispatchPlans) }
                item { MediaQueueTelemetryCard(queueTelemetry) }
                item { MediaQueueActionsCard(queueActions) }
                item { MediaWorkerBridgeCard(workerBridge) }
                item { MediaTermuxRuntimeAdapterCard(termuxRuntime) }
                item { MediaNativeDirectDownloadEngineCard(nativeDirect) }
                item { OfflineLibraryV2Card(libraryV2) }
                item { PlayerDiagnosticsDeckCard(playerDiagnostics) }
                item { BrowserCaptureQualityCard(browserCaptureQuality) }
                item { SessionPrivacyAuditCard(sessionPrivacyAudit) }
                item { OfflineMediaLibraryCard(librarySummary, libraryItems, downloads, onResumeOrRetryDownload) }
                item { PostProcessingAutomationCard(postProcessingAutomation, null, null, null) }
                item { EmptyFeatureScreen("Media inbox", "Share a video, audio, HLS, or DASH URL to capture metadata and queue it safely, or switch to Browser to discover media inside pages.") }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { TermuxMediaPipelineCard(termuxMediaPipeline, onClearTermuxMediaJobs) }
                item { MediaMobilePolishCard(mediaMobilePolish) }
                item { MediaFinalValidationGateCard(mediaFinalValidation) }
                item { MediaExecutionQueueCard(executionJobs) }
                item { MediaDispatchDashboardCard(dispatchDashboard, dispatchPlans) }
                item { MediaQueueTelemetryCard(queueTelemetry) }
                item { MediaQueueActionsCard(queueActions) }
                item { MediaWorkerBridgeCard(workerBridge) }
                item { MediaTermuxRuntimeAdapterCard(termuxRuntime) }
                item { MediaNativeDirectDownloadEngineCard(nativeDirect) }
                item { OfflineLibraryV2Card(libraryV2) }
                item { PlayerDiagnosticsDeckCard(playerDiagnostics) }
                item { BrowserCaptureQualityCard(browserCaptureQuality) }
                item { SessionPrivacyAuditCard(sessionPrivacyAudit) }
                item { OfflineMediaLibraryCard(librarySummary, libraryItems, downloads, onResumeOrRetryDownload) }
                item { PostProcessingAutomationCard(postProcessingAutomation, null, null, null) }
                item {
                    TextButton(
                        onClick = { copyTextToClipboard(context, "XDM Termux media diagnostics", termuxMediaPipeline.diagnosticsSummary()) },
                        modifier = Modifier.sizeIn(minWidth = 96.dp, minHeight = 48.dp),
                    ) { Text("Copy media diagnostics") }
                }
                items(captures, key = MediaCaptureRecord::id) { capture ->
                    val captureVariants = variants.filter { it.captureId == capture.id }.sortedBy { it.position }
                    MediaCaptureCard(
                        capture,
                        captureVariants,
                        onDownload,
                        onResolve,
                        onSelectVariant,
                        onRemove,
                        onTermuxMetadata,
                        onTermuxInspect,
                        onTermuxYtDlpDownload,
                        onTermuxConvert,
                        onPreviewPostProcessing,
                        onRunPostProcessing,
                    )
                }
            }
        }
    }
}



@Composable
private fun MediaFinalValidationGateCard(dashboard: MediaFinalValidationDashboard) {
    Card(Modifier.fillMaxWidth().semantics { contentDescription = "Media final validation gate ${dashboard.summary}" }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            XdmCardTitle("Media final validation gate")
            XdmSupportingText(
                "Phase 33 re-enables validation for the complete Media stack: static validators, Gradle build/test/lint, warning-zero policy, route contracts, Termux/chroot safety, and secret-leak scans.",
                maxLines = 4,
            )
            XdmActionFlowRow {
                StatusPill(if (dashboard.readyForFullValidation) "ready for full validation" else "validation blockers", if (dashboard.readyForFullValidation) XdmStatusTone.Success else XdmStatusTone.Warning)
                dashboard.blockerCount.takeIf { it > 0 }?.let { StatusPill("$it blocker", XdmStatusTone.Error) }
                dashboard.reviewCount.takeIf { it > 0 }?.let { StatusPill("$it review", XdmStatusTone.Warning) }
                StatusPill("${dashboard.commandCount} commands", XdmStatusTone.Info)
                StatusPill(if (dashboard.warningGate) "warning-zero" else "warning review", if (dashboard.warningGate) XdmStatusTone.Success else XdmStatusTone.Warning)
                StatusPill(if (dashboard.noNewTopLevelRoutes) "no new routes" else "route review", if (dashboard.noNewTopLevelRoutes) XdmStatusTone.Success else XdmStatusTone.Error)
                StatusPill(if (dashboard.secretSafe) "secret-safe" else "redaction review", if (dashboard.secretSafe) XdmStatusTone.Success else XdmStatusTone.Error)
            }
            XdmMetadataText(dashboard.summary, maxLines = 3)
            dashboard.checks.take(6).forEach { check ->
                XdmListCard(compact = true) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            XdmMetadataText(check.title, maxLines = 1)
                            XdmSupportingText(check.summary, maxLines = 2)
                            XdmMetadataText(check.evidence, maxLines = 2)
                        }
                        StatusPill(if (check.passing) "pass" else check.severity.label, toneForFinalValidation(check.severity, check.passing))
                    }
                }
            }
            XdmListCard(compact = true) {
                XdmMetadataText("Final command ledger", maxLines = 1)
                dashboard.commands.take(5).forEach { command ->
                    XdmMetadataText("${command.label}: ${command.safePreview}", maxLines = 2)
                }
            }
        }
    }
}

private fun toneForFinalValidation(severity: MediaFinalValidationSeverity, passing: Boolean): XdmStatusTone = when {
    passing -> XdmStatusTone.Success
    severity == MediaFinalValidationSeverity.Blocker -> XdmStatusTone.Error
    severity == MediaFinalValidationSeverity.Review -> XdmStatusTone.Warning
    else -> XdmStatusTone.Neutral
}


@Composable
private fun MediaMobilePolishCard(dashboard: MediaMobilePolishDashboard) {
    Card(Modifier.fillMaxWidth().semantics { contentDescription = "Media mobile polish ${dashboard.summary}" }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            XdmCardTitle("Media mobile polish")
            XdmSupportingText(
                "Phase 32 makes the Media stack phone-friendly with a sticky current-job summary, compact action strip, collapsed diagnostics, explicit empty/offline/error states, accessibility labels, and foldable guidance without adding routes.",
                maxLines = 4,
            )
            XdmActionFlowRow {
                StatusPill(dashboard.mode.label, XdmStatusTone.Info)
                StatusPill("${dashboard.visiblePrimarySectionCount} primary", XdmStatusTone.Neutral)
                dashboard.collapsedDiagnosticsCount.takeIf { it > 0 }?.let { StatusPill("$it collapsed", XdmStatusTone.Info) }
                dashboard.attentionCount.takeIf { it > 0 }?.let { StatusPill("$it attention", XdmStatusTone.Warning) }
                StatusPill(if (dashboard.noTinyScrollIslands) "no tiny scroll islands" else "scroll review", if (dashboard.noTinyScrollIslands) XdmStatusTone.Success else XdmStatusTone.Warning)
                StatusPill(if (dashboard.accessibilityReady) "accessibility-ready" else "accessibility review", if (dashboard.accessibilityReady) XdmStatusTone.Success else XdmStatusTone.Warning)
                StatusPill(if (dashboard.secretSafe) "secret-safe" else "redaction review", if (dashboard.secretSafe) XdmStatusTone.Success else XdmStatusTone.Error)
            }
            XdmListCard(compact = true) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        XdmMetadataText("Sticky current job summary", maxLines = 1)
                        XdmSupportingText(dashboard.currentJob.summary, maxLines = 2)
                        XdmMetadataText(dashboard.currentJob.safeDiagnostic, maxLines = 2)
                    }
                    StatusPill(dashboard.currentJob.primaryActionLabel, if (dashboard.currentJob.attentionRequired) XdmStatusTone.Warning else XdmStatusTone.Success)
                }
            }
            XdmMetadataText(dashboard.emptyStateLabel, maxLines = 2)
            dashboard.sections.take(4).forEach { section ->
                XdmListCard(compact = true) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            XdmMetadataText(section.title, maxLines = 1)
                            XdmSupportingText(section.summary, maxLines = 2)
                            XdmMetadataText(section.accessibilityLabel, maxLines = 2)
                        }
                        StatusPill(section.priority.label, toneForMobilePriority(section.priority))
                    }
                    XdmActionFlowRow {
                        StatusPill("max ${section.recommendedMaxRows}", XdmStatusTone.Neutral)
                        if (section.collapsedByDefault) StatusPill("collapsed", XdmStatusTone.Info)
                    }
                }
            }
            dashboard.recommendations.take(4).forEach { recommendation ->
                XdmMetadataText("${recommendation.signal.label}: ${recommendation.detail}", maxLines = 2)
            }
        }
    }
}

private fun toneForMobilePriority(priority: MediaMobileSectionPriority): XdmStatusTone = when (priority) {
    MediaMobileSectionPriority.Sticky -> XdmStatusTone.Success
    MediaMobileSectionPriority.Primary -> XdmStatusTone.Info
    MediaMobileSectionPriority.Secondary -> XdmStatusTone.Neutral
    MediaMobileSectionPriority.Collapsed -> XdmStatusTone.Neutral
    MediaMobileSectionPriority.HiddenUntilNeeded -> XdmStatusTone.Warning
}

@Composable
private fun BrowserCaptureQualityCard(dashboard: BrowserCaptureQualityDashboard) {
    Card(Modifier.fillMaxWidth().semantics { contentDescription = "Browser capture quality ${dashboard.summary}" }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            XdmCardTitle("Browser capture quality")
            XdmSupportingText(
                "Phase 30 improves sniffing quality by grouping duplicates, suppressing analytics noise, flagging stale sessions, and scoring captured media without storing secret query strings.",
                maxLines = 3,
            )
            XdmActionFlowRow {
                StatusPill("${dashboard.treasureCount} treasure", if (dashboard.treasureCount > 0) XdmStatusTone.Success else XdmStatusTone.Neutral)
                dashboard.noiseCount.takeIf { it > 0 }?.let { StatusPill("$it noise", XdmStatusTone.Warning) }
                dashboard.duplicateCount.takeIf { it > 0 }?.let { StatusPill("$it grouped", XdmStatusTone.Info) }
                dashboard.refreshCount.takeIf { it > 0 }?.let { StatusPill("$it refresh", XdmStatusTone.Warning) }
                dashboard.protectedCount.takeIf { it > 0 }?.let { StatusPill("$it protected", XdmStatusTone.Warning) }
                dashboard.liveCount.takeIf { it > 0 }?.let { StatusPill("$it live", XdmStatusTone.Info) }
                StatusPill(if (dashboard.secretSafe) "secret-safe" else "redaction review", if (dashboard.secretSafe) XdmStatusTone.Success else XdmStatusTone.Warning)
            }
            XdmMetadataText(dashboard.summary, maxLines = 3)
            if (dashboard.empty) {
                XdmMetadataText("No browser capture quality rows yet. Browse or share media to let the sniffer rank captures.", maxLines = 2)
            } else {
                dashboard.rows.take(5).forEach { row ->
                    XdmListCard(compact = true) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                            Column(Modifier.weight(1f)) {
                                XdmMetadataText(row.title, maxLines = 1)
                                XdmSupportingText(row.summary, maxLines = 2)
                                XdmMetadataText(row.safeDiagnostics, maxLines = 3)
                            }
                            StatusPill(row.disposition.label, toneForCaptureQuality(row.disposition))
                        }
                        XdmActionFlowRow {
                            StatusPill("confidence ${row.confidenceScore}", XdmStatusTone.Info)
                            StatusPill(row.sourceHost.ifBlank { "unknown host" }, XdmStatusTone.Neutral)
                            if (row.ignoredByDefault) StatusPill("ignored by default", XdmStatusTone.Warning)
                            if (row.refreshMetadataAvailable) StatusPill("refresh metadata", XdmStatusTone.Warning)
                            row.duplicateOfCaptureId?.let { StatusPill("grouped", XdmStatusTone.Info) }
                        }
                    }
                }
            }
        }
    }
}

private fun toneForCaptureQuality(disposition: CaptureQualityDisposition): XdmStatusTone = when (disposition) {
    CaptureQualityDisposition.Treasure -> XdmStatusTone.Success
    CaptureQualityDisposition.NeedsMetadataRefresh -> XdmStatusTone.Warning
    CaptureQualityDisposition.IgnoreNoise -> XdmStatusTone.Neutral
    CaptureQualityDisposition.GroupWithExisting -> XdmStatusTone.Info
    CaptureQualityDisposition.ProtectedDiagnostic -> XdmStatusTone.Warning
    CaptureQualityDisposition.LiveReview -> XdmStatusTone.Info
}

@Composable
private fun SessionPrivacyAuditCard(dashboard: MediaSessionPrivacyAuditDashboard) {
    Card(Modifier.fillMaxWidth().semantics { contentDescription = "Session privacy audit ${dashboard.summary}" }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            XdmCardTitle("Session privacy audit")
            XdmSupportingText(
                "Phase 31 audits browser sessions, resolver handoffs, queue specs, sidecars, logs, notifications, temp files, and Termux previews for secret leaks and cleanup gaps.",
                maxLines = 3,
            )
            XdmActionFlowRow {
                dashboard.blockerCount.takeIf { it > 0 }?.let { StatusPill("$it blocker", XdmStatusTone.Error) }
                dashboard.reviewCount.takeIf { it > 0 }?.let { StatusPill("$it review", XdmStatusTone.Warning) }
                dashboard.cleanupDueCount.takeIf { it > 0 }?.let { StatusPill("$it cleanup due", XdmStatusTone.Warning) }
                dashboard.cleanupVerifiedCount.takeIf { it > 0 }?.let { StatusPill("$it cleanup verified", XdmStatusTone.Success) }
                StatusPill(if (dashboard.durableSecretSafe) "durable secret-safe" else "durable leak blocked", if (dashboard.durableSecretSafe) XdmStatusTone.Success else XdmStatusTone.Error)
                StatusPill(if (dashboard.transientCleanupHealthy) "cleanup healthy" else "cleanup review", if (dashboard.transientCleanupHealthy) XdmStatusTone.Success else XdmStatusTone.Warning)
            }
            XdmMetadataText(dashboard.summary, maxLines = 3)
            if (dashboard.empty) {
                XdmMetadataText("No privacy findings yet. The audit still scans all planned surfaces when media jobs appear.", maxLines = 2)
            } else {
                dashboard.findings.take(6).forEach { finding ->
                    XdmListCard(compact = true) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                            Column(Modifier.weight(1f)) {
                                XdmMetadataText(finding.surface.label, maxLines = 1)
                                XdmSupportingText(finding.remediation, maxLines = 2)
                                XdmMetadataText(finding.redactedPreview, maxLines = 3)
                            }
                            StatusPill(finding.severity.label, toneForPrivacySeverity(finding.severity))
                        }
                        XdmActionFlowRow {
                            StatusPill(finding.cleanupState.label, XdmStatusTone.Neutral)
                            finding.captureId?.let { StatusPill(it.take(18), XdmStatusTone.Info) }
                        }
                    }
                }
            }
        }
    }
}

private fun toneForPrivacySeverity(severity: MediaPrivacySeverity): XdmStatusTone = when (severity) {
    MediaPrivacySeverity.Pass -> XdmStatusTone.Success
    MediaPrivacySeverity.Review -> XdmStatusTone.Warning
    MediaPrivacySeverity.Blocker -> XdmStatusTone.Error
}


@Composable
private fun MediaDispatchDashboardCard(dashboard: MediaDispatchDashboard, plans: List<MediaDispatchPlan>) {
    Card(Modifier.fillMaxWidth().semantics { contentDescription = "Media dispatch control tower ${dashboard.summary}" }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            XdmCardTitle("Media dispatch control tower")
            XdmSupportingText("Phase 22 dispatch runbook maps each resolver choice to a safe lane, background policy, retry policy, progress signals, and terminal cleanup before the job leaves the Media inbox.", maxLines = 3)
            XdmActionFlowRow {
                StatusPill("${dashboard.readyCount} ready", if (dashboard.readyCount > 0) XdmStatusTone.Success else XdmStatusTone.Neutral)
                dashboard.blockedCount.takeIf { it > 0 }?.let { StatusPill("$it blocked", XdmStatusTone.Warning) }
                dashboard.refreshCount.takeIf { it > 0 }?.let { StatusPill("$it refresh", XdmStatusTone.Warning) }
                dashboard.termuxSetupCount.takeIf { it > 0 }?.let { StatusPill("$it Termux setup", XdmStatusTone.Info) }
                StatusPill(if (dashboard.secretSafe) "secret-safe" else "redaction review", if (dashboard.secretSafe) XdmStatusTone.Success else XdmStatusTone.Warning)
            }
            XdmMetadataText(dashboard.summary, maxLines = 3)
            if (plans.isEmpty()) {
                XdmMetadataText("No dispatch plans yet. Capture media from ShareSheet, IronFox, or the built-in browser to generate a runbook.", maxLines = 2)
            } else {
                plans.take(4).forEach { plan ->
                    XdmListCard(compact = true) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                            Column(Modifier.weight(1f)) {
                                XdmMetadataText(plan.title, maxLines = 1)
                                XdmSupportingText(plan.summary, maxLines = 2)
                                XdmMetadataText(plan.safeDiagnostics, maxLines = 3)
                            }
                            StatusPill(plan.readiness.label, toneForDispatchReadiness(plan.readiness))
                        }
                        XdmActionFlowRow {
                            StatusPill(plan.lane.label, XdmStatusTone.Info)
                            StatusPill(plan.primaryActionLabel, if (plan.queueButtonEnabled) XdmStatusTone.Success else XdmStatusTone.Neutral)
                            StatusPill("${plan.steps.size} steps", XdmStatusTone.Neutral)
                            plan.progressSignals.firstOrNull()?.let { StatusPill(it.label, XdmStatusTone.Info) }
                        }
                        plan.warnings.takeIf { it.isNotEmpty() }?.let { warnings ->
                            XdmMetadataText("Warnings: ${warnings.joinToString(" • ")}", maxLines = 2)
                        }
                    }
                }
            }
        }
    }
}


@Composable
private fun MediaQueueTelemetryCard(deck: MediaQueueTelemetryDeck) {
    Card(Modifier.fillMaxWidth().semantics { contentDescription = "Media queue telemetry ${deck.summary}" }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            XdmCardTitle("Media queue telemetry")
            XdmSupportingText(
                "Phase 23 turns dispatch runbooks into a control-room telemetry deck with progress pulse, next action, cleanup status, and redaction health before final validation.",
                maxLines = 3,
            )
            XdmActionFlowRow {
                StatusPill("${deck.readyToLaunchCount} ready", if (deck.readyToLaunchCount > 0) XdmStatusTone.Success else XdmStatusTone.Neutral)
                deck.activeCount.takeIf { it > 0 }?.let { StatusPill("$it active", XdmStatusTone.Info) }
                deck.needsAttentionCount.takeIf { it > 0 }?.let { StatusPill("$it attention", XdmStatusTone.Warning) }
                deck.cleanupArmedCount.takeIf { it > 0 }?.let { StatusPill("$it cleanup armed", XdmStatusTone.Neutral) }
                deck.terminalCount.takeIf { it > 0 }?.let { StatusPill("$it terminal", XdmStatusTone.Neutral) }
                StatusPill(if (deck.secretSafe) "secret-safe telemetry" else "redaction review", if (deck.secretSafe) XdmStatusTone.Success else XdmStatusTone.Warning)
            }
            XdmMetadataText(deck.summary, maxLines = 3)
            if (deck.empty) {
                XdmMetadataText("No queue telemetry yet. Capture media and prepare a dispatch runbook to populate the deck.", maxLines = 2)
            } else {
                deck.rows.take(5).forEach { row ->
                    XdmListCard(compact = true) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                            Column(Modifier.weight(1f)) {
                                XdmMetadataText(row.title, maxLines = 1)
                                XdmSupportingText(row.summary, maxLines = 2)
                                XdmMetadataText(row.safeDiagnostic, maxLines = 3)
                            }
                            StatusPill(row.tone.label, toneForQueueTelemetry(row.tone))
                        }
                        XdmActionFlowRow {
                            StatusPill(row.progressLabel, XdmStatusTone.Info)
                            StatusPill(row.nextActionLabel, if (row.stalled) XdmStatusTone.Warning else XdmStatusTone.Neutral)
                            if (row.cleanupArmed) StatusPill("Terminal cleanup", XdmStatusTone.Success)
                            StatusPill(if (row.secretSafe) "No leak" else "Leak blocked", if (row.secretSafe) XdmStatusTone.Success else XdmStatusTone.Warning)
                        }
                    }
                }
            }
        }
    }
}




@Composable
private fun OfflineLibraryV2Card(dashboard: OfflineLibraryV2Dashboard) {
    Card(Modifier.fillMaxWidth().semantics { contentDescription = "Offline Library 2.0 ${dashboard.summary}" }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            XdmCardTitle("Offline Library 2.0")
            XdmSupportingText(
                "Phase 28 makes completed media filterable by video, audio, failed, playable, missing file, source host, and cleanup state with safe sidecar actions.",
                maxLines = 3,
            )
            XdmActionFlowRow {
                StatusPill("${dashboard.visibleCount} visible", XdmStatusTone.Neutral)
                dashboard.playableCount.takeIf { it > 0 }?.let { StatusPill("$it playable", XdmStatusTone.Success) }
                dashboard.videoCount.takeIf { it > 0 }?.let { StatusPill("$it video", XdmStatusTone.Info) }
                dashboard.audioCount.takeIf { it > 0 }?.let { StatusPill("$it audio", XdmStatusTone.Info) }
                dashboard.failedCount.takeIf { it > 0 }?.let { StatusPill("$it failed", XdmStatusTone.Warning) }
                dashboard.missingCount.takeIf { it > 0 }?.let { StatusPill("$it missing", XdmStatusTone.Warning) }
                dashboard.cleanupCount.takeIf { it > 0 }?.let { StatusPill("$it cleanup", XdmStatusTone.Neutral) }
                StatusPill(if (dashboard.secretSafe) "safe export" else "redaction review", if (dashboard.secretSafe) XdmStatusTone.Success else XdmStatusTone.Warning)
            }
            XdmMetadataText("Filters: ${OfflineLibraryV2Filter.entries.joinToString { it.label }}", maxLines = 2)
            dashboard.sourceHosts.takeIf { it.isNotEmpty() }?.let { hosts -> XdmMetadataText("Source hosts: ${hosts.take(5).joinToString()}", maxLines = 2) }
            if (dashboard.empty) {
                XdmMetadataText("No offline media rows yet. Completed downloads will appear here with sidecar actions and playback handoff.", maxLines = 2)
            } else {
                dashboard.rows.take(5).forEach { row -> OfflineLibraryV2RowCard(row) }
            }
        }
    }
}

@Composable
private fun OfflineLibraryV2RowCard(row: com.mikeyphw.xdm.android.media.OfflineLibraryV2Row) {
    XdmListCard(compact = true) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                XdmMetadataText(row.title, maxLines = 1)
                XdmSupportingText(row.summary, maxLines = 2)
                XdmMetadataText("Sidecar: ${row.sidecarFileName}", maxLines = 1)
                XdmMetadataText("Safe export: ${row.safeExportJson.take(180)}", maxLines = 2)
            }
            StatusPill(row.health.label, toneForOfflineLibraryHealth(row.health))
        }
        XdmActionFlowRow {
            row.actions.filter { it.enabled }.take(5).forEach { action ->
                StatusPill(action.kind.label, if (action.requiresConfirmation) XdmStatusTone.Warning else XdmStatusTone.Success)
            }
        }
    }
}

private fun toneForOfflineLibraryHealth(health: OfflineLibraryV2Health): XdmStatusTone = when (health) {
    OfflineLibraryV2Health.Ready -> XdmStatusTone.Success
    OfflineLibraryV2Health.Failed,
    OfflineLibraryV2Health.MissingFile,
    OfflineLibraryV2Health.NeedsSidecarRepair -> XdmStatusTone.Warning
    OfflineLibraryV2Health.NeedsCleanup -> XdmStatusTone.Info
    OfflineLibraryV2Health.WaitingForDownload -> XdmStatusTone.Neutral
}

@Composable
private fun PlayerDiagnosticsDeckCard(reports: List<MediaPlayerDiagnosticReport>) {
    Card(Modifier.fillMaxWidth().semantics { contentDescription = "Player diagnostics deck ${reports.size}" }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            XdmCardTitle("Player diagnostics deck")
            XdmSupportingText(
                "Phase 29 makes Media3 playback failures explainable with source, network, decoder, codec, subtitle, and protected-media buckets plus retry-prepare guidance.",
                maxLines = 3,
            )
            XdmActionFlowRow {
                StatusPill("${reports.size} reports", XdmStatusTone.Neutral)
                reports.count { it.retryPrepareAvailable }.takeIf { it > 0 }?.let { StatusPill("$it retry", XdmStatusTone.Info) }
                reports.count { it.protectedDiagnosticOnly }.takeIf { it > 0 }?.let { StatusPill("$it protected", XdmStatusTone.Warning) }
                StatusPill(if (reports.all { it.sourceSafe }) "source-safe" else "redaction review", if (reports.all { it.sourceSafe }) XdmStatusTone.Success else XdmStatusTone.Warning)
            }
            reports.take(3).forEach { report -> PlayerDiagnosticsReportCard(report) }
            if (reports.isEmpty()) {
                XdmMetadataText("Completed direct media will expose Player 2.0 diagnostics here.", maxLines = 2)
            }
        }
    }
}

@Composable
private fun PlayerDiagnosticsReportCard(report: MediaPlayerDiagnosticReport) {
    XdmListCard(compact = true) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                XdmMetadataText(report.title, maxLines = 1)
                XdmSupportingText(report.message, maxLines = 3)
                XdmMetadataText("Playback position: ${report.positionMemory.summary}", maxLines = 2)
                XdmMetadataText("Track availability: ${report.tracks.joinToString { it.summary }}", maxLines = 2)
                report.subtitleRows.takeIf { it.isNotEmpty() }?.let { rows -> XdmMetadataText("Subtitle availability: ${rows.joinToString { it.summary }}", maxLines = 2) }
            }
            StatusPill(report.bucket.label, toneForPlayerDiagnostic(report.bucket))
        }
        XdmActionFlowRow {
            report.actions.take(5).forEach { action -> StatusPill(action.label, if (action.label.contains("Retry")) XdmStatusTone.Info else XdmStatusTone.Neutral) }
        }
    }
}

private fun toneForPlayerDiagnostic(bucket: MediaPlayerDiagnosticBucket): XdmStatusTone = when (bucket) {
    MediaPlayerDiagnosticBucket.Ready -> XdmStatusTone.Success
    MediaPlayerDiagnosticBucket.Network,
    MediaPlayerDiagnosticBucket.Source,
    MediaPlayerDiagnosticBucket.Subtitle -> XdmStatusTone.Info
    MediaPlayerDiagnosticBucket.Decoder,
    MediaPlayerDiagnosticBucket.UnsupportedCodec,
    MediaPlayerDiagnosticBucket.ProtectedMedia,
    MediaPlayerDiagnosticBucket.Unknown -> XdmStatusTone.Warning
}

@Composable
private fun MediaNativeDirectDownloadEngineCard(dashboard: NativeDirectDashboard) {
    Card(Modifier.fillMaxWidth().semantics { contentDescription = "Native direct download engine ${dashboard.summary}" }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            XdmCardTitle("Native direct download engine")
            XdmSupportingText(
                "Phase 27 plans Android-native direct media transfers with transient headers, resume metadata, destination policy, and redacted diagnostics before any byte writer is enabled.",
                maxLines = 3,
            )
            XdmActionFlowRow {
                StatusPill("${dashboard.readyCount} ready", if (dashboard.readyCount > 0) XdmStatusTone.Success else XdmStatusTone.Neutral)
                dashboard.resumeCount.takeIf { it > 0 }?.let { StatusPill("$it resumable", XdmStatusTone.Info) }
                dashboard.permissionCount.takeIf { it > 0 }?.let { StatusPill("$it permission", XdmStatusTone.Warning) }
                dashboard.unsupportedCount.takeIf { it > 0 }?.let { StatusPill("$it adaptive", XdmStatusTone.Neutral) }
                StatusPill(if (dashboard.secretSafe) "secret-safe" else "redaction review", if (dashboard.secretSafe) XdmStatusTone.Success else XdmStatusTone.Warning)
            }
            dashboard.plans.take(3).forEach { plan -> NativeDirectDownloadPlanRow(plan) }
            if (dashboard.plans.isEmpty()) {
                XdmSupportingText("No direct download requests yet. Direct MP4, WebM, MP3, and M4A captures will appear here when the worker bridge is ready.")
            }
        }
    }
}

@Composable
private fun NativeDirectDownloadPlanRow(plan: NativeDirectDownloadRequestPlan) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(plan.summary, style = MaterialTheme.typography.labelLarge)
        XdmSupportingText(plan.redactedDiagnostics.take(220), maxLines = 3)
    }
}

@Composable
private fun MediaTermuxRuntimeAdapterCard(dashboard: TermuxRuntimeDashboard) {
    Card(Modifier.fillMaxWidth().semantics { contentDescription = "Media Termux runtime adapter ${dashboard.summary}" }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            XdmCardTitle("Media Termux runtime adapter")
            XdmSupportingText(
                "Phase 26 turns worker bridge requests into typed yt-dlp and aria2 Termux launch plans with capability probes, transient Netscape cookie/input/session files, and terminal cleanup checks.",
                maxLines = 3,
            )
            XdmActionFlowRow {
                StatusPill("${dashboard.launchableCount} launchable", if (dashboard.launchableCount > 0) XdmStatusTone.Success else XdmStatusTone.Neutral)
                dashboard.missingToolCount.takeIf { it > 0 }?.let { StatusPill("$it missing tools", XdmStatusTone.Warning) }
                dashboard.cleanupArmedCount.takeIf { it > 0 }?.let { StatusPill("$it cleanup armed", XdmStatusTone.Info) }
                StatusPill(if (dashboard.secretSafe) "secret-safe" else "redaction review", if (dashboard.secretSafe) XdmStatusTone.Success else XdmStatusTone.Warning)
            }
            dashboard.plans.take(3).forEach { plan -> TermuxRuntimeLaunchPlanRow(plan) }
            if (dashboard.plans.isEmpty()) {
                XdmSupportingText("No worker bridge requests yet. Capture media, choose tracks, then review the Termux launch plan before execution.")
            }
        }
    }
}

@Composable
private fun TermuxRuntimeLaunchPlanRow(plan: TermuxRuntimeLaunchPlan) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(plan.summary, style = MaterialTheme.typography.labelLarge)
        XdmSupportingText(plan.redactedPreview.take(220), maxLines = 3)
        plan.missingToolHints.takeIf { it.isNotEmpty() }?.let { hints ->
            XdmSupportingText("Install/help only: ${hints.joinToString(" • ")}", maxLines = 3)
        }
    }
}

@Composable
private fun MediaWorkerBridgeCard(dashboard: MediaWorkerBridgeDashboard) {
    Card(Modifier.fillMaxWidth().semantics { contentDescription = "Media worker bridge ${dashboard.summary}" }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            XdmCardTitle("Media worker bridge")
            XdmSupportingText(
                "Phase 25 converts ready media actions into Android UIDT, WorkManager foreground, aria2, native, or Termux yt-dlp bridge requests without launching services yet.",
                maxLines = 3,
            )
            XdmActionFlowRow {
                StatusPill("${dashboard.launchableCount} launchable", if (dashboard.launchableCount > 0) XdmStatusTone.Success else XdmStatusTone.Neutral)
                dashboard.androidWorkerCount.takeIf { it > 0 }?.let { StatusPill("$it Android", XdmStatusTone.Info) }
                dashboard.termuxWorkerCount.takeIf { it > 0 }?.let { StatusPill("$it Termux", XdmStatusTone.Info) }
                dashboard.blockedCount.takeIf { it > 0 }?.let { StatusPill("$it blocked", XdmStatusTone.Warning) }
                dashboard.confirmationCount.takeIf { it > 0 }?.let { StatusPill("$it confirm", XdmStatusTone.Warning) }
                StatusPill(if (dashboard.secretSafe) "secret-safe bridge" else "redaction review", if (dashboard.secretSafe) XdmStatusTone.Success else XdmStatusTone.Warning)
            }
            XdmMetadataText(dashboard.summary, maxLines = 3)
            if (dashboard.empty) {
                XdmMetadataText("No worker bridge requests yet. Queue actions become bridge requests after dispatch planning.", maxLines = 2)
            } else {
                dashboard.requests.take(5).forEach { request -> MediaWorkerBridgePlanCard(request) }
            }
        }
    }
}

@Composable
private fun MediaWorkerBridgePlanCard(request: MediaWorkerBridgeRequest) {
    XdmListCard(compact = true) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                XdmMetadataText(request.title, maxLines = 1)
                XdmSupportingText(request.summary, maxLines = 3)
                XdmMetadataText(request.notification.summary, maxLines = 2)
                XdmMetadataText(request.adapter.redactedPreview, maxLines = 3)
            }
            StatusPill(request.readiness.label, toneForWorkerBridge(request.readiness, request.kind))
        }
        XdmActionFlowRow {
            StatusPill(request.kind.label, toneForWorkerBridge(request.readiness, request.kind))
            StatusPill(request.backgroundPolicy.workKind.label, XdmStatusTone.Neutral)
            StatusPill(if (request.adapter.rawShellExposed) "raw shell" else "typed adapter", if (request.adapter.rawShellExposed) XdmStatusTone.Warning else XdmStatusTone.Success)
            if (request.cleanupAfterTerminal.isNotEmpty()) StatusPill("cleanup owned", XdmStatusTone.Success)
            StatusPill(if (request.secretSafe) "No leak" else "Leak blocked", if (request.secretSafe) XdmStatusTone.Success else XdmStatusTone.Warning)
        }
    }
}

private fun toneForWorkerBridge(readiness: MediaWorkerBridgeReadiness, kind: MediaWorkerBridgeKind): XdmStatusTone = when {
    readiness == MediaWorkerBridgeReadiness.Ready -> XdmStatusTone.Success
    readiness == MediaWorkerBridgeReadiness.Blocked || kind == MediaWorkerBridgeKind.BlockedDiagnostic -> XdmStatusTone.Warning
    readiness == MediaWorkerBridgeReadiness.NeedsConfirmation -> XdmStatusTone.Warning
    readiness == MediaWorkerBridgeReadiness.WaitingForTermux || readiness == MediaWorkerBridgeReadiness.WaitingForMetadata -> XdmStatusTone.Info
    else -> XdmStatusTone.Neutral
}

@Composable
private fun MediaQueueActionsCard(dashboard: MediaQueueActionDashboard) {
    Card(Modifier.fillMaxWidth().semantics { contentDescription = "Media queue actions ${dashboard.summary}" }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            XdmCardTitle("Media queue actions")
            XdmSupportingText(
                "Phase 24 turns telemetry into safe action eligibility for pause, resume, retry, cancel, cleanup, refresh, Termux setup, and library handoff without executing raw commands.",
                maxLines = 3,
            )
            XdmActionFlowRow {
                StatusPill("${dashboard.launchableCount} launch", if (dashboard.launchableCount > 0) XdmStatusTone.Success else XdmStatusTone.Neutral)
                dashboard.pausableCount.takeIf { it > 0 }?.let { StatusPill("$it pause", XdmStatusTone.Info) }
                dashboard.retryableCount.takeIf { it > 0 }?.let { StatusPill("$it retry/resume", XdmStatusTone.Warning) }
                dashboard.cancellableCount.takeIf { it > 0 }?.let { StatusPill("$it cancel", XdmStatusTone.Warning) }
                dashboard.cleanupCount.takeIf { it > 0 }?.let { StatusPill("$it cleanup", XdmStatusTone.Neutral) }
                StatusPill(if (dashboard.secretSafe) "secret-safe actions" else "redaction review", if (dashboard.secretSafe) XdmStatusTone.Success else XdmStatusTone.Warning)
            }
            XdmMetadataText(dashboard.summary, maxLines = 3)
            if (dashboard.empty) {
                XdmMetadataText("No queue actions yet. Capture media and let dispatch telemetry build action eligibility.", maxLines = 2)
            } else {
                dashboard.bulkActions.takeIf { it.isNotEmpty() }?.let { bulkActions ->
                    XdmListCard(compact = true) {
                        XdmMetadataText("Bulk actions")
                        bulkActions.forEach { bulk ->
                            XdmMetadataText("${bulk.label} • ${if (bulk.requiresConfirmation) "confirmation required" else "ready"} • ${bulk.safeSummary}", maxLines = 2)
                        }
                    }
                }
                dashboard.plans.take(5).forEach { plan -> MediaQueueActionPlanCard(plan) }
            }
        }
    }
}

@Composable
private fun MediaQueueActionPlanCard(plan: MediaQueueActionPlan) {
    XdmListCard(compact = true) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                XdmMetadataText(plan.title, maxLines = 1)
                XdmSupportingText(plan.safeSummary, maxLines = 3)
                plan.unavailableReasons.takeIf { it.isNotEmpty() }?.let { reasons ->
                    XdmMetadataText("Unavailable: ${reasons.joinToString(" • ")}", maxLines = 2)
                }
            }
            StatusPill(plan.primaryAction.kind.label, toneForQueueAction(plan.primaryAction.kind, plan.primaryAction.availability))
        }
        XdmActionFlowRow {
            plan.actions.filter { it.availability != MediaQueueActionAvailability.Hidden }.take(6).forEach { action ->
                StatusPill(action.kind.label, toneForQueueAction(action.kind, action.availability))
            }
        }
    }
}

private fun toneForQueueAction(kind: MediaQueueActionKind, availability: MediaQueueActionAvailability): XdmStatusTone = when {
    availability == MediaQueueActionAvailability.Disabled || availability == MediaQueueActionAvailability.Hidden -> XdmStatusTone.Neutral
    kind == MediaQueueActionKind.Cancel || availability == MediaQueueActionAvailability.ConfirmationRequired -> XdmStatusTone.Warning
    kind == MediaQueueActionKind.Launch || kind == MediaQueueActionKind.OpenLibrary -> XdmStatusTone.Success
    kind == MediaQueueActionKind.Retry || kind == MediaQueueActionKind.Resume || kind == MediaQueueActionKind.Pause -> XdmStatusTone.Info
    else -> XdmStatusTone.Neutral
}

private fun toneForQueueTelemetry(tone: MediaQueueTelemetryTone): XdmStatusTone = when (tone) {
    MediaQueueTelemetryTone.Stable -> XdmStatusTone.Success
    MediaQueueTelemetryTone.Active -> XdmStatusTone.Info
    MediaQueueTelemetryTone.Attention,
    MediaQueueTelemetryTone.Blocked -> XdmStatusTone.Warning
}

private fun toneForDispatchReadiness(readiness: MediaDispatchReadiness): XdmStatusTone = when (readiness) {
    MediaDispatchReadiness.Ready -> XdmStatusTone.Success
    MediaDispatchReadiness.AwaitingUserChoice,
    MediaDispatchReadiness.NeedsTermuxSetup -> XdmStatusTone.Info
    MediaDispatchReadiness.NeedsMetadataRefresh,
    MediaDispatchReadiness.BlockedProtected,
    MediaDispatchReadiness.BlockedSecretLeak -> XdmStatusTone.Warning
}

@Composable
private fun OfflineMediaLibraryCard(summary: OfflineMediaLibrarySummary, items: List<OfflineMediaLibraryItem>, downloads: List<Download>, onResumeOrRetryDownload: (Download) -> Unit) {
    var expandedPlayerId by remember { mutableStateOf<String?>(null) }
    Card(Modifier.fillMaxWidth().semantics { contentDescription = "Offline library ${summary.message}" }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            XdmCardTitle("Offline library and player")
            XdmSupportingText(summary.message, maxLines = 3)
            XdmActionFlowRow {
                StatusPill("${summary.playableCount} playable", if (summary.playableCount > 0) XdmStatusTone.Success else XdmStatusTone.Neutral)
                summary.adaptiveCount.takeIf { it > 0 }?.let { StatusPill("$it adaptive", XdmStatusTone.Info) }
                summary.audioOnlyCount.takeIf { it > 0 }?.let { StatusPill("$it audio", XdmStatusTone.Neutral) }
                summary.subtitleTrackCount.takeIf { it > 0 }?.let { StatusPill("$it subtitles", XdmStatusTone.Neutral) }
            }
            XdmMetadataText("Offline media library persists redacted sidecar metadata beside completed files: title, thumbnail, duration, source host, selected track IDs, and safe playback state.", maxLines = 3)
            if (items.isEmpty()) {
                XdmMetadataText("No offline media items yet. Queue selected media to seed the library.", maxLines = 2)
            } else {
                items.take(5).forEach { item ->
                    XdmListCard(compact = true) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                            Column(Modifier.weight(1f)) {
                                XdmCardTitle(item.title, maxLines = 1)
                                XdmMetadataText(listOf(item.fileName, item.sourceHost, item.durationLabel).joinToString(" • "), maxLines = 1)
                                XdmSupportingText(item.detail, maxLines = 2)
                            }
                            StatusPill(item.state?.name ?: "Captured", if (item.isCompleted) XdmStatusTone.Success else XdmStatusTone.Neutral)
                        }
                        XdmActionFlowRow {
                            item.pageHost?.takeIf { it.isNotBlank() }?.let { StatusPill("Page $it", XdmStatusTone.Info) }
                            item.thumbnailUrl?.takeIf { it.isNotBlank() }?.let { StatusPill("Thumbnail", XdmStatusTone.Info) }
                            item.downloadId?.let { StatusPill("Job", XdmStatusTone.Neutral) }
                        }
                        XdmMetadataText("Sidecar: ${item.sidecar.toRedactedJson()}", maxLines = 2)
                        val playbackCandidate = item.toPlaybackCandidate()
                        XdmActionFlowRow {
                            playbackCandidate?.let {
                                TextButton(onClick = { expandedPlayerId = if (expandedPlayerId == item.captureId) null else item.captureId }) { Text(if (expandedPlayerId == item.captureId) "Hide player" else "Open player") }
                            }
                            val actionLabel = when {
                                item.canResume -> "Resume media"
                                item.canRetry -> "Retry media"
                                else -> null
                            }
                            actionLabel?.let { label ->
                                val download = item.downloadId?.let { id -> downloads.firstOrNull { it.id == id } }
                                download?.let { TextButton(onClick = { onResumeOrRetryDownload(it) }) { Text(label) } }
                            }
                        }
                        if (expandedPlayerId == item.captureId) {
                            playbackCandidate?.let { Media3DirectPlayerCard(it) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaExecutionQueueCard(jobs: List<MediaExecutionJob>) {
    Card(Modifier.fillMaxWidth().semantics { contentDescription = "Media execution jobs ${jobs.size}" }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            XdmCardTitle("Media download execution")
            XdmSupportingText("Tracks move through explicit states: Probing, Queued, Downloading, Completed, Failed, or Blocked. Retry and resume stay attached to the originating media capture.", maxLines = 3)
            if (jobs.isEmpty()) {
                XdmMetadataText("No media jobs yet.", maxLines = 1)
            } else {
                jobs.take(5).forEach { job ->
                    XdmListCard(compact = true) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                            Column(Modifier.weight(1f)) {
                                XdmMetadataText(job.title, maxLines = 1)
                                XdmSupportingText(job.detail, maxLines = 2)
                                XdmMetadataText(job.engine, maxLines = 1)
                            }
                            StatusPill(job.stage.label, when (job.stage) {
                                MediaExecutionStage.Completed -> XdmStatusTone.Success
                                MediaExecutionStage.Failed, MediaExecutionStage.Blocked -> XdmStatusTone.Warning
                                MediaExecutionStage.Downloading, MediaExecutionStage.Probing -> XdmStatusTone.Info
                                MediaExecutionStage.Queued -> XdmStatusTone.Neutral
                            })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaCaptureCard(
    capture: MediaCaptureRecord,
    captureVariants: List<MediaVariant>,
    onDownload: (MediaCaptureRecord, MediaTrackSelection) -> Unit,
    onResolve: (MediaCaptureRecord) -> Unit,
    onSelectVariant: (MediaCaptureRecord, String) -> Unit,
    onRemove: (MediaCaptureRecord) -> Unit,
    onTermuxMetadata: (MediaCaptureRecord, MediaTrackSelection) -> Unit,
    onTermuxInspect: (MediaCaptureRecord) -> Unit,
    onTermuxYtDlpDownload: (MediaCaptureRecord, MediaTrackSelection) -> Unit,
    onTermuxConvert: (MediaCaptureRecord, ConversionPreset) -> Unit,
    onPreviewPostProcessing: (MediaCaptureRecord) -> Unit,
    onRunPostProcessing: (MediaCaptureRecord) -> Unit,
) {
    val context = LocalContext.current
    var variantSelectorExpanded by remember(capture.id) { mutableStateOf(false) }
    var trackSelection by remember(capture.id) { mutableStateOf(MediaTrackSelection(videoVariantId = capture.selectedVariantId)) }
    LaunchedEffect(capture.selectedVariantId) {
        trackSelection = trackSelection.copy(videoVariantId = capture.selectedVariantId ?: trackSelection.videoVariantId)
    }
    val mediaPlanner = remember { MediaDownloadPlanner() }
    val executionPlanner = remember { MediaExecutionLibraryPlanner(mediaPlanner) }
    val mediaPlan = remember(capture, captureVariants, trackSelection) { mediaPlanner.plan(capture, captureVariants, selection = trackSelection) }
    val mediaQueueSpec = remember(capture, captureVariants, mediaPlan.trackSelection) {
        executionPlanner.queueSpec(capture, captureVariants, mediaPlan.trackSelection, "content://downloads")
    }
    val mediaEnginePlan = remember(mediaQueueSpec) { executionPlanner.enginePlan(mediaQueueSpec, Build.VERSION.SDK_INT) }
    val mediaDispatchPlan = remember(mediaQueueSpec, mediaEnginePlan) {
        MediaExecutionDispatcher().dispatchPlan(mediaQueueSpec, mediaEnginePlan, capture)
    }
    val mediaQueueActionPlan = remember(mediaDispatchPlan) { MediaQueueActionPlanner().actionPlan(mediaDispatchPlan, null) }
    val mediaWorkerBridgeRequest = remember(mediaQueueSpec, mediaEnginePlan, mediaDispatchPlan, mediaQueueActionPlan) {
        MediaWorkerBridgePlanner().request(mediaQueueSpec, mediaEnginePlan, mediaDispatchPlan, mediaQueueActionPlan)
    }
    val metadataPreview = remember(capture, captureVariants) { mediaPlanner.metadataProbePreview(capture, captureVariants) }
    val pickerGroups = remember(capture, captureVariants, trackSelection) { mediaPlanner.pickerGroups(capture, captureVariants, trackSelection) }
    val playbackCandidate = remember(capture, captureVariants) { mediaPlanner.playbackCandidate(capture, captureVariants) }
    val selectedVariant = mediaPlan.selectedVariantId?.let { id -> captureVariants.firstOrNull { it.id == id } }
        ?: captureVariants.firstOrNull { it.id == capture.selectedVariantId }
        ?: captureVariants.firstOrNull()
    XdmListCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                XdmCardTitle(capture.title, maxLines = 1)
                XdmMetadataText(mediaOriginLabel(capture), maxLines = 1)
            }
            StatusPill(capture.kind.uiLabel(), XdmStatusTone.Info)
        }
        XdmSupportingText(
            listOfNotNull(
                capture.mimeType,
                capture.container,
                capture.durationMs?.let { formatDurationSeconds(it) },
            ).joinToString(" • ").ifBlank { "Media details will appear after resolution." },
            maxLines = 2,
        )
        XdmActionFlowRow {
            StatusPill(capture.status.uiLabel(), if (capture.status == MediaCaptureStatus.DownloadCreated) XdmStatusTone.Success else XdmStatusTone.Neutral)
            StatusPill(capture.resolutionStatus.uiLabel(), if (capture.resolutionStatus == MediaResolutionStatus.Failed || capture.resolutionStatus == MediaResolutionStatus.RequiresRefresh) XdmStatusTone.Warning else XdmStatusTone.Neutral)
            StatusPill(mediaPlan.displayName, if (mediaPlan.strategy == MediaDownloadStrategy.UnsupportedProtected) XdmStatusTone.Warning else XdmStatusTone.Info)
            capture.downloadId?.let { StatusPill("Queued", XdmStatusTone.Success) }
        }
        XdmMetadataText(mediaPlan.explanation, maxLines = 3)
        MetadataProbePreviewCard(metadataPreview, mediaPlan)
        SessionHandoffCard(mediaPlan)
        MediaEngineHardeningCard(mediaEnginePlan)
        MediaDispatchRunbookCard(mediaDispatchPlan)
        MediaQueueActionPlanCard(mediaQueueActionPlan)
        MediaWorkerBridgePlanCard(mediaWorkerBridgeRequest)
        ProtectedMediaDiagnosticsCard(mediaPlan)
        playbackCandidate?.let { candidate ->
            Media3DirectPlayerCard(candidate)
            PlayerDiagnosticsReportCard(MediaPlayerDiagnosticsPlanner().report(candidate))
        }
        if (captureVariants.isNotEmpty()) {
            XdmListCard(compact = true) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        XdmMetadataText("Selected variant")
                        XdmMetricText(selectedVariant?.qualityLabel ?: "Automatic")
                        XdmSupportingText("Choose tracks: Choose variant, Audio track, and Subtitle track before download.", maxLines = 2)
                    }
                    TextButton(onClick = { variantSelectorExpanded = !variantSelectorExpanded }) { Text(if (variantSelectorExpanded) "Hide variants" else "Choose variant / Choose tracks") }
                }
                if (variantSelectorExpanded) {
                    pickerGroups.forEach { group ->
                        VariantPickerGroupCard(
                            group = group,
                            onSelect = { variant ->
                                trackSelection = when (group.kind) {
                                    MediaVariantKind.Audio -> trackSelection.copy(audioVariantId = variant.id)
                                    MediaVariantKind.Subtitle -> trackSelection.copy(subtitleVariantId = variant.id)
                                    MediaVariantKind.Primary, MediaVariantKind.Video -> {
                                        onSelectVariant(capture, variant.id)
                                        trackSelection.copy(videoVariantId = variant.id)
                                    }
                                    MediaVariantKind.Thumbnail -> trackSelection
                                }
                            },
                        )
                    }
                }
            }
        } else {
            XdmMetadataText("No variants yet. Resolve metadata to discover quality, audio, and subtitle options.")
        }
        XdmActionFlowRow {
            Button(
                onClick = { onDownload(capture, mediaPlan.trackSelection) },
                enabled = mediaPlan.canQueueDirectly && capture.status != MediaCaptureStatus.DownloadCreated && capture.resolutionStatus != MediaResolutionStatus.RequiresRefresh,
            ) { Text(if (capture.status == MediaCaptureStatus.DownloadCreated) "Added" else "Queue selected") }
            TextButton(onClick = { onResolve(capture) }) { Text(if (capture.resolutionStatus == MediaResolutionStatus.RequiresRefresh) "Refresh metadata" else "Resolve metadata") }
            TextButton(onClick = { onRemove(capture) }) { Text("Remove capture") }
        }
        XdmListCard(compact = true) {
            XdmMetadataText("Termux media pipeline")
            XdmSupportingText("yt-dlp receives page metadata, selected format hints, and redacted session handoff arguments. No raw shell commands are exposed.", maxLines = 3)
            XdmActionFlowRow {
                TextButton(onClick = { onTermuxMetadata(capture, mediaPlan.trackSelection) }) { Text("yt-dlp page metadata") }
                TextButton(onClick = { copyTextToClipboard(context, "XDM yt-dlp probe URL", mediaPlan.metadataProbeUrl) }) { Text("Copy probe URL") }
                TextButton(onClick = { onTermuxInspect(capture) }) { Text("FFprobe") }
                TextButton(onClick = { onTermuxYtDlpDownload(capture, mediaPlan.trackSelection) }, enabled = mediaPlan.canQueueDirectly) { Text("yt-dlp download") }
                TextButton(onClick = { onTermuxConvert(capture, ConversionPreset.VideoFastStart) }) { Text("Fast-start MP4") }
                TextButton(onClick = { onTermuxConvert(capture, ConversionPreset.AudioExtract) }) { Text("Extract audio") }
            }
        }
        XdmListCard(compact = true) {
            XdmMetadataText("Post-processing automation")
            XdmSupportingText("Preview or run matching post-processing rules for this media capture using typed Termux/root actions only.")
            XdmActionFlowRow {
                TextButton(onClick = { onPreviewPostProcessing(capture) }) { Text("Preview rules") }
                TextButton(onClick = { onRunPostProcessing(capture) }) { Text("Run rules") }
            }
        }
    }
}

@Composable
private fun MetadataProbePreviewCard(preview: YtDlpMetadataProbeResult, plan: MediaDownloadPlan) {
    XdmListCard(compact = true) {
        XdmMetadataText("yt-dlp metadata preview")
        XdmSupportingText(preview.summary, maxLines = 2)
        XdmActionFlowRow {
            preview.thumbnailUrl?.takeIf { it.isNotBlank() }?.let { StatusPill("Thumbnail", XdmStatusTone.Info) }
            preview.durationMs?.let { StatusPill(preview.durationLabel, XdmStatusTone.Neutral) }
            StatusPill("Probe ${if (preview.webpageUrl != null) "page" else "stream"}", XdmStatusTone.Info)
            plan.ytDlpFormatSelector?.let { StatusPill("Format selector", XdmStatusTone.Neutral) }
        }
        plan.ytDlpFormatSelector?.let { XdmMetadataText("yt-dlp format: $it", maxLines = 2) }
    }
}

@Composable
private fun SessionHandoffCard(plan: MediaDownloadPlan) {
    XdmListCard(compact = true) {
        XdmMetadataText("Cookie/header session handoff")
        XdmSupportingText(
            if (plan.sessionHandoff.needsSession) "Resolver will forward referer/header context to yt-dlp or aria2 while diagnostics stay redacted."
            else "No page cookies or special headers were detected for this capture.",
            maxLines = 3,
        )
        XdmMetadataText(plan.sessionHandoff.redactedSummary, maxLines = 3)
    }
}

@Composable
private fun MediaDispatchRunbookCard(plan: MediaDispatchPlan) {
    XdmListCard(compact = true) {
        XdmMetadataText("Dispatch runbook")
        XdmSupportingText("Safe dispatch is gated by readiness, selected lane, retry policy, progress signals, redacted diagnostics, and terminal cleanup.", maxLines = 3)
        XdmActionFlowRow {
            StatusPill(plan.readiness.label, toneForDispatchReadiness(plan.readiness))
            StatusPill(plan.primaryActionLabel, if (plan.queueButtonEnabled) XdmStatusTone.Success else XdmStatusTone.Neutral)
            StatusPill("${plan.steps.size} steps", XdmStatusTone.Neutral)
            StatusPill("retry ${plan.retryPolicy.maxAttempts}", XdmStatusTone.Info)
        }
        XdmMetadataText(plan.safeDiagnostics, maxLines = 4)
        plan.steps.take(3).forEach { step ->
            XdmMetadataText("${step.kind.label}: ${step.title}", maxLines = 1)
        }
    }
}

@Composable
private fun MediaEngineHardeningCard(plan: MediaExecutionEnginePlan) {
    XdmListCard(compact = true) {
        XdmMetadataText("Download engine hardening")
        XdmSupportingText(
            "UIDT / WorkManager fallback / foreground service policy is selected before queueing, with transient cookie and aria2 files cleaned after terminal state.",
            maxLines = 3,
        )
        XdmActionFlowRow {
            StatusPill(plan.lane.label, XdmStatusTone.Info)
            StatusPill(plan.backgroundPolicy.workKind.label, XdmStatusTone.Neutral)
            plan.tempCookieFile?.let { StatusPill("Netscape cookie temp file", XdmStatusTone.Warning) }
            plan.aria2Input?.let { StatusPill("aria2 transient input/session", XdmStatusTone.Info) }
            StatusPill(if (plan.leakReport.safe) "No cookie leaks" else "Review leaks", if (plan.leakReport.safe) XdmStatusTone.Success else XdmStatusTone.Warning)
        }
        XdmMetadataText("Cleanup verified: ${plan.cleanupActions.joinToString()}", maxLines = 2)
    }
}

@Composable
private fun ProtectedMediaDiagnosticsCard(plan: MediaDownloadPlan) {
    XdmListCard(compact = true) {
        XdmMetadataText("Protected media diagnostics")
        XdmSupportingText(plan.protectedDiagnostic.reason, maxLines = 3)
        XdmActionFlowRow {
            StatusPill(plan.protectedDiagnostic.label, if (plan.protectedDiagnostic.protected) XdmStatusTone.Warning else XdmStatusTone.Neutral)
            plan.protectedDiagnostic.scheme?.let { StatusPill(it, XdmStatusTone.Warning) }
        }
        XdmMetadataText(plan.protectedDiagnostic.allowedAction, maxLines = 2)
    }
}

@Composable
private fun VariantPickerGroupCard(group: MediaVariantPickerGroup, onSelect: (MediaVariant) -> Unit) {
    XdmListCard(compact = true) {
        XdmMetadataText(group.title)
        XdmSupportingText(group.countLabel, maxLines = 1)
        group.variants.forEach { variant ->
            VariantSelectorRow(
                variant = variant,
                selected = group.selectedVariantId == variant.id,
                onSelect = { onSelect(variant) },
            )
        }
    }
}

@Composable
private fun TermuxMediaPipelineCard(pipeline: TermuxMediaPipelineStatus, onClearCompleted: () -> Unit) {
    Card(Modifier.fillMaxWidth().semantics { contentDescription = "Termux media pipeline ${pipeline.readinessLabel}" }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    XdmCardTitle("Termux media pipeline")
                    XdmMetricText(pipeline.readinessLabel)
                }
                if (pipeline.jobs.any { it.status == TermuxMediaJobStatus.Completed || it.status == TermuxMediaJobStatus.Failed }) {
                    TextButton(onClick = onClearCompleted) { Text("Clear done") }
                }
            }
            XdmSupportingText("yt-dlp discovers variants and downloads media; FFprobe inspects streams; FFmpeg remuxes, fast-starts, and extracts audio inside Termux.")
            XdmMetadataText(pipeline.lastAction, maxLines = 2)
            pipeline.recentJobs.take(4).forEach { job ->
                XdmListCard(compact = true) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            XdmCardTitle(job.kind.label, maxLines = 1)
                            XdmMetadataText(job.title, maxLines = 1)
                            XdmMetadataText(job.message.ifBlank { job.output }, maxLines = 2)
                        }
                        StatusPill(job.status.label, if (job.status == TermuxMediaJobStatus.Failed) XdmStatusTone.Warning else XdmStatusTone.Info)
                    }
                }
            }
        }
    }
}

@Composable
private fun PostProcessingAutomationCard(
    automation: PostProcessingAutomationStatus,
    onEnabledChanged: ((Boolean) -> Unit)?,
    onRetryFailed: (() -> Unit)?,
    onClearEvents: (() -> Unit)?,
) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth().semantics { contentDescription = "Post-processing automation ${automation.readinessLabel}" }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    XdmCardTitle("Post-processing automation")
                    XdmMetricText(automation.readinessLabel)
                }
                onEnabledChanged?.let { callback ->
                    Switch(
                        checked = automation.enabled,
                        onCheckedChange = callback,
                        modifier = Modifier.semantics { stateDescription = if (automation.enabled) "Post-processing automation enabled" else "Post-processing automation disabled" },
                    )
                } ?: StatusPill(if (automation.enabled) "Enabled" else "Disabled", if (automation.enabled) XdmStatusTone.Success else XdmStatusTone.Neutral)
            }
            XdmSupportingText("Rules are previewable and execute only through typed Termux media, checksum, cleanup, move/rename, or optional root actions.")
            XdmMetadataText(automation.lastMessage, maxLines = 3)
            XdmActionFlowRow {
                onRetryFailed?.let { TextButton(onClick = it, enabled = automation.failedEvents.isNotEmpty()) { Text("Retry failed") } }
                onClearEvents?.let { TextButton(onClick = it, enabled = automation.events.isNotEmpty()) { Text("Clear events") } }
                TextButton(onClick = { copyTextToClipboard(context, "XDM post-processing diagnostics", automation.diagnosticsSummary()) }) { Text("Copy diagnostics") }
            }
            automation.enabledRules.take(3).forEach { rule ->
                XdmListCard(compact = true) {
                    XdmCardTitle(rule.name, maxLines = 1)
                    XdmMetadataText(rule.summary, maxLines = 2)
                    rule.conditions.takeIf { it.isNotEmpty() }?.let { conditions ->
                        XdmMetadataText("When ${conditions.joinToString { it.summary }}", maxLines = 2)
                    }
                }
            }
            automation.recentEvents.take(4).forEach { event ->
                val tone = when (event.status) {
                    PostProcessingAutomationEventStatus.Failed -> XdmStatusTone.Warning
                    PostProcessingAutomationEventStatus.Completed, PostProcessingAutomationEventStatus.Queued -> XdmStatusTone.Info
                    PostProcessingAutomationEventStatus.Preview, PostProcessingAutomationEventStatus.Skipped -> XdmStatusTone.Neutral
                }
                XdmMetadataText("${event.summary}: ${event.message}", maxLines = 2)
                StatusPill(event.status.label, tone)
            }
        }
    }
}

@Composable
private fun VariantSelectorRow(variant: MediaVariant, selected: Boolean, onSelect: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                XdmCardTitle(variant.qualityLabel)
                XdmMetadataText(variantDetails(variant), maxLines = 2)
            }
            FilterChip(selected = selected, onClick = onSelect, label = { Text(if (selected) "Selected" else "Select") })
        }
    }
}

@Composable
fun RecoveryScreen(records: List<RecoveryRecord>, onValidate: (RecoveryRecord) -> Unit, onRemove: (RecoveryRecord) -> Unit) {
    if (records.isEmpty()) {
        EmptyFeatureScreen("Recovery is clear", "No orphaned or interrupted artifacts were detected.")
        return
    }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(records, key = RecoveryRecord::id) { record ->
            RecoveryRecordCard(record, onValidate, onRemove)
        }
    }
}

@Composable
private fun RecoveryRecordCard(record: RecoveryRecord, onValidate: (RecoveryRecord) -> Unit, onRemove: (RecoveryRecord) -> Unit) {
    var technicalExpanded by remember(record.id) { mutableStateOf(false) }
    XdmListCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                XdmCardTitle(recoveryProblemTitle(record))
                XdmSupportingText(record.reason)
            }
            StatusPill(record.classification.uiLabel(), record.classification.statusTone())
        }
        XdmMetadataText(recoveryRecommendedExplanation(record), maxLines = 3)
        XdmActionFlowRow {
            StatusPill(record.recommendedAction.uiLabel(), record.classification.statusTone())
            StatusPill(if (record.safeToResume) "Safe to resume" else "Needs review", if (record.safeToResume) XdmStatusTone.Success else XdmStatusTone.Warning)
        }
        XdmActionFlowRow {
            Button(onClick = { onValidate(record) }) { Text(recoveryPrimaryActionLabel(record)) }
            TextButton(onClick = { technicalExpanded = !technicalExpanded }) { Text(if (technicalExpanded) "Hide technical details" else "Technical details") }
            TextButton(onClick = { onRemove(record) }) { Text("Remove record only") }
        }
        XdmMetadataText("Remove record only hides this recovery item; it does not delete downloaded files or partial data.")
        if (technicalExpanded) {
            XdmListCard(compact = true) {
                XdmMetadataText("Artifact path: ${record.artifactPath}", maxLines = 3)
                XdmMetadataText(record.downloadId?.let { "Download ID: $it" } ?: "No linked download")
            }
        }
    }
}

@Composable
fun DiagnosticsScreen(
    state: MainUiState,
    browserStatus: BrowserIntegrationStatus,
    clipboardInbox: List<ClipboardInboxItem>,
    onRunAria2SmokeTest: () -> Unit,
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
    onScanClipboardText: (String) -> Unit,
    onAcceptClipboardItem: (ClipboardInboxItem) -> Unit,
    onDismissClipboardItem: (ClipboardInboxItem) -> Unit,
) {
    val context = LocalContext.current
    val redactedSummary = PrivacyDiagnosticsRedactor.redactedHealthSummary(
        report = state.releaseSecurityReport,
        downloadCount = state.downloads.size,
        mediaCaptureCount = state.mediaCaptures.size,
        automationCount = state.automationCommands.size,
        rejectedHandoffCount = state.automationCommands.count { it.status == AutomationCommandStatus.Rejected },
    )
    val installUpdateSummary = state.installUpdateReadinessReport.redactedSummary()
    val finalReleaseSummary = state.finalReleaseGateReport.redactedSummary()
    val supportSummary = "$redactedSummary\n\n$installUpdateSummary\n\n$finalReleaseSummary"
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { XdmSectionHeader("Runtime health") }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            XdmCardTitle("App integrity")
                            XdmSupportingText(state.releaseSecurityReport.summary)
                        }
                        TextButton(
                            onClick = { copyTextToClipboard(context, "XDM diagnostic summary", supportSummary) },
                            modifier = Modifier
                                .sizeIn(minWidth = 96.dp, minHeight = 48.dp)
                                .semantics { contentDescription = "Copy privacy-safe release summary" },
                        ) { Text("Copy summary") }
                    }
                    state.releaseSecurityReport.findings.take(3).forEach { finding ->
                        val severity = when (finding.severity) {
                            ReleaseSecuritySeverity.Info -> "Info"
                            ReleaseSecuritySeverity.Warning -> "Warning"
                            ReleaseSecuritySeverity.Blocking -> "Blocked"
                        }
                        XdmMetadataText("$severity: ${finding.title}")
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    XdmCardTitle("Update compatibility")
                    XdmSupportingText(state.installUpdateReadinessReport.summary)
                    state.installUpdateReadinessReport.checks.take(4).forEach { check ->
                        val severity = when (check.severity) {
                            ReleaseReadinessSeverity.Info -> "Info"
                            ReleaseReadinessSeverity.Warning -> "Warning"
                            ReleaseReadinessSeverity.Blocking -> "Blocked"
                        }
                        XdmMetadataText("$severity: ${check.title}")
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth().semantics { contentDescription = "Final release gate ${state.finalReleaseGateReport.summary}" }) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    XdmCardTitle("Release readiness")
                    XdmSupportingText(state.finalReleaseGateReport.summary)
                    state.finalReleaseGateReport.checks.take(4).forEach { check ->
                        val severity = when (check.severity) {
                            FinalReleaseGateSeverity.Info -> "Info"
                            FinalReleaseGateSeverity.Warning -> "Warning"
                            FinalReleaseGateSeverity.Blocking -> "Blocked"
                        }
                        XdmMetadataText("$severity: ${check.title}")
                    }
                }
            }
        }
        item { DiagnosticLine("Desktop parity", state.desktopParityReport.summary) }
        item { DiagnosticLine("Protocol coverage", state.protocolExpansionReport.summary) }
        item { DiagnosticLine("Settings exchange", "Import/export snapshot is available from Settings") }
        item { DiagnosticLine("Downloads", state.downloads.size.toString()) }
        item { DiagnosticLine("Queues", state.queues.size.toString()) }
        item { DiagnosticLine("Recovery records", state.recovery.size.toString()) }
        item { DiagnosticLine("Finalization journals", state.finalizationJournals.count { it.needsRecovery }.toString()) }
        item { DiagnosticLine("Media captures", state.mediaCaptures.size.toString()) }
        item { DiagnosticLine("Media variants", state.mediaVariants.size.toString()) }
        item { DiagnosticLine("Automation commands", state.automationCommands.size.toString()) }
        item { DiagnosticLine("Post-processing events", state.postProcessingAutomation.events.size.toString()) }
        item { DiagnosticLine("Browser origins", state.automationCommands.mapNotNull { it.originHost }.distinct().size.toString()) }
        item { DiagnosticLine("Rejected handoffs", state.automationCommands.count { it.status == AutomationCommandStatus.Rejected }.toString()) }
        item {
            BrowserIntegrationDiagnosticsCard(
                status = browserStatus,
                inbox = clipboardInbox,
                onScanClipboardText = onScanClipboardText,
                onAccept = onAcceptClipboardItem,
                onDismiss = onDismissClipboardItem,
            )
        }
        if (state.automationCommands.isNotEmpty()) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        XdmCardTitle("Recent browser handoffs")
                        state.automationCommands.take(4).forEach { command ->
                            XdmMetadataText(command.redactedDiagnosticLine())
                        }
                    }
                }
            }
        }
        item { DiagnosticLine("Native backend", "HTTP/HTTPS, checkpoints, resume and segmentation") }
        item { DiagnosticLine("Execution", "UIDT on Android 14+, foreground dataSync fallback") }
        item { DiagnosticLine("Active transfers", state.activeTransfers.activeCount.toString()) }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            XdmCardTitle("aria2 runtime")
                            XdmMetricText(state.aria2Diagnostics.status)
                        }
                        Button(
                            onClick = onRunAria2SmokeTest,
                            enabled = state.aria2Diagnostics.canRunSmokeTest,
                        ) {
                            Text(if (state.aria2Diagnostics.smokeTestRunning) "Testing…" else "Run probe")
                        }
                    }
                    XdmMetadataText(state.aria2Diagnostics.detail)
                }
            }
        }
        item {
            PostProcessingAutomationCard(
                automation = state.postProcessingAutomation,
                onEnabledChanged = null,
                onRetryFailed = onRetryPostProcessing,
                onClearEvents = onClearPostProcessingEvents,
            )
        }
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
    }
}

@Composable
private fun DiagnosticLine(label: String, value: String) {
    Card(Modifier.fillMaxWidth().semantics { contentDescription = "$label: $value" }) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            XdmCardTitle(label)
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun BrowserIntegrationDiagnosticsCard(
    status: BrowserIntegrationStatus,
    inbox: List<ClipboardInboxItem>,
    onScanClipboardText: (String) -> Unit,
    onAccept: (ClipboardInboxItem) -> Unit,
    onDismiss: (ClipboardInboxItem) -> Unit,
) {
    val context = LocalContext.current
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    Card(Modifier.fillMaxWidth().semantics { contentDescription = "Browser integration ${status.summary}" }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    XdmCardTitle("Browser integration and clipboard inbox")
                    XdmSupportingText(status.summary)
                }
                Button(
                    onClick = {
                        val text = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                        onScanClipboardText(text)
                    },
                ) { Text("Scan clipboard") }
            }
            XdmMetadataText("Share sheet, browser VIEW handoff, typed extras, sanitized headers, duplicate command handling, and clipboard review are active.")
            if (inbox.isEmpty()) {
                XdmMetadataText("Clipboard inbox is empty.")
            } else {
                inbox.take(5).forEach { item ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            XdmMetadataText("${item.status}: ${item.title ?: hostFromUrl(item.url)}", maxLines = 1)
                            XdmMetadataText(item.url, maxLines = 1)
                        }
                        TextButton(onClick = { onAccept(item) }, enabled = item.status == "New") { Text("Add") }
                        TextButton(onClick = { onDismiss(item) }, enabled = item.status == "New") { Text("Dismiss") }
                    }
                }
            }
        }
    }
}


@Composable
private fun TermuxBridgeDiagnosticsCard(
    termux: TermuxBridgeStatus,
    onRunProbe: () -> Unit,
    onRunRootProbe: () -> Unit,
    onCollectRootDiagnostics: () -> Unit,
    onKillStuckAria2WithRoot: () -> Unit,
) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth().semantics { contentDescription = "Termux bridge ${termux.readinessLabel}" }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    XdmCardTitle("Termux bridge")
                    XdmMetricText(termux.readinessLabel)
                }
                Button(onClick = onRunProbe, enabled = termux.canRunProbe) { Text("Probe tools") }
            }
            XdmSupportingText(termux.summary)
            XdmActionFlowRow {
                StatusPill(if (termux.termuxInstalled) "Termux installed" else "Termux missing", if (termux.termuxInstalled) XdmStatusTone.Success else XdmStatusTone.Warning)
                StatusPill(if (termux.runCommandPermissionGranted) "RUN_COMMAND ready" else "Permission needed", if (termux.runCommandPermissionGranted) XdmStatusTone.Success else XdmStatusTone.Warning)
                StatusPill(if (termux.rootAvailable) "Root available" else "Root optional", if (termux.rootAvailable) XdmStatusTone.Info else XdmStatusTone.Neutral)
            }
            termux.toolRows.forEach { row ->
                XdmMetadataText("${row.tool.displayName}: ${row.statusLabel} — ${row.versionLine}", maxLines = 2)
            }
            XdmSectionHeader("Optional root actions")
            XdmSupportingText("Root actions are launched through Termux as typed, logged operations. Root mode must be enabled before medium-risk actions can run.")
            XdmActionFlowRow {
                TextButton(onClick = onRunRootProbe, enabled = termux.canRunRootProbe) { Text("Probe root") }
                TextButton(onClick = onCollectRootDiagnostics, enabled = termux.canRunRootAction) { Text("Root diagnostics") }
                TextButton(onClick = onKillStuckAria2WithRoot, enabled = termux.canRunRootAction) { Text("Kill stuck aria2") }
            }
            XdmMetadataText(termux.lastRootMessage, maxLines = 3)
            termux.rootAudit.take(3).forEach { audit ->
                XdmMetadataText("Root audit: ${audit.summary}", maxLines = 2)
            }
            termux.recentRuns.firstOrNull()?.let { run ->
                XdmMetadataText("Last Termux run: ${run.summary} ${run.exitCode?.let { "exit $it" } ?: "pending"}", maxLines = 2)
            }
            TextButton(
                onClick = { copyTextToClipboard(context, "XDM Termux diagnostics", termux.diagnosticsSummary()) },
                modifier = Modifier.sizeIn(minWidth = 96.dp, minHeight = 48.dp),
            ) { Text("Copy Termux diagnostics") }
        }
    }
}


@Composable
private fun TermuxBridgeSettingsCard(
    termux: TermuxBridgeStatus,
    onRunProbe: () -> Unit,
    onOpenTermux: () -> Unit,
    onRootModeChanged: (TermuxRootMode) -> Unit,
    onRunRootProbe: () -> Unit,
    onCollectRootDiagnostics: () -> Unit,
    onKillStuckAria2WithRoot: () -> Unit,
    onFixDownloadPermissionsWithRoot: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().semantics { contentDescription = "Termux backend ${termux.readinessLabel}" }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    XdmCardTitle("External tools through Termux")
                    XdmSupportingText("Use Termux for aria2c, FFmpeg, FFprobe, yt-dlp, and Python without adding a raw shell to XDM.")
                }
                StatusPill(termux.readinessLabel, termux.statusTone())
            }
            XdmMetadataText(termux.summary)
            XdmActionFlowRow {
                Button(onClick = onRunProbe, enabled = termux.canRunProbe) { Text("Probe tools") }
                TextButton(onClick = onOpenTermux, enabled = termux.termuxInstalled) { Text("Open Termux") }
            }
            XdmSectionHeader("Optional root mode")
            XdmSupportingText("Root is off by default and only unlocks typed file/process actions; XDM never exposes a raw root shell endpoint.")
            XdmActionFlowRow {
                TermuxRootMode.entries.forEach { mode ->
                    FilterChip(
                        selected = termux.rootMode == mode,
                        onClick = { onRootModeChanged(mode) },
                        label = { Text(mode.label) },
                    )
                }
            }
            XdmMetadataText(termux.rootMode.description)
            XdmActionFlowRow {
                TextButton(onClick = onRunRootProbe, enabled = termux.canRunRootProbe) { Text("Probe root") }
                TextButton(onClick = onCollectRootDiagnostics, enabled = termux.canRunRootAction) { Text("Root diagnostics") }
                TextButton(onClick = onKillStuckAria2WithRoot, enabled = termux.canRunRootAction) { Text("Kill stuck aria2") }
                TextButton(onClick = onFixDownloadPermissionsWithRoot, enabled = termux.canRunRootAction) { Text("Fix XDM permissions") }
            }
            termux.rootAudit.take(4).forEach { audit ->
                XdmMetadataText("Root audit: ${audit.summary}", maxLines = 2)
            }
        }
    }
}


@Composable
private fun TermuxAria2CockpitCard(
    aria2: TermuxAria2CockpitStatus,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onProbe: () -> Unit,
    onRefreshTasks: () -> Unit,
    onPauseAll: () -> Unit,
    onResumeAll: () -> Unit,
    onSaveSession: () -> Unit,
) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth().semantics { contentDescription = "Termux aria2 cockpit ${aria2.readinessLabel}" }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    XdmCardTitle("Termux aria2 cockpit")
                    XdmMetricText(aria2.readinessLabel)
                }
                StatusPill(aria2.daemonState.label, aria2.statusTone())
            }
            XdmSupportingText("Manage a Termux-hosted aria2 RPC daemon with an app-generated secret, private session file, and typed controls.")
            aria2.config?.let { config ->
                XdmMetadataText("RPC: ${config.redactedEndpoint} • secret ${config.redactedSecret}")
                XdmMetadataText("Session: ${config.sessionFile}", maxLines = 2)
                XdmMetadataText("Downloads: ${config.downloadDir}", maxLines = 2)
            } ?: XdmMetadataText("Enable Termux aria2 in Settings to generate the RPC secret and session paths.")
            XdmActionFlowRow {
                Button(onClick = onStart, enabled = aria2.canStart) { Text("Start daemon") }
                TextButton(onClick = onStop, enabled = aria2.canStop) { Text("Stop") }
                TextButton(onClick = onProbe, enabled = aria2.canProbe) { Text("Probe RPC") }
                TextButton(onClick = onRefreshTasks, enabled = aria2.canControlTasks) { Text("Tasks") }
                TextButton(onClick = onSaveSession, enabled = aria2.canControlTasks) { Text("Save session") }
            }
            XdmActionFlowRow {
                TextButton(onClick = onPauseAll, enabled = aria2.canControlTasks) { Text("Pause all") }
                TextButton(onClick = onResumeAll, enabled = aria2.canControlTasks) { Text("Resume all") }
                TextButton(
                    onClick = { copyTextToClipboard(context, "XDM Termux aria2 diagnostics", aria2.diagnosticsSummary()) },
                    enabled = aria2.config != null,
                ) { Text("Copy aria2 diagnostics") }
            }
            XdmMetadataText("Health: ${aria2.lastHealth}", maxLines = 3)
            XdmMetadataText("Last action: ${aria2.lastAction}", maxLines = 3)
            if (aria2.taskRows.isEmpty()) {
                XdmMetadataText("No active Termux aria2 tasks have been parsed yet. Use Tasks after the daemon is running.")
            } else {
                aria2.taskRows.take(4).forEach { task ->
                    XdmMetadataText("${task.gid}: ${task.status} • ${task.progressLabel} • ${task.fileName}", maxLines = 2)
                }
            }
        }
    }
}

@Composable
private fun TermuxAria2SettingsCard(
    aria2: TermuxAria2CockpitStatus,
    onEnabledChanged: (Boolean) -> Unit,
    onRotateSecret: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().semantics { contentDescription = "Termux aria2 backend ${aria2.readinessLabel}" }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    XdmCardTitle("Termux aria2 backend")
                    XdmSupportingText("Use a Termux-hosted aria2 RPC daemon for large, mirror-heavy or long-running jobs while keeping native Android as the fallback.")
                }
                Switch(
                    checked = aria2.enabled,
                    onCheckedChange = onEnabledChanged,
                    modifier = Modifier.semantics { stateDescription = if (aria2.enabled) "Termux aria2 enabled" else "Termux aria2 disabled" },
                )
            }
            XdmActionFlowRow {
                StatusPill(aria2.daemonState.label, aria2.statusTone())
                StatusPill(if (aria2.config != null) "Secret generated" else "No secret", if (aria2.config != null) XdmStatusTone.Success else XdmStatusTone.Neutral)
            }
            aria2.config?.let { config ->
                XdmMetadataText("Endpoint ${config.redactedEndpoint}; session and logs stay under Termux home.", maxLines = 2)
                TextButton(onClick = onRotateSecret, enabled = aria2.enabled && aria2.daemonState != TermuxAria2DaemonState.Running) { Text("Rotate RPC secret") }
            } ?: XdmMetadataText("Enable this to generate a local RPC secret, download directory, session file, and log path.")
        }
    }
}

private fun TermuxAria2CockpitStatus.statusTone(): XdmStatusTone = when (daemonState) {
    TermuxAria2DaemonState.Running -> XdmStatusTone.Success
    TermuxAria2DaemonState.Starting, TermuxAria2DaemonState.Stopping -> XdmStatusTone.Info
    TermuxAria2DaemonState.Failed -> XdmStatusTone.Warning
    TermuxAria2DaemonState.Disabled, TermuxAria2DaemonState.Stopped -> XdmStatusTone.Neutral
}

private fun TermuxBridgeStatus.statusTone(): XdmStatusTone = when {
    !termuxInstalled || !runCommandPermissionGranted -> XdmStatusTone.Warning
    toolRows.any { it.available } -> XdmStatusTone.Success
    else -> XdmStatusTone.Info
}

@Composable
private fun StatusPill(text: String, tone: XdmStatusTone = XdmStatusTone.Neutral) {
    XdmStatusBadge(text, tone = tone)
}


@Composable
fun SettingsScreen(
    compact: Boolean,
    capabilities: List<BackendCapabilityRow>,
    migrations: List<BackendMigrationRecord>,
    installUpdateReadinessReport: com.mikeyphw.xdm.android.model.InstallUpdateReadinessReport,
    finalReleaseGateReport: com.mikeyphw.xdm.android.model.FinalReleaseGateReport,
    proxySettings: ProxyCredentialSettings,
    postProcessingSettings: PostProcessingSettings,
    settingsExportText: String,
    backupRestoreReport: BackupRestoreReport,
    destinationRules: List<DestinationRule>,
    duplicateRules: List<DuplicateUrlRule>,
    protocolExpansionReport: ProtocolExpansionReport,
    releasePackagingReport: ReleasePackagingReport,
    termuxBridge: TermuxBridgeStatus,
    termuxAria2: TermuxAria2CockpitStatus,
    postProcessingAutomation: PostProcessingAutomationStatus,
    onCompactChanged: (Boolean) -> Unit,
    onProxyChanged: (ProxyCredentialSettings) -> Unit,
    onPostProcessingChanged: (PostProcessingSettings) -> Unit,
    onImportSettings: (String) -> Unit,
    onSaveDestinationRule: (String, DestinationRuleMatch, String, String) -> Unit,
    onSaveDuplicateRule: (String, DuplicateUrlAction) -> Unit,
    onRunTermuxProbe: () -> Unit,
    onOpenTermux: () -> Unit,
    onRootModeChanged: (TermuxRootMode) -> Unit,
    onRunTermuxRootProbe: () -> Unit,
    onCollectRootDiagnostics: () -> Unit,
    onKillStuckAria2WithRoot: () -> Unit,
    onFixDownloadPermissionsWithRoot: () -> Unit,
    onTermuxAria2EnabledChanged: (Boolean) -> Unit,
    onRotateTermuxAria2Secret: () -> Unit,
    onPostProcessingAutomationEnabledChanged: (Boolean) -> Unit,
    onRetryPostProcessing: () -> Unit,
    onClearPostProcessingEvents: () -> Unit,
) {
    val context = LocalContext.current
    var importText by remember { mutableStateOf("") }
    var proxyEnabled by remember(proxySettings) { mutableStateOf(proxySettings.enabled) }
    var proxyHost by remember(proxySettings) { mutableStateOf(proxySettings.host) }
    var proxyPort by remember(proxySettings) { mutableStateOf(proxySettings.port?.toString().orEmpty()) }
    var proxyUsername by remember(proxySettings) { mutableStateOf(proxySettings.username) }
    var proxyAlias by remember(proxySettings) { mutableStateOf(proxySettings.credentialAlias) }
    var postEnabled by remember(postProcessingSettings) { mutableStateOf(postProcessingSettings.enabled) }
    var postPreset by remember(postProcessingSettings) { mutableStateOf(postProcessingSettings.preset) }
    var postLabel by remember(postProcessingSettings) { mutableStateOf(postProcessingSettings.customCommandLabel) }
    var destinationRuleName by remember { mutableStateOf("") }
    var destinationRulePattern by remember { mutableStateOf("") }
    var destinationRuleMatch by remember { mutableStateOf(DestinationRuleMatch.Host) }
    var duplicateHost by remember { mutableStateOf("") }
    var duplicateAction by remember { mutableStateOf(DuplicateUrlAction.OpenExisting) }
    val proxyDraft = ProxyCredentialSettings(proxyEnabled, proxyHost, proxyPort.toIntOrNull()?.takeIf { it in 1..65535 }, proxyUsername, proxyAlias)
    val proxyDirty = proxyDraft != proxySettings
    val proxyPortValid = proxyPort.isBlank() || proxyPort.toIntOrNull()?.let { it in 1..65535 } == true
    val postDraft = PostProcessingSettings(postEnabled, postPreset, postLabel)
    val postDirty = postDraft != postProcessingSettings

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { XdmSectionHeader("Appearance") }
        item {
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        XdmCardTitle("Compact download cards")
                        XdmMetadataText("Reduce vertical spacing in the download list.")
                    }
                    Switch(
                        checked = compact,
                        onCheckedChange = onCompactChanged,
                        modifier = Modifier.semantics { stateDescription = if (compact) "Compact cards enabled" else "Compact cards disabled" },
                    )
                }
            }
        }
        item { XdmSectionHeader("Settings import/export") }
        item {
            Card(Modifier.fillMaxWidth().semantics { contentDescription = "Settings import export snapshot" }) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    XdmCardTitle("Portable settings snapshot")
                    XdmSupportingText("Copy a safe backup, paste one back here, and review whether it looks ready before importing.")
                    XdmMetadataText(backupRestoreReport.summary)
                    XdmActionFlowRow {
                        TextButton(onClick = { copyTextToClipboard(context, "XDM settings snapshot", settingsExportText) }) { Text("Copy export") }
                        StatusPill(if (importText.isBlank()) "No import" else "Import ready", if (importText.isBlank()) XdmStatusTone.Neutral else XdmStatusTone.Info)
                    }
                    OutlinedTextField(
                        importText,
                        { importText = it },
                        label = { Text("Paste settings snapshot") },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 6,
                        supportingText = { Text("Secrets are not included in exported snapshots.") },
                    )
                    XdmActionFlowRow {
                        Button(onClick = { onImportSettings(importText); importText = "" }, enabled = importText.isNotBlank()) { Text("Import snapshot") }
                        if (importText.isNotBlank()) {
                            TextButton(onClick = { importText = "" }) { Text("Clear") }
                        }
                    }
                }
            }
        }
        item { XdmSectionHeader("Rules and restore hardening") }
        item {
            Card(Modifier.fillMaxWidth().semantics { contentDescription = "Destination rules ${destinationRules.size}" }) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    XdmCardTitle("Destination rules")
                    XdmSupportingText("Route new downloads by host, extension, MIME type, or fallback destination before the download is queued.")
                    XdmActionFlowRow {
                        DestinationRuleMatch.entries.forEach { match ->
                            FilterChip(selected = destinationRuleMatch == match, onClick = { destinationRuleMatch = match }, label = { Text(match.name.lowercase().replaceFirstChar { it.titlecase() }) })
                        }
                    }
                    OutlinedTextField(destinationRuleName, { destinationRuleName = it }, label = { Text("Rule name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(destinationRulePattern, { destinationRulePattern = it }, label = { Text("Pattern") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Button(
                        onClick = {
                            onSaveDestinationRule(destinationRuleName, destinationRuleMatch, destinationRulePattern, settingsExportText.lineSequence().firstOrNull { it.startsWith("destinationUri=") }?.substringAfter('=').orEmpty())
                            destinationRuleName = ""
                            destinationRulePattern = ""
                        },
                        enabled = destinationRuleName.isNotBlank() && destinationRulePattern.isNotBlank(),
                    ) { Text("Save destination rule") }
                    destinationRules.take(4).forEach { rule -> XdmMetadataText("${rule.name}: ${rule.match.name} ${rule.pattern} → ${rule.destinationUri}", maxLines = 2) }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth().semantics { contentDescription = "Duplicate URL rules ${duplicateRules.size}" }) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    XdmCardTitle("Duplicate URL rules")
                    XdmSupportingText("Detect repeated source URLs before enqueueing and prefer opening the existing record by default.")
                    OutlinedTextField(duplicateHost, { duplicateHost = it }, label = { Text("Host pattern") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    XdmActionFlowRow {
                        DuplicateUrlAction.entries.forEach { action ->
                            FilterChip(selected = duplicateAction == action, onClick = { duplicateAction = action }, label = { Text(action.name.replace(Regex("([a-z])([A-Z])"), "$1 $2")) })
                        }
                    }
                    Button(onClick = { onSaveDuplicateRule(duplicateHost, duplicateAction); duplicateHost = "" }, enabled = duplicateHost.isNotBlank()) { Text("Save duplicate rule") }
                    duplicateRules.take(4).forEach { rule -> XdmMetadataText("${rule.hostPattern}: ${rule.action.name} (${if (rule.enabled) "enabled" else "disabled"})") }
                }
            }
        }
        item { XdmSectionHeader("Proxy and credentials") }
        item {
            Card(Modifier.fillMaxWidth().semantics { contentDescription = "Proxy credential profile ${proxySettings.redactedSummary}" }) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            XdmCardTitle("Proxy profile")
                            XdmMetadataText(proxySettings.redactedSummary)
                        }
                        StatusPill(if (proxyDirty) "Unsaved" else "Saved", if (proxyDirty) XdmStatusTone.Warning else XdmStatusTone.Success)
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        XdmSupportingText("Use proxy", modifier = Modifier.weight(1f))
                        Switch(checked = proxyEnabled, onCheckedChange = { proxyEnabled = it })
                    }
                    OutlinedTextField(proxyHost, { proxyHost = it }, label = { Text("Host") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(
                        proxyPort,
                        { proxyPort = it.filter { char -> char.isDigit() }.take(5) },
                        label = { Text("Port") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isError = !proxyPortValid,
                        supportingText = { Text(if (proxyPortValid) "Optional. Use 1–65535." else "Port must be between 1 and 65535.") },
                    )
                    OutlinedTextField(proxyUsername, { proxyUsername = it }, label = { Text("Username") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(proxyAlias, { proxyAlias = it }, label = { Text("Credential alias") }, modifier = Modifier.fillMaxWidth(), singleLine = true, supportingText = { Text("Store a label only; passwords stay outside exported diagnostics and settings snapshots.") })
                    XdmActionFlowRow {
                        Button(onClick = { onProxyChanged(proxyDraft) }, enabled = proxyDirty && proxyPortValid) { Text("Save proxy profile") }
                        if (proxyDirty) {
                            TextButton(
                                onClick = {
                                    proxyEnabled = proxySettings.enabled
                                    proxyHost = proxySettings.host
                                    proxyPort = proxySettings.port?.toString().orEmpty()
                                    proxyUsername = proxySettings.username
                                    proxyAlias = proxySettings.credentialAlias
                                },
                            ) { Text("Reset") }
                        }
                    }
                }
            }
        }
        item { XdmSectionHeader("Termux backend") }
        item {
            TermuxBridgeSettingsCard(
                termux = termuxBridge,
                onRunProbe = onRunTermuxProbe,
                onOpenTermux = onOpenTermux,
                onRootModeChanged = onRootModeChanged,
                onRunRootProbe = onRunTermuxRootProbe,
                onCollectRootDiagnostics = onCollectRootDiagnostics,
                onKillStuckAria2WithRoot = onKillStuckAria2WithRoot,
                onFixDownloadPermissionsWithRoot = onFixDownloadPermissionsWithRoot,
            )
        }
        item { TermuxAria2SettingsCard(termuxAria2, onTermuxAria2EnabledChanged, onRotateTermuxAria2Secret) }
        item { XdmSectionHeader("Conversion and post-processing") }
        item { PostProcessingAutomationCard(postProcessingAutomation, onPostProcessingAutomationEnabledChanged, onRetryPostProcessing, onClearPostProcessingEvents) }
        item {
            Card(Modifier.fillMaxWidth().semantics { contentDescription = "Conversion post processing ${postProcessingSettings.redactedSummary}" }) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            XdmCardTitle("Post-processing hook")
                            XdmMetadataText(postProcessingSettings.redactedSummary)
                        }
                        StatusPill(if (postDirty) "Unsaved" else "Saved", if (postDirty) XdmStatusTone.Warning else XdmStatusTone.Success)
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        XdmSupportingText("Run after completion", modifier = Modifier.weight(1f))
                        Switch(checked = postEnabled, onCheckedChange = { postEnabled = it })
                    }
                    XdmActionFlowRow {
                        ConversionPreset.entries.forEach { preset ->
                            FilterChip(selected = postPreset == preset, onClick = { postPreset = preset }, label = { Text(preset.displayName()) })
                        }
                    }
                    OutlinedTextField(postLabel, { postLabel = it }, label = { Text("Custom label") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    XdmActionFlowRow {
                        Button(onClick = { onPostProcessingChanged(postDraft) }, enabled = postDirty) { Text("Save post-processing") }
                        if (postDirty) {
                            TextButton(
                                onClick = {
                                    postEnabled = postProcessingSettings.enabled
                                    postPreset = postProcessingSettings.preset
                                    postLabel = postProcessingSettings.customCommandLabel
                                },
                            ) { Text("Reset") }
                        }
                    }
                    XdmMetadataText("Conversion starts only when the selected backend supports the chosen preset.")
                }
            }
        }
        item { XdmSectionHeader("Protocol expansion") }
        item {
            Card(Modifier.fillMaxWidth().semantics { contentDescription = "Protocol expansion ${protocolExpansionReport.summary}" }) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    XdmCardTitle(protocolExpansionReport.summary)
                    protocolExpansionReport.rows.forEach { row -> XdmMetadataText("${row.protocol.uppercase()}: ${row.recommendation}") }
                }
            }
        }
        item { XdmSectionHeader("Backend strategy") }
        items(capabilities, key = { it.backend.name }) { capability ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        XdmCardTitle(capability.backend.uiLabel())
                        StatusPill(if (capability.available) "Available" else "Unavailable", if (capability.available) XdmStatusTone.Success else XdmStatusTone.Warning)
                    }
                    XdmSupportingText(capability.summary)
                    XdmMetadataText("Protocols: ${capability.protocols.sorted().joinToString().ifBlank { "None" }}")
                    Text(
                        listOfNotNull(
                            "Segments".takeIf { capability.segmentation },
                            "Mirrors".takeIf { capability.mirrors },
                            "Metalink".takeIf { capability.metalink },
                            "SAF".takeIf { capability.saf },
                            "Repair".takeIf { capability.selectiveRepair },
                            "Media".takeIf { capability.media },
                        ).joinToString(" • ").ifBlank { "No optional capabilities" },
                        style = MaterialTheme.typography.labelMedium,
                    )
                    XdmMetadataText("Diagnostics: ${capability.diagnosticDetail.uiLabel()} • Battery: ${capability.batteryImpact.uiLabel()}")
                }
            }
        }
        if (migrations.isNotEmpty()) {
            item { XdmSectionHeader("Recent backend migrations") }
            items(migrations.take(5), key = BackendMigrationRecord::id) { migration ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        XdmCardTitle("${migration.sourceBackend.uiLabel()} → ${migration.targetBackend.uiLabel()}")
                        XdmMetricText(migration.stage.uiLabel())
                        XdmMetadataText(migration.message)
                    }
                }
            }
        }
    }
}




private enum class DownloadSort(val label: String) {
    Attention("Needs attention"),
    Recent("Newest first"),
    Name("Name"),
    Progress("Progress"),
}

private fun List<Download>.sortForUi(sort: DownloadSort): List<Download> = when (sort) {
    DownloadSort.Attention -> sortedWith(
        compareByDescending<Download> { if (it.state == DownloadState.Failed || it.state == DownloadState.RecoveryRequired) 1 else 0 }
            .thenByDescending { if (it.state == DownloadState.Downloading || it.state == DownloadState.Connecting) 1 else 0 }
            .thenByDescending { it.createdAtEpochMs },
    )
    DownloadSort.Recent -> sortedByDescending { it.createdAtEpochMs }
    DownloadSort.Name -> sortedBy { it.fileName.lowercase() }
    DownloadSort.Progress -> sortedByDescending { it.progressFraction }
}

private fun Download.matchesQuery(query: String): Boolean {
    val needle = query.trim().lowercase()
    if (needle.isBlank()) return true
    return listOf(fileName, sourceUrl, destinationUri, userLabel.orEmpty(), backend.uiLabel(), state.uiLabel())
        .any { it.lowercase().contains(needle) }
}

private fun copyTextToClipboard(context: Context, label: String, value: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard?.setPrimaryClip(ClipData.newPlainText(label, value))
}

private fun Download.accessibilitySummary(): String = buildString {
    append(fileName)
    append(", ")
    append(state.uiLabel().lowercase())
    append(" using ")
    append(backend.uiLabel())
    totalBytes?.let { total ->
        append(", ")
        append(bytesReceived.formatBytes())
        append(" of ")
        append(total.formatBytes())
    }
}

private fun Download.progressAccessibilitySummary(): String {
    val total = totalBytes ?: return "Progress unavailable"
    val percent = (progressFraction * 100).toInt().coerceIn(0, 100)
    return "$percent percent, ${bytesReceived.formatBytes()} of ${total.formatBytes()}"
}


private fun buildScheduleConstraintsJson(
    days: String,
    startTime: String,
    endTime: String,
    unmeteredOnly: Boolean,
    chargingRequired: Boolean,
    minimumBattery: String,
): String {
    val json = JSONObject()
    days.trim().takeIf(String::isNotBlank)?.let { json.put("days", it) }
    startTime.trim().takeIf(String::isNotBlank)?.let { json.put("startTime", it) }
    endTime.trim().takeIf(String::isNotBlank)?.let { json.put("endTime", it) }
    if (unmeteredOnly) json.put("unmetered", true)
    if (chargingRequired) json.put("charging", true)
    minimumBattery.toIntOrNull()?.coerceIn(0, 100)?.let { json.put("minimumBatteryPercent", it) }
    return json.toString()
}

private fun scheduleBoolean(rawJson: String, key: String, default: Boolean): Boolean = runCatching {
    JSONObject(rawJson).optBoolean(key, default)
}.getOrDefault(default)

private fun scheduleString(rawJson: String, key: String, default: String): String = runCatching {
    JSONObject(rawJson).optString(key).ifBlank { default }
}.getOrDefault(default)

private fun scheduleInt(rawJson: String, key: String): Int? = runCatching {
    JSONObject(rawJson).takeIf { it.has(key) }?.optInt(key)
}.getOrNull()

private fun nextRunSummary(enabled: Boolean, rawJson: String): String {
    if (!enabled) return "Disabled; it will not run until enabled."
    val start = scheduleString(rawJson, "startTime", "")
    val end = scheduleString(rawJson, "endTime", "")
    val days = scheduleString(rawJson, "days", "")
    val window = when {
        start.isNotBlank() && end.isNotBlank() -> "$start–$end"
        start.isNotBlank() -> "after $start"
        else -> "when conditions match"
    }
    return listOf(days.takeIf(String::isNotBlank), "Next eligible window: $window").filterNotNull().joinToString(" • ")
}

private fun mediaOriginLabel(capture: MediaCaptureRecord): String = listOfNotNull(
    capture.pageUrl?.let(::hostFromUrl),
    hostFromUrl(capture.sourceUrl),
).distinct().joinToString(" • ").ifBlank { "Captured media" }

private fun hostFromUrl(url: String): String = runCatching {
    url.substringAfter("://", url).substringBefore('/').substringBefore('?').ifBlank { url }
}.getOrDefault(url)

private fun formatDurationSeconds(durationMs: Long): String {
    val totalSeconds = (durationMs / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"
}

private fun variantDetails(variant: MediaVariant): String = listOfNotNull(
    variant.mimeType,
    variant.width?.let { width -> variant.height?.let { height -> "${width}×${height}" } },
    variant.bitrateBitsPerSecond?.takeIf { it > 0 }?.let { "${it / 1000} kbps" },
    variant.codecs,
    variant.language?.takeIf(String::isNotBlank)?.let { "Language: $it" },
).joinToString(" • ").ifBlank { variant.kind.name.lowercase().replaceFirstChar { it.titlecase() } }

private fun recoveryProblemTitle(record: RecoveryRecord): String = when (record.classification) {
    RecoveryClassification.ReadyToResume -> "Interrupted download can resume"
    RecoveryClassification.NeedsRemoteValidation -> "Download needs remote validation"
    RecoveryClassification.NeedsRepair -> "Partial file needs repair"
    RecoveryClassification.MissingPartialFile -> "Partial file is missing"
    RecoveryClassification.RemoteFileChanged -> "Remote file changed"
    RecoveryClassification.CompletionRecovered -> "Completed file was recovered"
    RecoveryClassification.FinalizationInterrupted -> "Finishing was interrupted"
    RecoveryClassification.BackendTaskOrphaned -> "Backend task lost its owner"
    RecoveryClassification.OrphanedArtifact -> "Untracked partial file found"
}

private fun recoveryRecommendedExplanation(record: RecoveryRecord): String = when (record.recommendedAction) {
    RecoveryAction.Resume -> "The partial data looks reusable. Resume keeps existing bytes and continues safely."
    RecoveryAction.Validate -> "Validate checks the partial data before XDM decides whether it can be reused."
    RecoveryAction.VerifyAndRepair -> "XDM should verify trusted blocks and repair only the damaged range when possible."
    RecoveryAction.RestartFromZero -> "Restart discards reuse assumptions and creates a fresh backend task."
    RecoveryAction.AdoptOrphan -> "Adopt links this artifact to a managed download only after validation."
    RecoveryAction.LocateFile -> "Locate the missing file before any resume or repair action."
    RecoveryAction.RemoveRecord -> "Remove only clears the recovery warning; it does not delete user files."
}

private fun recoveryPrimaryActionLabel(record: RecoveryRecord): String = when (record.recommendedAction) {
    RecoveryAction.Resume -> "Resume download"
    RecoveryAction.Validate -> "Validate safely"
    RecoveryAction.VerifyAndRepair -> "Verify and repair"
    RecoveryAction.RestartFromZero -> "Restart download"
    RecoveryAction.AdoptOrphan -> "Validate and adopt"
    RecoveryAction.LocateFile -> "Locate file"
    RecoveryAction.RemoveRecord -> "Review record"
}

private fun scheduleConstraintSummary(rawJson: String): List<String> {
    if (rawJson.isBlank() || rawJson.trim() == "{}") return listOf("No additional conditions")
    return runCatching {
        val json = JSONObject(rawJson)
        buildList {
            json.optString("days").takeIf { it.isNotBlank() }?.let { add("Days: ${humanizeValue(it)}") }
            json.optString("startTime").takeIf { it.isNotBlank() }?.let { start ->
                val end = json.optString("endTime").takeIf { it.isNotBlank() }
                add(if (end == null) "Starts at $start" else "Time: $start–$end")
            }
            if (json.optBoolean("unmetered", false) || json.optBoolean("unmeteredOnly", false)) add("Network: Unmetered only")
            if (json.optBoolean("charging", false) || json.optBoolean("requiresCharging", false)) add("Power: Charging required")
            json.optInt("minimumBatteryPercent", -1).takeIf { it >= 0 }?.let { add("Battery: At least $it%") }
            json.optString("networkType").takeIf { it.isNotBlank() }?.let { add("Network: ${humanizeValue(it)}") }
            if (isEmpty()) {
                json.keys().asSequence().forEach { key ->
                    val value = json.opt(key)
                    if (value != null && value != JSONObject.NULL) add("${humanizeValue(key)}: ${humanizeJsonValue(value)}")
                }
            }
        }.ifEmpty { listOf("No additional conditions") }
    }.getOrElse { listOf("Schedule conditions are saved and will be applied automatically") }
}

private fun humanizeJsonValue(value: Any): String = when (value) {
    is Boolean -> if (value) "Required" else "Not required"
    is JSONArray -> (0 until value.length()).joinToString(", ") { humanizeValue(value.optString(it)) }
    else -> humanizeValue(value.toString())
}

private fun humanizeValue(value: String): String = value
    .replace('_', ' ')
    .replace(Regex("([a-z])([A-Z])"), "$1 $2")
    .trim()
    .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

@Composable
fun EmptyFeatureScreen(title: String, description: String) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        XdmSectionHeader(title)
        Spacer(Modifier.height(8.dp))
        Text(description, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun Download.fileManagementSummary(): String = buildString {
    appendLine("File: $fileName")
    appendLine("State: ${state.uiLabel()}")
    appendLine("Backend: ${backend.uiLabel()}")
    appendLine("URL: $sourceUrl")
    appendLine("Destination: $destinationUri")
    appendLine("Progress: ${bytesReceived.formatBytes()}${totalBytes?.let { " / ${it.formatBytes()}" } ?: ""}")
    mimeType?.takeIf { it.isNotBlank() }?.let { appendLine("MIME type: $it") }
    userLabel?.takeIf { it.isNotBlank() }?.let { appendLine("Label: $it") }
    errorMessage?.takeIf { it.isNotBlank() }?.let { appendLine("Last error: $it") }
}.trimEnd()
