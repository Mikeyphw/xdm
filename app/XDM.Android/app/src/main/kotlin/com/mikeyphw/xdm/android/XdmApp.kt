package com.mikeyphw.xdm.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mikeyphw.xdm.android.model.BackendType
import com.mikeyphw.xdm.android.model.BrowserSessionHealthPlanner
import com.mikeyphw.xdm.android.model.EngineEscalationPlanner
import com.mikeyphw.xdm.android.model.DownloadState
import com.mikeyphw.xdm.android.model.OperationalActivityEvent

private val routeTopology = AppRoute.entries
private val primaryRoutes = routeTopology.filterNot { it == AppRoute.Add }

@Composable
fun XdmApp(viewModel: MainViewModel, requestNotifications: () -> Unit = {}) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var lastPrimaryRouteName by rememberSaveable { mutableStateOf(AppRoute.Downloads.name) }

    LaunchedEffect(state.route) {
        if (state.route in primaryRoutes) lastPrimaryRouteName = state.route.name
    }

    val previousPrimaryRoute = AppRoute.restore(lastPrimaryRouteName).takeIf { it in primaryRoutes } ?: AppRoute.Downloads
    val visibleRoute = if (state.route == AppRoute.Add) previousPrimaryRoute else state.route

    BackHandler(enabled = state.route == AppRoute.Add) {
        viewModel.navigate(previousPrimaryRoute)
    }
    BackHandler(enabled = state.route != AppRoute.Downloads && state.route != AppRoute.Add) {
        viewModel.navigate(AppRoute.Downloads)
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val windowClass = XdmWindowClass.fromWidth(maxWidth)
        CompositionLocalProvider(LocalXdmWindowClass provides windowClass) {
            XdmAdaptiveShell(
                windowClass = windowClass,
                selectedRoute = visibleRoute,
                destinations = primaryRoutes,
                activeTransferCount = state.activeTransfers.activeCount,
                queuedTransferCount = state.downloads.count { it.state == DownloadState.Queued },
                runtimeLabel = state.activeTransfers.bandwidthProfile,
                onNavigate = viewModel::navigate,
                onAddDownload = { viewModel.navigate(AppRoute.Add) },
            ) {
                XdmRouteContent(
                    route = visibleRoute,
                    state = state,
                    viewModel = viewModel,
                )
            }
            XdmAdaptiveSheet(
                visible = state.route == AppRoute.Add,
                windowClass = windowClass,
                onDismissRequest = { viewModel.navigate(previousPrimaryRoute) },
                title = "New download",
            ) {
                val externalSessionHealth = BrowserSessionHealthPlanner.evaluate(state.externalAddDraft)
                val externalEngineEscalation = state.externalAddDraft?.let { draft ->
                    EngineEscalationPlanner.evaluate(
                        draft = draft,
                        recommendation = viewModel.backendRecommendation(
                            draft.url,
                            draft.fileName,
                            BackendType.Automatic,
                            state.destinationUri,
                            state.conflictPolicy,
                            true,
                        ),
                        sessionHealth = externalSessionHealth,
                    )
                }
                AddDownloadScreen(
                    destinationUri = state.destinationUri,
                    conflictPolicy = state.conflictPolicy,
                    savedDestinations = state.destinationPermissions,
                    externalDraftId = state.externalAddDraft?.id,
                    initialUrl = state.externalAddDraft?.url,
                    initialFileName = state.externalAddDraft?.fileName,
                    externalSourceLabel = state.externalAddDraft?.sourceLabel,
                    externalKind = state.externalAddDraft?.kind,
                    externalOrigin = state.externalAddDraft?.origin,
                    externalPageTitle = state.externalAddDraft?.pageTitle,
                    externalPageUrl = state.externalAddDraft?.pageUrl,
                    externalMimeType = state.externalAddDraft?.mimeType,
                    externalContentLength = state.externalAddDraft?.contentLength,
                    externalCanInspectMedia = state.externalAddDraft?.canInspectAsMedia == true,
                    externalSessionHealth = externalSessionHealth,
                    externalEngineEscalationPlan = externalEngineEscalation,
                    onInspectMedia = { url, fileName ->
                        state.externalAddDraft?.let(viewModel::inspectExternalMedia)
                            ?: viewModel.inspectManualMedia(url, fileName)
                    },
                    onCancel = { viewModel.navigate(previousPrimaryRoute) },
                    onDestinationChanged = viewModel::setDestination,
                    onSafDestinationSelected = viewModel::registerSafDestination,
                    onConflictPolicyChanged = viewModel::setConflictPolicy,
                    onAdd = { url, name, backend, destination, conflictPolicy, allowFallback, expectedChecksum, checksumAlgorithm ->
                        requestNotifications()
                        viewModel.addDownload(url, name, backend, destination, conflictPolicy, allowFallback, expectedChecksum, checksumAlgorithm)
                    },
                    recommend = viewModel::backendRecommendation,
                )
            }
        }
    }
}

