package com.mikeyphw.xdm.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import com.mikeyphw.xdm.android.model.DownloadState

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
                AddDownloadScreen(
                    destinationUri = state.destinationUri,
                    conflictPolicy = state.conflictPolicy,
                    savedDestinations = state.destinationPermissions,
                    externalDraftId = state.externalAddDraft?.id,
                    initialUrl = state.externalAddDraft?.url,
                    initialFileName = state.externalAddDraft?.fileName,
                    externalSourceLabel = state.externalAddDraft?.sourceLabel,
                    externalKind = state.externalAddDraft?.kind,
                    externalPageTitle = state.externalAddDraft?.pageTitle,
                    externalPageUrl = state.externalAddDraft?.pageUrl,
                    externalMimeType = state.externalAddDraft?.mimeType,
                    externalContentLength = state.externalAddDraft?.contentLength,
                    externalCanInspectMedia = state.externalAddDraft?.canInspectAsMedia == true,
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
                state.downloads,
                state.compactDensity,
                state.activeTransfers,
                state.queueIntelligence,
                state.activitySummary,
                state.backendCapabilities,
                state.checksumResults,
                state.verificationRecords,
                state.historyReport,
                state.organizationReport,
                state.tags,
                state.tagAssignments,
                state.savedSearches,
                viewModel::togglePause,
                viewModel::migrateBackend,
                viewModel::removeDownloadFromHistory,
                viewModel::clearFinishedHistory,
                viewModel::archiveDownloads,
                viewModel::bulkPause,
                viewModel::bulkResume,
                viewModel::createTag,
                viewModel::assignTag,
                viewModel::saveSearch,
                viewModel::deleteSavedSearch,
                viewModel::pauseAll,
                viewModel::resumeAll,
                viewModel::previewPostProcessingForDownload,
                viewModel::runPostProcessingForDownload,
                viewModel::runQueueIntelligenceNow,
                viewModel::startIgnoringQueuePolicy,
                { viewModel.navigateActivity(ActivityPanel.Attention) },
                { viewModel.navigateActivity(ActivityPanel.Decisions) },
            )
            AppRoute.Add -> Unit
            AppRoute.Media -> MediaInboxScreen(
                captures = state.mediaCaptures,
                variants = state.mediaVariants,
                mediaTrackSelections = state.mediaTrackSelections,
                downloads = state.downloads,
                onPastePageUrl = { viewModel.navigate(AppRoute.Add) },
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
            AppRoute.Settings -> SettingsScreen(
                state.compactDensity,
                state.backendCapabilities,
                state.backendMigrations,
                state.installUpdateReadinessReport,
                state.finalReleaseGateReport,
                state.proxySettings,
                state.postProcessingSettings,
                state.settingsExportText,
                state.backupRestoreReport,
                state.destinationRules,
                state.duplicateRules,
                state.protocolExpansionReport,
                state.releasePackagingReport,
                state.termuxBridge,
                state.termuxAria2,
                state.postProcessingAutomation,
                viewModel::setCompactDensity,
                viewModel::setProxySettings,
                viewModel::setPostProcessingSettings,
                viewModel::importSettingsSnapshot,
                viewModel::saveDestinationRule,
                viewModel::saveDuplicateRule,
                viewModel::runTermuxToolProbe,
                viewModel::openTermux,
                viewModel::setTermuxRootMode,
                viewModel::runTermuxRootProbe,
                viewModel::collectTermuxRootProcessDiagnostics,
                viewModel::killStuckTermuxAria2WithRoot,
                viewModel::fixTermuxDownloadPermissionsWithRoot,
                viewModel::setTermuxAria2Enabled,
                viewModel::rotateTermuxAria2Secret,
                viewModel::setPostProcessingAutomationEnabled,
                viewModel::retryFailedPostProcessing,
                viewModel::clearPostProcessingEvents,
            )
        }
    }
}

@Composable
private fun ActivityHub(state: MainUiState, viewModel: MainViewModel) {
    val panel = state.activityPanel
    val onActivityAction: (com.mikeyphw.xdm.android.model.OperationalActivityEvent) -> Unit = { event ->
        val download = event.downloadId?.let { id -> state.downloads.firstOrNull { it.id == id } }
        when (event.actionLabel) {
            "Start anyway" -> download?.let(viewModel::startIgnoringQueuePolicy)
            "Retry now", "Review transfer", "Verify or redownload" -> download?.let(viewModel::togglePause)
            "Open recovery", "Validate", "Verify and repair", "Resume", "Restart", "Adopt file", "Locate file", "Remove record" -> viewModel.selectActivityPanel(ActivityPanel.Recovery)
            "Review request context", "Review intake", "Change destination", "Repair permission" -> viewModel.navigate(AppRoute.Add)
            "Open resolver diagnostics" -> viewModel.navigate(AppRoute.Media)
        }
    }
    Column(Modifier.fillMaxSize()) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
        ) {
            items(ActivityPanel.entries) { item ->
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
                ActivityPanel.Overview -> ActivityOverviewScreen(
                    state = state,
                    onEvaluateQueueIntelligence = viewModel::runQueueIntelligenceNow,
                    onOpenTimeline = { viewModel.selectActivityPanel(ActivityPanel.Timeline) },
                    onOpenAttention = { viewModel.selectActivityPanel(ActivityPanel.Attention) },
                    onOpenDecisions = { viewModel.selectActivityPanel(ActivityPanel.Decisions) },
                )
                ActivityPanel.Timeline -> ActivityTimelineScreen(
                    events = state.activityEvents,
                    onAction = onActivityAction,
                    onDismiss = viewModel::dismissActivityEvent,
                )
                ActivityPanel.Attention -> ActivityAttentionScreen(
                    events = state.activityEvents,
                    onAction = onActivityAction,
                    onDismiss = viewModel::dismissActivityEvent,
                )
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
                    state.recovery,
                    viewModel::validateRecoveryRecord,
                    viewModel::removeRecoveryRecord,
                )
                ActivityPanel.Diagnostics -> Column(Modifier.fillMaxSize()) {
                    OperationalDiagnosticsHeader(
                        summary = state.activitySummary,
                        diagnosticsExport = state.activityDiagnosticsExport,
                        onOpenTimeline = { viewModel.selectActivityPanel(ActivityPanel.Timeline) },
                        onClearHistory = viewModel::clearActivityHistory,
                    )
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        DiagnosticsScreen(
                            state,
                            state.browserIntegrationStatus,
                            state.clipboardInbox,
                            viewModel::runAria2SmokeTest,
                            viewModel::runTermuxToolProbe,
                            viewModel::runTermuxRootProbe,
                            viewModel::collectTermuxRootProcessDiagnostics,
                            viewModel::killStuckTermuxAria2WithRoot,
                            viewModel::startTermuxAria2Daemon,
                            viewModel::stopTermuxAria2Daemon,
                            viewModel::probeTermuxAria2Daemon,
                            viewModel::refreshTermuxAria2Tasks,
                            viewModel::pauseAllTermuxAria2Tasks,
                            viewModel::resumeAllTermuxAria2Tasks,
                            viewModel::saveTermuxAria2Session,
                            viewModel::retryFailedPostProcessing,
                            viewModel::clearPostProcessingEvents,
                            viewModel::scanClipboardText,
                            viewModel::acceptClipboardItem,
                            viewModel::dismissClipboardItem,
                        )
                    }
                }
            }
        }
    }
}

