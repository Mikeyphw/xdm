package com.mikeyphw.xdm.android

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mikeyphw.xdm.android.model.BackendCapabilityRow
import com.mikeyphw.xdm.android.model.ChecksumResult
import com.mikeyphw.xdm.android.model.Download
import com.mikeyphw.xdm.android.model.DownloadAction
import com.mikeyphw.xdm.android.model.DownloadActionKind
import com.mikeyphw.xdm.android.model.DownloadActionPlanner
import com.mikeyphw.xdm.android.model.DownloadDashboardOrdering
import com.mikeyphw.xdm.android.model.DownloadActionContext
import com.mikeyphw.xdm.android.model.DownloadUiTruthPlanner
import com.mikeyphw.xdm.android.model.CompletedArtifactCapabilities
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
import com.mikeyphw.xdm.android.scheduler.CompletedFileGrantPolicy
import com.mikeyphw.xdm.android.scheduler.MediaRequestHandoffStore
import com.mikeyphw.xdm.android.termux.PostProcessingAutomationStatus
import com.mikeyphw.xdm.android.termux.TermuxBridgeStatus
import com.mikeyphw.xdm.android.util.formatSpeed
import androidx.core.net.toUri

@Composable
@UiSurface(UiAudience.User, "Manage downloads and transfer state")
fun DownloadsScreen(
    downloads: List<Download>,
    requestedDetailDownloadId: String? = null,
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
    postProcessingAutomation: PostProcessingAutomationStatus,
    termuxBridge: TermuxBridgeStatus,
    onInspectArtifact: suspend (Download) -> CompletedArtifactCapabilities,
    onInspectResumeCapability: suspend (Download) -> Boolean,
    onTogglePause: (Download) -> Unit,
    onCancelDownload: (Download) -> Unit,
    onRedownload: (Download) -> Unit,
    onMoveDownloadInQueue: (Download, DownloadActionKind) -> Unit,
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
    onStartNow: (Download) -> Unit,
    onRenameCompleted: (Download, String, (String) -> Unit) -> Unit,
    onRefreshLink: (Download, String, (String) -> Unit) -> Unit,
    onDeleteEntry: (Download, (String) -> Unit) -> Unit,
    onDeleteSavedFile: (Download, Boolean, (String) -> Unit) -> Unit,
    onRestartFromZero: (Download, (String) -> Unit) -> Unit,
    onOpenRecovery: (Download, DownloadActionKind) -> Unit,
    onDetailSelectionChanged: (String?) -> Unit,
    onOpenActivityAttention: () -> Unit,
    onOpenActivityDecisions: () -> Unit,
) {
    val context = LocalContext.current
    val windowClass = LocalXdmWindowClass.current
    val windowProfile = LocalXdmWindowProfile.current
    var filter by rememberSaveable { mutableStateOf(DownloadWorkspaceFilter.All) }
    var query by rememberSaveable { mutableStateOf("") }
    var searchVisible by rememberSaveable { mutableStateOf(false) }
    var ordering by rememberSaveable { mutableStateOf(DownloadDashboardOrdering.Smart) }
    var includeArchived by rememberSaveable { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    var detailDownloadId by rememberSaveable { mutableStateOf<String?>(requestedDetailDownloadId) }
    var organizeVisible by rememberSaveable { mutableStateOf(false) }
    var actionDownloadId by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmationDownloadId by remember { mutableStateOf<String?>(null) }
    var confirmationAction by remember { mutableStateOf<DownloadAction?>(null) }
    var textActionDownloadId by remember { mutableStateOf<String?>(null) }
    var textAction by remember { mutableStateOf<DownloadAction?>(null) }
    var textActionValue by remember { mutableStateOf("") }
    var artifactCapabilities by remember { mutableStateOf<Map<String, CompletedArtifactCapabilities>>(emptyMap()) }
    var durableResumeCapabilities by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var twoPaneLayoutActive by remember { mutableStateOf(false) }

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
    val actionDownload = downloads.firstOrNull { it.id == actionDownloadId }
    val confirmationDownload = downloads.firstOrNull { it.id == confirmationDownloadId }
    val heldDownload = DownloadsWorkspacePlanner.firstPolicyHeldDownload(downloads)
    val copy = DownloadsWorkspacePlanner.copyFor(filter)
    val selectionMode = selectedIds.isNotEmpty()
    fun actionContext(download: Download): DownloadActionContext = DownloadUiTruthPlanner.contextFor(
        download = download,
        downloads = downloads,
        verificationRecords = verificationRecords,
        checksumResults = checksumResults,
        artifact = artifactCapabilities[download.id] ?: CompletedArtifactCapabilities(
            friendlyLocation = destinationUiLabel(download.destinationUri),
        ),
        backendMigrationAvailable = backendMigrationAvailable(download, capabilities),
        postProcessingInputAvailable = artifactCapabilities[download.id]?.readable == true,
        validatedPartialAvailable = durableResumeCapabilities[download.id] == true,
        exactRequestReplayAvailable = MediaRequestHandoffStore.forDownload(download.id)?.exactUrl != null,
    )
    val executeDownloadAction: (Download, DownloadAction) -> Unit = { download, action ->
        performDownloadAction(
            context = context,
            download = download,
            action = action,
            actionContext = actionContext(download),
            onTogglePause = onTogglePause,
            onCancelDownload = onCancelDownload,
            onRedownload = onRedownload,
            onMoveDownloadInQueue = onMoveDownloadInQueue,
            onDeleteRecord = { item -> onDeleteEntry(item) { showActionToast(context, it) } },
            onDeleteSavedFile = { item, removeEntry -> onDeleteSavedFile(item, removeEntry) { showActionToast(context, it) } },
            onStartNow = onStartNow,
            onRename = { item ->
                textActionDownloadId = item.id
                textAction = action
                textActionValue = item.fileName
            },
            onRefreshLink = { item ->
                textActionDownloadId = item.id
                textAction = action
                textActionValue = MediaRequestHandoffStore.forDownload(item.id)?.exactUrl ?: item.sourceUrl
            },
            onRestartFromZero = { item -> onRestartFromZero(item) { showActionToast(context, it) } },
            onOpenRecovery = onOpenRecovery,
            onOpenDetails = { detailDownloadId = download.id },
        )
    }
    val runDownloadAction: (Download, DownloadAction) -> Unit = { download, action ->
        if (action.enabled) {
            if (action.kind in setOf(DownloadActionKind.Rename, DownloadActionKind.RefreshLink)) {
                executeDownloadAction(download, action)
            } else if (action.requiresConfirmation) {
                confirmationDownloadId = download.id
                confirmationAction = action
            } else {
                executeDownloadAction(download, action)
            }
        }
    }

    LaunchedEffect(requestedDetailDownloadId, downloads) {
        requestedDetailDownloadId
            ?.takeIf { requested -> downloads.any { it.id == requested } }
            ?.let { detailDownloadId = it }
    }

    LaunchedEffect(detailDownloadId) {
        onDetailSelectionChanged(detailDownloadId)
    }

    LaunchedEffect(downloads) {
        val completed = downloads.filter { it.state == DownloadState.Completed }
        artifactCapabilities = completed.associate { it.id to onInspectArtifact(it) }
        val resumable = downloads.filter { it.state in setOf(
            DownloadState.Paused,
            DownloadState.WaitingForNetwork,
            DownloadState.WaitingForPower,
            DownloadState.Failed,
            DownloadState.RecoveryRequired,
        ) }
        durableResumeCapabilities = resumable.associate { it.id to onInspectResumeCapability(it) }
    }

    LaunchedEffect(downloads, visibleDownloads, detailDownloadId, twoPaneLayoutActive) {
        val visibleIds = visibleDownloads.mapTo(mutableSetOf()) { it.id }
        selectedIds = selectedIds.intersect(visibleIds)
        if (detailDownloadId != null && detailDownloadId !in visibleIds) {
            detailDownloadId = if (twoPaneLayoutActive) visibleDownloads.firstOrNull()?.id else null
        } else if (twoPaneLayoutActive && detailDownloadId == null) {
            detailDownloadId = visibleDownloads.firstOrNull()?.id
        }
    }

    Column(Modifier.fillMaxSize().xdmScreen(XdmScreenTags.Downloads, "Downloads")) {
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
                if (twoPaneLayoutActive) detailDownloadId = null
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

        var downloadsPaneLeftInWindow by remember { mutableStateOf<Dp?>(null) }
        val paneDensity = LocalDensity.current
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .onGloballyPositioned { coordinates ->
                    downloadsPaneLeftInWindow = with(paneDensity) { coordinates.positionInWindow().x.toDp() }
                },
        ) {
            val measuredWindowProfile = windowProfile.withAvailablePaneWidth(maxWidth)
            val contentLeftInWindow = downloadsPaneLeftInWindow?.plus(20.dp)
            val contentWidth = (maxWidth - 40.dp).coerceAtLeast(0.dp)
            val hingeSplit = contentLeftInWindow?.let { measuredWindowProfile.verticalHingeSplitFor(it, contentWidth) }
            val measuredTwoPaneDownloads = when {
                measuredWindowProfile.hasVerticalSeparatingFold -> hingeSplit != null
                else -> measuredWindowProfile.allowsTwoPaneDownloadsFor(maxWidth)
            }
            LaunchedEffect(measuredTwoPaneDownloads) {
                twoPaneLayoutActive = measuredTwoPaneDownloads
            }
            if (measuredTwoPaneDownloads) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    horizontalArrangement = if (hingeSplit == null) Arrangement.spacedBy(measuredWindowProfile.minimumPaneGap) else Arrangement.Start,
                ) {
                    DownloadWorkspaceList(
                        downloads = visibleDownloads,
                        actionContextFor = ::actionContext,
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
                            val action = DownloadActionPlanner.primaryActionFor(download, actionContext(download))
                            runDownloadAction(download, action)
                        },
                        onMoreActions = { download -> actionDownloadId = download.id },
                        modifier = Modifier
                            .then(
                                if (hingeSplit != null) Modifier.width(hingeSplit.leftPaneWidth)
                                else Modifier.weight(0.58f),
                            )
                            .fillMaxHeight()
                            .widthIn(min = measuredWindowProfile.downloadsListMinWidth)
                            .xdmTraversalOrder(XdmTraversalOrder.List),
                    )
                    if (hingeSplit != null) Spacer(Modifier.width(hingeSplit.hingeGap))
                    Box(
                        Modifier
                            .then(
                                if (hingeSplit != null) Modifier.width(hingeSplit.rightPaneWidth)
                                else Modifier.weight(0.42f),
                            )
                            .fillMaxHeight()
                            .widthIn(min = measuredWindowProfile.downloadsDetailMinWidth)
                            .xdmPane("Download details pane", traversal = XdmTraversalOrder.Detail),
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
                                    actionContext = actionContext(detailDownload),
                                    capabilities = capabilities,
                                    checksumResults = checksumResults,
                                    verificationRecords = verificationRecords,
                                    postProcessingAutomation = postProcessingAutomation,
                                    termuxBridge = termuxBridge,
                                    onTogglePause = onTogglePause,
                                    onMigrateBackend = onMigrateBackend,
                                    onRemoveHistory = onRemoveHistory,
                                    onPreviewPostProcessing = onPreviewPostProcessing,
                                    onRunPostProcessing = onRunPostProcessing,
                                    onStartIgnoringQueuePolicy = onStartIgnoringQueuePolicy,
                                    onOpenActivityAttention = onOpenActivityAttention,
                                    onDownloadAction = { action -> runDownloadAction(detailDownload, action) },
                                )
                            }
                        }
                    }
                }
            } else {
                DownloadWorkspaceList(
                    downloads = visibleDownloads,
                    actionContextFor = ::actionContext,
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
                        val action = DownloadActionPlanner.primaryActionFor(download, actionContext(download))
                        runDownloadAction(download, action)
                    },
                    onMoreActions = { download -> actionDownloadId = download.id },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .xdmTraversalOrder(XdmTraversalOrder.List),
                )
            }
        }
    }

    actionDownload?.let { download ->
        XdmAdaptiveSheet(
            visible = true,
            windowClass = windowClass,
            onDismissRequest = { actionDownloadId = null },
            title = "Actions for ${download.fileName}",
            scrollContent = false,
        ) {
            DownloadActionsContent(
                download = download,
                actionContext = actionContext(download),
                onAction = { action ->
                    actionDownloadId = null
                    runDownloadAction(download, action)
                },
            )
        }
    }

    val confirmationSheetDownload = confirmationDownload
    val confirmationSheetAction = confirmationAction
    if (confirmationSheetDownload != null && confirmationSheetAction != null) {
        XdmAdaptiveSheet(
            visible = true,
            windowClass = windowClass,
            onDismissRequest = {
                confirmationDownloadId = null
                confirmationAction = null
            },
            title = confirmationSheetAction.label,
        ) {
            Column(
                Modifier.fillMaxWidth().padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    confirmationSheetAction.supportingText.ifBlank { "Confirm this action for ${confirmationSheetDownload.fileName}." },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = {
                        confirmationDownloadId = null
                        confirmationAction = null
                    }) { Text("Cancel") }
                    Button(onClick = {
                        val confirmedDownload = confirmationSheetDownload
                        val requestedKind = confirmationSheetAction.kind
                        confirmationDownloadId = null
                        confirmationAction = null
                        val freshAction = DownloadActionPlanner.actionsFor(
                            confirmedDownload,
                            actionContext(confirmedDownload),
                        ).firstOrNull { it.kind == requestedKind && it.enabled && it.requiresConfirmation }
                        if (freshAction == null) {
                            showActionToast(context, "That action is no longer available because the download changed.")
                        } else {
                            executeDownloadAction(confirmedDownload, freshAction)
                        }
                    }) { Text("Confirm") }
                }
            }
        }
    }

    val textSheetDownload = downloads.firstOrNull { it.id == textActionDownloadId }
    val textSheetAction = textAction
    if (textSheetDownload != null && textSheetAction != null) {
        XdmAdaptiveSheet(
            visible = true,
            windowClass = windowClass,
            onDismissRequest = { textActionDownloadId = null; textAction = null },
            title = textSheetAction.label,
            scrollContent = false,
        ) {
            Column(Modifier.fillMaxWidth().padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlinedTextField(
                    value = textActionValue,
                    onValueChange = { textActionValue = it },
                    label = { Text(if (textSheetAction.kind == DownloadActionKind.Rename) "New file name" else "Fresh source URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(textSheetAction.supportingText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                    TextButton(onClick = { textActionDownloadId = null; textAction = null }) { Text("Cancel") }
                    Button(onClick = {
                        val item = textSheetDownload
                        val value = textActionValue
                        val kind = textSheetAction.kind
                        textActionDownloadId = null
                        textAction = null
                        if (kind == DownloadActionKind.Rename) {
                            onRenameCompleted(item, value) { showActionToast(context, it) }
                        } else {
                            onRefreshLink(item, value) { showActionToast(context, it) }
                        }
                    }) { Text("Apply") }
                }
            }
        }
    }

    XdmAdaptiveSheet(
        visible = organizeVisible,
        windowClass = windowClass,
        onDismissRequest = { organizeVisible = false },
        title = "Organize downloads",
        scrollContent = false,
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
                onApplySavedSearch = { search ->
                    query = search.query
                    filter = search.state.toWorkspaceFilter()
                    includeArchived = search.includeArchived
                    searchVisible = search.query.isNotBlank()
                    selectedIds = emptySet()
                    detailDownloadId = null
                    organizeVisible = false
                },
                onDeleteSavedSearch = onDeleteSavedSearch,
                onCopyHistory = { copyTextToClipboard(context, "XDM history index", HistoryManagementPolicy.exportIndex(downloads)) },
                onClearFinishedHistory = onClearFinishedHistory,
                onOpenActivityAttention = onOpenActivityAttention,
                onOpenActivityDecisions = onOpenActivityDecisions,
            )
        }
    }

    XdmAdaptiveSheet(
        visible = !twoPaneLayoutActive && detailDownload != null,
        windowClass = windowClass,
        onDismissRequest = { detailDownloadId = null },
        title = "Download details",
        scrollContent = false,
    ) {
        detailDownload?.let { download ->
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            ) {
                DownloadDetails(
                    download = download,
                    actionContext = actionContext(download),
                    capabilities = capabilities,
                    checksumResults = checksumResults,
                    verificationRecords = verificationRecords,
                    postProcessingAutomation = postProcessingAutomation,
                    termuxBridge = termuxBridge,
                    onTogglePause = onTogglePause,
                    onMigrateBackend = onMigrateBackend,
                    onRemoveHistory = onRemoveHistory,
                    onPreviewPostProcessing = onPreviewPostProcessing,
                    onRunPostProcessing = onRunPostProcessing,
                    onStartIgnoringQueuePolicy = onStartIgnoringQueuePolicy,
                    onOpenActivityAttention = onOpenActivityAttention,
                    onDownloadAction = { action -> runDownloadAction(download, action) },
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
    actionContextFor: (Download) -> DownloadActionContext,
    compact: Boolean,
    selectionMode: Boolean,
    selectedIds: Set<String>,
    emptyTitle: String,
    emptyDescription: String,
    onDownloadClick: (Download) -> Unit,
    onDownloadLongClick: (Download) -> Unit,
    onPrimaryAction: (Download) -> Unit,
    onMoreActions: (Download) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (downloads.isEmpty()) {
        Box(
            modifier.fillMaxWidth().xdmScreen(XdmScreenTags.DownloadsList, "Downloads list"),
            contentAlignment = Alignment.TopCenter,
        ) {
            XdmEmptyState(title = emptyTitle, description = emptyDescription)
        }
        return
    }
    LazyColumn(
        modifier = modifier.xdmScreen(XdmScreenTags.DownloadsList, "Downloads list").semantics { isTraversalGroup = true },
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp),
    ) {
        items(downloads, key = Download::id) { download ->
            DownloadRow(
                download = download,
                actionContext = actionContextFor(download),
                compact = compact,
                selected = download.id in selectedIds,
                selectionMode = selectionMode,
                onClick = { onDownloadClick(download) },
                onLongClick = { onDownloadLongClick(download) },
                onPrimaryAction = { onPrimaryAction(download) },
                onMoreActions = { onMoreActions(download) },
            )
        }
    }
}


@Composable
private fun DownloadActionsContent(
    download: Download,
    actionContext: DownloadActionContext,
    onAction: (DownloadAction) -> Unit,
) {
    val actions = DownloadActionPlanner.actionsFor(download, actionContext)
    Column(Modifier.fillMaxWidth().padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        XdmMetadataText("Tap the row for details. Use the primary row button for ${DownloadActionPlanner.primaryActionFor(download, actionContext).label.lowercase()}.")
        XdmGroupedList {
            actions.forEachIndexed { index, action ->
                if (index > 0) XdmListSeparator()
                XdmListRow(
                    headline = action.label,
                    supporting = action.menuSupportingText(),
                    enabled = action.enabled,
                    leading = { Icon(action.iconVector(), contentDescription = null) },
                    trailing = if (action.primary) ({ XdmStatusBadge("Primary", tone = XdmStatusTone.Info) }) else null,
                    onClick = if (action.enabled) ({ onAction(action) }) else null,
                )
            }
        }
    }
}

private fun DownloadAction.menuSupportingText(): String = buildList {
    if (supportingText.isNotBlank()) add(supportingText)
    if (requiresConfirmation) add("Requires confirmation")
    if (destructive) add("Destructive")
    if (!enabled) add("Unavailable for this item")
}.joinToString(" • ")

private fun performDownloadAction(
    context: android.content.Context,
    download: Download,
    action: DownloadAction,
    actionContext: DownloadActionContext,
    onTogglePause: (Download) -> Unit,
    onCancelDownload: (Download) -> Unit,
    onRedownload: (Download) -> Unit,
    onMoveDownloadInQueue: (Download, DownloadActionKind) -> Unit,
    onDeleteRecord: (Download) -> Unit,
    onDeleteSavedFile: (Download, Boolean) -> Unit,
    onStartNow: (Download) -> Unit,
    onRename: (Download) -> Unit,
    onRefreshLink: (Download) -> Unit,
    onRestartFromZero: (Download) -> Unit,
    onOpenRecovery: (Download, DownloadActionKind) -> Unit,
    onOpenDetails: () -> Unit,
) {
    when (action.kind) {
        DownloadActionKind.Pause,
        DownloadActionKind.Resume,
        DownloadActionKind.Retry,
        -> onTogglePause(download)
        DownloadActionKind.StartNow -> onStartNow(download)
        DownloadActionKind.CopyLink -> actionContext.publicSourceUrl?.let { copySensitiveTextToClipboard(context, "XDM redacted source URL", it) }
        DownloadActionKind.CopyFileName -> copyTextToClipboard(context, "XDM file name", download.fileName)
        DownloadActionKind.CopyDestination -> actionContext.artifact.androidUri?.let { copySensitiveTextToClipboard(context, "XDM Android URI", it) }
        DownloadActionKind.CopyFriendlyLocation -> copyTextToClipboard(context, "XDM saved location", actionContext.artifact.friendlyLocation)
        DownloadActionKind.ShareLink -> actionContext.publicSourceUrl?.let { shareText(context, "XDM redacted source URL", it) }
        DownloadActionKind.OpenFile -> openCompletedFile(context, download)
        DownloadActionKind.OpenDetails -> onOpenDetails()
        DownloadActionKind.ReviewRecovery, DownloadActionKind.LocateFile -> onOpenRecovery(download, action.kind)
        DownloadActionKind.Cancel -> onCancelDownload(download)
        DownloadActionKind.MoveToTop,
        DownloadActionKind.MoveUp,
        DownloadActionKind.MoveDown,
        DownloadActionKind.MoveToBottom,
        -> onMoveDownloadInQueue(download, action.kind)
        DownloadActionKind.ShareFile -> shareCompletedFile(context, download)
        DownloadActionKind.OpenFolder -> openCompletedLocation(context, download, actionContext)
        DownloadActionKind.Rename -> onRename(download)
        DownloadActionKind.Redownload -> onRedownload(download)
        DownloadActionKind.RefreshLink -> onRefreshLink(download)
        DownloadActionKind.RestartFromZero -> onRestartFromZero(download)
        DownloadActionKind.DeleteFile -> onDeleteSavedFile(download, false)
        DownloadActionKind.DeleteRecord -> onDeleteRecord(download)
        DownloadActionKind.DeleteFileAndRecord -> onDeleteSavedFile(download, true)
    }
}

private fun openCompletedFile(context: android.content.Context, download: Download) {
    val uri = completedDownloadUri(context, download)
    if (uri == null) {
        showActionToast(context, "Android has not exposed a readable saved-file link for this item yet.")
        return
    }
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, download.mimeType?.takeIf { it.isNotBlank() } ?: "*/*")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(Intent.createChooser(intent, "Open ${download.fileName}").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    } catch (_: ActivityNotFoundException) {
        showActionToast(context, "No app can open this file type yet.")
    } catch (_: SecurityException) {
        showActionToast(context, "Android blocked access to the saved file.")
    } catch (_: IllegalArgumentException) {
        showActionToast(context, "Saved-file link is not valid yet.")
    }
}