@Composable
private fun XdmRouteContent(
    route: AppRoute,
    state: MainUiState,
    viewModel: MainViewModel,
) {
    Box(Modifier.fillMaxSize()) {
        when (route) {
            AppRoute.Downloads -> DownloadsScreen(
                downloads = state.downloads,
                compact = state.compactDensity,
                active = state.activeTransfers,
                queueIntelligence = state.queueIntelligence,
                activitySummary = state.activitySummary,
                capabilities = state.backendCapabilities,
                checksumResults = state.checksumResults,
                verificationRecords = state.verificationRecords,
                historyReport = state.historyReport,
                organizationReport = state.organizationReport,
                tags = state.tags,
                tagAssignments = state.tagAssignments,
                savedSearches = state.savedSearches,
                postProcessingAutomation = state.postProcessingAutomation,
                termuxBridge = state.termuxBridge,
                onInspectArtifact = viewModel::inspectCompletedArtifact,
                onInspectResumeCapability = viewModel::inspectResumeCapability,
                onTogglePause = viewModel::togglePause,
                onCancelDownload = viewModel::cancelDownload,
                onRedownload = viewModel::redownload,
                onMoveDownloadInQueue = viewModel::moveDownloadInQueue,
                onMigrateBackend = viewModel::migrateBackend,
                onRemoveHistory = viewModel::removeDownloadFromHistory,
                onClearFinishedHistory = viewModel::clearFinishedHistory,
                onArchiveDownloads = viewModel::archiveDownloads,
                onBulkPause = viewModel::bulkPause,
                onBulkResume = viewModel::bulkResume,
                onCreateTag = viewModel::createTag,
                onAssignTag = viewModel::assignTag,
                onSaveSearch = viewModel::saveSearch,
                onDeleteSavedSearch = viewModel::deleteSavedSearch,
                onPauseAll = viewModel::pauseAll,
                onResumeAll = viewModel::resumeAll,
                onPreviewPostProcessing = viewModel::previewPostProcessingForDownload,
                onRunPostProcessing = viewModel::runPostProcessingForDownload,
                onEvaluateQueueIntelligence = viewModel::runQueueIntelligenceNow,
                onStartIgnoringQueuePolicy = viewModel::startIgnoringQueuePolicy,
                onStartNow = viewModel::startNow,
                onRenameCompleted = viewModel::renameCompletedFile,
                onRefreshLink = viewModel::refreshDownloadLink,
                onDeleteEntry = viewModel::deleteDownloadEntry,
                onDeleteSavedFile = viewModel::deleteSavedFile,
                onRestartFromZero = viewModel::restartFromZero,
                onOpenRecovery = viewModel::openRecoveryFor,
                onOpenActivityAttention = { viewModel.navigateActivity(ActivityPanel.Attention) },
                onOpenActivityDecisions = { viewModel.navigateActivity(ActivityPanel.Decisions) },
            )
            AppRoute.Add -> Unit
            AppRoute.Media -> MediaInboxScreen(
                captures = state.mediaCaptures,
                variants = state.mediaVariants,
                mediaTrackSelections = state.mediaTrackSelections,
                downloads = state.downloads,
                onPastePageUrl = viewModel::capturePageUrl,
                onBatchInput = viewModel::captureMediaBatchInput,
                onDownload = viewModel::downloadMediaCapture,
                onResumeOrRetryDownload = viewModel::togglePause,
                onResolve = viewModel::resolveMediaCapture,
                onSelectVariant = viewModel::selectMediaVariant,
                onTrackSelectionChanged = viewModel::updateMediaTrackSelection,
                onRemove = viewModel::removeMediaCapture,
            )
            AppRoute.Library -> MediaLibraryScreen(
                captures = state.mediaCaptures,
                variants = state.mediaVariants,
                downloads = state.downloads,
                onResumeOrRetryDownload = viewModel::togglePause,
                onRemoveRecord = viewModel::removeMediaCapture,
            )
            AppRoute.Activity -> ActivityHub(state, viewModel)
            AppRoute.Settings -> SettingsScreen(state, viewModel)
        }
    }
}

