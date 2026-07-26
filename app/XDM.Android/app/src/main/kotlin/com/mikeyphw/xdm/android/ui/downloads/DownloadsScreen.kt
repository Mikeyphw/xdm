package com.mikeyphw.xdm.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.weight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.mikeyphw.xdm.android.model.BackendCapabilityRow
import com.mikeyphw.xdm.android.model.ChecksumResult
import com.mikeyphw.xdm.android.model.Download
import com.mikeyphw.xdm.android.model.DownloadDashboardOrdering
import com.mikeyphw.xdm.android.model.DownloadState
import com.mikeyphw.xdm.android.model.DownloadTag
import com.mikeyphw.xdm.android.model.DownloadTagAssignment
import com.mikeyphw.xdm.android.model.HistoryManagementPolicy
import com.mikeyphw.xdm.android.model.HistoryManagementReport
import com.mikeyphw.xdm.android.model.OperationalActivitySummary
import com.mikeyphw.xdm.android.model.OrganizationPowerToolsReport
import com.mikeyphw.xdm.android.model.QueueIntelligenceSummary
import com.mikeyphw.xdm.android.model.SavedSearch
import com.mikeyphw.xdm.android.model.VerificationRecord
import com.mikeyphw.xdm.android.scheduler.ActiveTransferSummary
import com.mikeyphw.xdm.android.ui.common.UiAudience
import com.mikeyphw.xdm.android.ui.common.UiSurface
import com.mikeyphw.xdm.android.util.formatSpeed

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
    val windowClass = LocalXdmWindowClass.current
    var filter by remember { mutableStateOf(DownloadWorkspaceFilter.Active) }
    var query by remember { mutableStateOf("") }
    var searchVisible by remember { mutableStateOf(false) }
    var ordering by remember { mutableStateOf(DownloadDashboardOrdering.Smart) }
    var includeArchived by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    var detailDownloadId by remember { mutableStateOf<String?>(null) }
    var organizeVisible by remember { mutableStateOf(false) }

    val metrics = DownloadsWorkspacePlanner.metrics(downloads.filterNot { it.archived })
    val visibleDownloads = DownloadsWorkspacePlanner.visibleDownloads(
        downloads = downloads,
        filter = filter,
        query = query,
        includeArchived = includeArchived,
        ordering = ordering,
    )
    val selectedDownloads = downloads.filter { it.id in selectedIds }
    val detailDownload = downloads.firstOrNull { it.id == detailDownloadId }
    val heldDownload = DownloadsWorkspacePlanner.firstPolicyHeldDownload(downloads)
    val copy = DownloadsWorkspacePlanner.copyFor(filter)
    val selectionMode = selectedIds.isNotEmpty()

    LaunchedEffect(downloads, detailDownloadId) {
        if (detailDownloadId != null && downloads.none { it.id == detailDownloadId }) detailDownloadId = null
    }
    LaunchedEffect(visibleDownloads, windowClass) {
        if (windowClass == XdmWindowClass.Expanded && detailDownloadId == null) {
            detailDownloadId = visibleDownloads.firstOrNull()?.id
        }
    }

    Column(Modifier.fillMaxSize()) {
        DownloadsOverviewHeader(
            windowClass = windowClass,
            activeCount = active.activeCount,
            aggregateSpeed = metrics.aggregateSpeedBytesPerSecond,
            remainingSeconds = metrics.remainingSeconds,
            queuedCount = metrics.queuedCount,
            searchVisible = searchVisible,
            onToggleSearch = { searchVisible = !searchVisible },
            onOpenOrganize = { organizeVisible = true },
        )

        if (searchVisible) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search downloads") },
                placeholder = { Text("File, source, destination, or backend") },
                singleLine = true,
                trailingIcon = {
                    IconButton(onClick = { query = ""; searchVisible = false }) {
                        Icon(Icons.Rounded.Close, contentDescription = "Close search")
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }

        if (heldDownload != null) {
            XdmNoticeRow(
                text = "Smart queue is protecting your connection. ${heldDownload.fileName} is waiting for an allowed network.",
                icon = Icons.Rounded.Wifi,
                tone = XdmStatusTone.Info,
                actionLabel = "Start now",
                onAction = { onStartIgnoringQueuePolicy(heldDownload) },
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
        } else if (queueIntelligence.heldForNetwork + queueIntelligence.heldForPower + queueIntelligence.heldForStorage + queueIntelligence.heldForSchedule + queueIntelligence.waitingForRetry > 0) {
            XdmNoticeRow(
                text = queueIntelligence.message,
                icon = Icons.Rounded.Wifi,
                tone = XdmStatusTone.Info,
                actionLabel = "Check now",
                onAction = onEvaluateQueueIntelligence,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
        }

        XdmSegmentedControl(
            options = DownloadWorkspaceFilter.entries,
            selected = filter,
            label = DownloadWorkspaceFilter::label,
            onSelected = {
                filter = it
                if (windowClass == XdmWindowClass.Expanded) detailDownloadId = null
            },
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        )

        DownloadSectionHeader(
            title = copy.title,
            subtitle = copy.subtitle,
            activeFilter = filter == DownloadWorkspaceFilter.Active,
            activeCount = active.activeCount,
            pausedCount = downloads.count { it.state == DownloadState.Paused },
            selectionCount = selectedIds.size,
            onPauseAll = onPauseAll,
            onResumeAll = onResumeAll,
            onClearSelection = { selectedIds = emptySet() },
            onOpenOrganize = { organizeVisible = true },
        )

        if (windowClass == XdmWindowClass.Expanded) {
            Row(
                Modifier.fillMaxWidth().weight(1f).padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                DownloadWorkspaceList(
                    downloads = visibleDownloads,
                    compact = compact,
                    selectionMode = selectionMode,
                    selectedIds = selectedIds,
                    emptyTitle = if (query.isBlank()) copy.emptyTitle else "No matching downloads",
                    emptyDescription = if (query.isBlank()) copy.emptyDescription else "Try a broader search or another filter.",
                    onDownloadClick = { download ->
                        if (selectionMode) {
                            selectedIds = selectedIds.toggle(download.id)
                        } else {
                            detailDownloadId = download.id
                        }
                    },
                    onDownloadLongClick = { download -> selectedIds = selectedIds.toggle(download.id) },
                    onPrimaryAction = { download ->
                        if (download.primaryActionUsesToggle()) onTogglePause(download) else detailDownloadId = download.id
                    },
                    modifier = Modifier.weight(0.58f).fillMaxHeight(),
                )
                Box(
                    Modifier.weight(0.42f).fillMaxHeight().widthIn(min = 340.dp),
                ) {
                    if (detailDownload == null) {
                        XdmEmptyState(
                            title = "Select a download",
                            description = "Useful status, destination, verification, and actions appear here.",
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Column(
                            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
                        ) {
                            DownloadDetails(
                                download = detailDownload,
                                capabilities = capabilities,
                                checksumResults = checksumResults,
                                verificationRecords = verificationRecords,
                                onTogglePause = onTogglePause,
                                onMigrateBackend = onMigrateBackend,
                                onRemoveHistory = onRemoveHistory,
                                onPreviewPostProcessing = onPreviewPostProcessing,
                                onRunPostProcessing = onRunPostProcessing,
                                onStartIgnoringQueuePolicy = onStartIgnoringQueuePolicy,
                            )
                        }
                    }
                }
            }
        } else {
            DownloadWorkspaceList(
                downloads = visibleDownloads,
                compact = compact,
                selectionMode = selectionMode,
                selectedIds = selectedIds,
                emptyTitle = if (query.isBlank()) copy.emptyTitle else "No matching downloads",
                emptyDescription = if (query.isBlank()) copy.emptyDescription else "Try a broader search or another filter.",
                onDownloadClick = { download ->
                    if (selectionMode) selectedIds = selectedIds.toggle(download.id) else detailDownloadId = download.id
                },
                onDownloadLongClick = { download -> selectedIds = selectedIds.toggle(download.id) },
                onPrimaryAction = { download ->
                    if (download.primaryActionUsesToggle()) onTogglePause(download) else detailDownloadId = download.id
                },
                modifier = Modifier.weight(1f).padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
    }

    XdmAdaptiveSheet(
        visible = organizeVisible,
        windowClass = windowClass,
        onDismissRequest = { organizeVisible = false },
        title = "Organize downloads",
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
        ) {
            OrganizeDownloadsContent(
                downloads = downloads,
                visibleDownloads = visibleDownloads,
                selectedDownloads = selectedDownloads,
                historyReport = historyReport,
                organizationReport = organizationReport,
                activitySummary = activitySummary,
                tags = tags,
                tagAssignments = tagAssignments,
                savedSearches = savedSearches,
                query = query,
                filter = filter,
                ordering = ordering,
                includeArchived = includeArchived,
                onOrderingChanged = { ordering = it },
                onIncludeArchivedChanged = { includeArchived = it },
                onSelectAllVisible = { selectedIds = visibleDownloads.mapTo(linkedSetOf()) { it.id } },
                onClearSelection = { selectedIds = emptySet() },
                onArchiveSelected = { archived ->
                    onArchiveDownloads(selectedDownloads, archived)
                    selectedIds = emptySet()
                },
                onBulkPause = { onBulkPause(selectedDownloads) },
                onBulkResume = { onBulkResume(selectedDownloads) },
                onCreateTag = onCreateTag,
                onAssignTag = { tag -> selectedDownloads.forEach { onAssignTag(it, tag) } },
                onSaveSearch = onSaveSearch,
                onDeleteSavedSearch = onDeleteSavedSearch,
                onCopyHistory = { copyTextToClipboard(context, "XDM history index", HistoryManagementPolicy.exportIndex(downloads)) },
                onClearFinishedHistory = onClearFinishedHistory,
                onOpenActivityAttention = onOpenActivityAttention,
                onOpenActivityDecisions = onOpenActivityDecisions,
            )
        }
    }

    XdmAdaptiveSheet(
        visible = windowClass != XdmWindowClass.Expanded && detailDownload != null,
        windowClass = windowClass,
        onDismissRequest = { detailDownloadId = null },
        title = "Download details",
    ) {
        detailDownload?.let { download ->
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            ) {
                DownloadDetails(
                    download = download,
                    capabilities = capabilities,
                    checksumResults = checksumResults,
                    verificationRecords = verificationRecords,
                    onTogglePause = onTogglePause,
                    onMigrateBackend = onMigrateBackend,
                    onRemoveHistory = onRemoveHistory,
                    onPreviewPostProcessing = onPreviewPostProcessing,
                    onRunPostProcessing = onRunPostProcessing,
                    onStartIgnoringQueuePolicy = onStartIgnoringQueuePolicy,
                )
            }
        }
    }
}

@Composable
private fun DownloadsOverviewHeader(
    windowClass: XdmWindowClass,
    activeCount: Int,
    aggregateSpeed: Long,
    remainingSeconds: Long?,
    queuedCount: Int,
    searchVisible: Boolean,
    onToggleSearch: () -> Unit,
    onOpenOrganize: () -> Unit,
) {
    val metrics = listOf(
        XdmMetric("active", activeCount.toString()),
        XdmMetric("total speed", if (aggregateSpeed > 0L) aggregateSpeed.formatSpeed() else "Idle"),
        if (remainingSeconds != null) XdmMetric("remaining", formatRemainingTime(remainingSeconds))
        else XdmMetric("queued", queuedCount.toString()),
    )
    Column(Modifier.fillMaxWidth()) {
        if (windowClass == XdmWindowClass.Expanded) {
            XdmPageHeader(
                title = "Downloads",
                subtitle = "Everything in motion, without the engine-room noise.",
                actions = {
                    IconButton(
                        onClick = onToggleSearch,
                        modifier = Modifier.semantics { contentDescription = if (searchVisible) "Hide download search" else "Search downloads" },
                    ) { Icon(Icons.Rounded.Search, contentDescription = null) }
                    IconButton(
                        onClick = onOpenOrganize,
                        modifier = Modifier.semantics { contentDescription = "Organize downloads" },
                    ) { Icon(Icons.Rounded.MoreVert, contentDescription = null) }
                },
            )
        } else {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Everything in motion, without the engine-room noise.",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(
                    onClick = onToggleSearch,
                    modifier = Modifier.semantics { contentDescription = if (searchVisible) "Hide download search" else "Search downloads" },
                ) { Icon(Icons.Rounded.Search, contentDescription = null) }
                IconButton(
                    onClick = onOpenOrganize,
                    modifier = Modifier.semantics { contentDescription = "Organize downloads" },
                ) { Icon(Icons.Rounded.MoreVert, contentDescription = null) }
            }
        }
        XdmMetricStrip(metrics, Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
    }
}

@Composable
private fun DownloadSectionHeader(
    title: String,
    subtitle: String,
    activeFilter: Boolean,
    activeCount: Int,
    pausedCount: Int,
    selectionCount: Int,
    onPauseAll: () -> Unit,
    onResumeAll: () -> Unit,
    onClearSelection: () -> Unit,
    onOpenOrganize: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (selectionCount > 0) {
            TextButton(onClick = onOpenOrganize) { Text("$selectionCount selected") }
            IconButton(onClick = onClearSelection) { Icon(Icons.Rounded.Close, contentDescription = "Clear selection") }
        } else if (activeFilter && activeCount > 0) {
            TextButton(onClick = onPauseAll) { Text("Pause all") }
        } else if (activeFilter && pausedCount > 0) {
            TextButton(onClick = onResumeAll) { Text("Resume all") }
        }
    }
}

@Composable
private fun DownloadWorkspaceList(
    downloads: List<Download>,
    compact: Boolean,
    selectionMode: Boolean,
    selectedIds: Set<String>,
    emptyTitle: String,
    emptyDescription: String,
    onDownloadClick: (Download) -> Unit,
    onDownloadLongClick: (Download) -> Unit,
    onPrimaryAction: (Download) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (downloads.isEmpty()) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            XdmEmptyState(title = emptyTitle, description = emptyDescription)
        }
        return
    }
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp),
    ) {
        items(downloads, key = Download::id) { download ->
            DownloadRow(
                download = download,
                compact = compact,
                selected = download.id in selectedIds,
                selectionMode = selectionMode,
                onClick = { onDownloadClick(download) },
                onLongClick = { onDownloadLongClick(download) },
                onPrimaryAction = { onPrimaryAction(download) },
            )
        }
    }
}

private fun Set<String>.toggle(id: String): Set<String> = if (id in this) this - id else this + id