private fun shareCompletedFile(context: android.content.Context, download: Download) {
    val uri = completedDownloadUri(context, download)
    if (uri == null) {
        showActionToast(context, "Android has not exposed a shareable saved-file link for this item yet.")
        return
    }
    val intent = Intent(Intent.ACTION_SEND)
        .setType(download.mimeType?.takeIf { it.isNotBlank() } ?: "application/octet-stream")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(Intent.createChooser(intent, "Share ${download.fileName}").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    } catch (_: ActivityNotFoundException) {
        showActionToast(context, "No app can share this file type yet.")
    } catch (_: SecurityException) {
        showActionToast(context, "Android blocked access to the saved file.")
    }
}

private fun openCompletedLocation(
    context: android.content.Context,
    download: Download,
    actionContext: DownloadActionContext,
) {
    val raw = actionContext.artifact.androidUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
    if (raw == null || !actionContext.artifact.locationBrowsable) {
        showActionToast(context, "This provider does not expose a containing-folder action. Use Open file instead.")
        return
    }
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, raw).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    } catch (_: Exception) {
        showActionToast(context, "No installed app can open this provider location.")
    }
}

private fun completedDownloadUri(context: android.content.Context, download: Download): Uri? =
    CompletedFileGrantPolicy.resolve(context, download)