@Composable
private fun ActivityHub(state: MainUiState, viewModel: MainViewModel) {
    val panel = state.activityPanel
    var lastPrimaryPanelName by rememberSaveable { mutableStateOf(ActivityPanel.Attention.name) }

    LaunchedEffect(panel) {
        if (panel.isPrimary) lastPrimaryPanelName = panel.normalized(false).name
        if (panel == ActivityPanel.Diagnostics) viewModel.openDeveloperTools()
    }

    val lastPrimary = runCatching { ActivityPanel.valueOf(lastPrimaryPanelName) }
        .getOrDefault(ActivityPanel.Attention)
        .normalized(false)
        .takeIf { it.isPrimary }
        ?: ActivityPanel.Attention

    val onActivityAction: (OperationalActivityEvent) -> Unit = { event ->
        val download = event.downloadId?.let { id -> state.downloads.firstOrNull { it.id == id } }
        when (event.actionLabel) {
            "Start anyway" -> download?.let(viewModel::startIgnoringQueuePolicy)
            "Retry now", "Review transfer", "Verify or redownload" -> download?.let(viewModel::togglePause)
            "Open recovery", "Validate", "Verify and repair", "Resume", "Restart", "Adopt file", "Locate file", "Remove record" -> viewModel.selectActivityPanel(ActivityPanel.Recovery)
            "Review request context", "Review intake", "Change destination", "Repair permission" -> viewModel.navigate(AppRoute.Add)
            "Open resolver diagnostics" -> viewModel.navigate(AppRoute.Media)
        }
    }

    ActivityWorkspaceScreen(
        events = state.activityEvents,
        selectedPanel = if (panel.isPrimary) panel else lastPrimary,
        onPanelChanged = viewModel::selectActivityPanel,
        onOpenManage = { viewModel.selectActivityPanel(ActivityPanel.Decisions) },
        onAction = onActivityAction,
        onDismiss = viewModel::dismissActivityEvent,
    )

    XdmAdaptiveSheet(
        visible = panel.isManage,
        windowClass = LocalXdmWindowClass.current,
        onDismissRequest = { viewModel.selectActivityPanel(lastPrimary) },
        title = "Manage activity",
    ) {
        Column(Modifier.fillMaxSize()) {
            XdmActionFlowRow(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                ActivityPanel.managePanels.forEach { item ->
                    FilterChip(
                        selected = panel == item,
                        onClick = { viewModel.selectActivityPanel(item) },
                        label = { Text(item.label) },
                        modifier = Modifier.semantics {
                            stateDescription = if (panel == item) "${item.label} selected" else "${item.label} not selected"
                        },
                    )
                }
            }
            Box(Modifier.fillMaxWidth().weight(1f)) {
                when (panel) {
                    ActivityPanel.Decisions -> ActivityDecisionsScreen(
                        events = state.activityEvents,
                        onEvaluateNow = viewModel::runQueueIntelligenceNow,
                        onAction = onActivityAction,
                        onDismiss = viewModel::dismissActivityEvent,
                    )
                    ActivityPanel.Queues -> QueuesScreen(
                        queues = state.queues,
                        onCreateQueue = viewModel::createQueue,
                        onUpdateQueue = viewModel::updateQueue,
                        onToggleQueue = viewModel::setQueueEnabled,
                        onDeleteQueue = viewModel::deleteQueue,
                    )
                    ActivityPanel.Schedule -> SchedulerScreen(
                        rules = state.schedules,
                        queues = state.queues,
                        queueIntelligence = state.queueIntelligence,
                        onCreateSchedule = viewModel::createSchedule,
                        onUpdateSchedule = viewModel::updateSchedule,
                        onToggleSchedule = viewModel::setScheduleEnabled,
                        onDeleteSchedule = viewModel::deleteSchedule,
                        onEvaluateNow = viewModel::runQueueIntelligenceNow,
                    )
                    ActivityPanel.Recovery -> RecoveryScreen(
                        records = state.recovery,
                        onValidate = viewModel::validateRecoveryRecord,
                        onRemove = viewModel::removeRecoveryRecord,
                        onValidateAll = viewModel::validateAllRecoveryRecords,
                        selectedDownloadId = state.selectedRecoveryDownloadId,
                        selectedAction = state.selectedRecoveryAction,
                    )
                    else -> Unit
                }
            }
        }
    }
}