private fun showActionToast(context: android.content.Context, message: String) {
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}

private fun shareText(context: android.content.Context, title: String, value: String) {
    val intent = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, value)
    context.startActivity(Intent.createChooser(intent, title))
}

private fun Set<String>.toggle(id: String): Set<String> = if (id in this) this - id else this + id

private fun backendMigrationAvailable(download: Download, capabilities: List<BackendCapabilityRow>): Boolean {
    if (download.state in setOf(DownloadState.Completed, DownloadState.Verifying, DownloadState.Repairing, DownloadState.Finalizing)) return false
    val target = when (download.backend) {
        com.mikeyphw.xdm.android.model.BackendType.Native -> com.mikeyphw.xdm.android.model.BackendType.Aria2
        com.mikeyphw.xdm.android.model.BackendType.Aria2 -> com.mikeyphw.xdm.android.model.BackendType.Native
        com.mikeyphw.xdm.android.model.BackendType.Automatic -> return false
    }
    val capability = capabilities.firstOrNull { it.backend == target } ?: return false
    val scheme = runCatching { Uri.parse(download.sourceUrl).scheme.orEmpty().lowercase() }.getOrDefault("")
    val destinationScheme = runCatching { Uri.parse(download.destinationUri).scheme.orEmpty().lowercase() }.getOrDefault("")
    return capability.available && scheme in capability.protocols && (destinationScheme != "content" || capability.saf)
}

private fun DownloadState?.toWorkspaceFilter(): DownloadWorkspaceFilter = when (this) {
    DownloadState.Connecting, DownloadState.Downloading, DownloadState.Verifying, DownloadState.Repairing, DownloadState.Finalizing -> DownloadWorkspaceFilter.Active
    DownloadState.Created, DownloadState.Queued, DownloadState.WaitingForNetwork, DownloadState.WaitingForPower -> DownloadWorkspaceFilter.Queued
    DownloadState.Paused -> DownloadWorkspaceFilter.Paused
    DownloadState.Completed, DownloadState.Failed, DownloadState.Cancelled, DownloadState.RecoveryRequired -> DownloadWorkspaceFilter.Finished
    null -> DownloadWorkspaceFilter.All
}
