package com.mikeyphw.xdm.android

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private val primaryRoutes = listOf(AppRoute.Downloads, AppRoute.Media, AppRoute.Queues)
private val overflowRoutes = listOf(AppRoute.Add, AppRoute.Scheduler, AppRoute.Recovery, AppRoute.Diagnostics, AppRoute.Settings)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun XdmApp(viewModel: MainViewModel, requestNotifications: () -> Unit = {}) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    BackHandler(enabled = state.route != AppRoute.Downloads) {
        viewModel.navigate(AppRoute.Downloads)
    }
    BoxWithConstraints(Modifier.fillMaxSize()) box@{
        val availableWidth = this@box.maxWidth
        val wide = availableWidth >= 840.dp
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                NavigationRail {
                    AppRoute.entries.forEach { route ->
                        NavigationRailItem(
                            selected = state.route == route,
                            onClick = { viewModel.navigate(route) },
                            modifier = Modifier.semantics { stateDescription = if (state.route == route) "${route.label} selected" else "${route.label} not selected" },
                            icon = { Icon(route.icon, route.label) },
                            label = { Text(route.label) },
                        )
                    }
                }
                AppScaffold(
                    state,
                    viewModel,
                    requestNotifications,
                    Modifier.fillMaxHeight().width((availableWidth - 80.dp).coerceAtLeast(0.dp)),
                    showBottomBar = false,
                )
            }
        } else {
            AppScaffold(state, viewModel, requestNotifications, Modifier.fillMaxSize(), showBottomBar = true)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppScaffold(
    state: MainUiState,
    viewModel: MainViewModel,
    requestNotifications: () -> Unit,
    modifier: Modifier,
    showBottomBar: Boolean,
) {
    var showMoreMenu by remember { mutableStateOf(false) }
    val overflowRouteSelected = state.route in overflowRoutes
    Scaffold(
        modifier = modifier,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(state.route.label) },
                actions = {
                    if (showBottomBar) {
                        IconButton(
                            onClick = { showMoreMenu = true },
                            modifier = Modifier.semantics { contentDescription = if (overflowRouteSelected) "More sections, ${state.route.label} selected" else "More sections" },
                        ) {
                            Icon(
                                Icons.Rounded.MoreVert,
                                if (overflowRouteSelected) "More sections, ${state.route.label} selected" else "More sections",
                                tint = if (overflowRouteSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        DropdownMenu(expanded = showMoreMenu, onDismissRequest = { showMoreMenu = false }) {
                            overflowRoutes.forEach { route ->
                                DropdownMenuItem(
                                    text = { Text(if (state.route == route) "${route.label} selected" else route.label) },
                                    leadingIcon = { Icon(route.icon, null) },
                                    onClick = {
                                        showMoreMenu = false
                                        viewModel.navigate(route)
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    primaryRoutes.forEach { route ->
                        NavigationBarItem(
                            selected = state.route == route,
                            onClick = { viewModel.navigate(route) },
                            modifier = Modifier.semantics { stateDescription = if (state.route == route) "${route.label} selected" else "${route.label} not selected" },
                            icon = { Icon(route.icon, route.label) },
                            label = { Text(route.label) },
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            if (state.route == AppRoute.Downloads) {
                FloatingActionButton(
                    onClick = { viewModel.navigate(AppRoute.Add) },
                    modifier = Modifier
                        .sizeIn(minWidth = 56.dp, minHeight = 56.dp)
                        .semantics { contentDescription = "Add download" },
                ) {
                    Icon(Icons.Rounded.Add, "Add download")
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (state.route) {
                AppRoute.Downloads -> DownloadsScreen(
                    state.downloads,
                    state.compactDensity,
                    state.activeTransfers,
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
                )
                AppRoute.Add -> AddDownloadScreen(
                    destinationUri = state.destinationUri,
                    conflictPolicy = state.conflictPolicy,
                    savedDestinations = state.destinationPermissions,
                    externalDraftId = state.externalAddDraft?.id,
                    initialUrl = state.externalAddDraft?.url,
                    initialFileName = state.externalAddDraft?.fileName,
                    externalSourceLabel = state.externalAddDraft?.sourceLabel,
                    onDestinationChanged = viewModel::setDestination,
                    onSafDestinationSelected = viewModel::registerSafDestination,
                    onConflictPolicyChanged = viewModel::setConflictPolicy,
                    onAdd = { url, name, backend, destination, conflictPolicy, allowFallback, expectedChecksum, checksumAlgorithm ->
                        requestNotifications()
                        viewModel.addDownload(url, name, backend, destination, conflictPolicy, allowFallback, expectedChecksum, checksumAlgorithm)
                    },
                    recommend = viewModel::backendRecommendation,
                )
                AppRoute.Queues -> QueuesScreen(
                    queues = state.queues,
                    onCreateQueue = viewModel::createQueue,
                    onUpdateQueue = viewModel::updateQueue,
                    onToggleQueue = viewModel::setQueueEnabled,
                    onDeleteQueue = viewModel::deleteQueue,
                )
                AppRoute.Scheduler -> SchedulerScreen(
                    rules = state.schedules,
                    queues = state.queues,
                    onCreateSchedule = viewModel::createSchedule,
                    onUpdateSchedule = viewModel::updateSchedule,
                    onToggleSchedule = viewModel::setScheduleEnabled,
                    onDeleteSchedule = viewModel::deleteSchedule,
                )
                AppRoute.Media -> MediaInboxScreen(
                    state.mediaCaptures,
                    state.mediaVariants,
                    state.downloads,
                    state.termuxMediaPipeline,
                    state.postProcessingAutomation,
                    viewModel::captureBrowserMediaUrl,
                    viewModel::openAddFromBrowser,
                    viewModel::downloadMediaCapture,
                    viewModel::togglePause,
                    viewModel::resolveMediaCapture,
                    viewModel::selectMediaVariant,
                    viewModel::removeMediaCapture,
                    viewModel::extractMediaMetadataWithTermux,
                    viewModel::inspectMediaWithTermuxFfprobe,
                    viewModel::downloadMediaWithTermuxYtDlp,
                    viewModel::convertMediaWithTermux,
                    viewModel::clearCompletedTermuxMediaJobs,
                    viewModel::previewPostProcessingForMedia,
                    viewModel::runPostProcessingForMedia,
                )
                AppRoute.Recovery -> RecoveryScreen(state.recovery, viewModel::validateRecoveryRecord, viewModel::removeRecoveryRecord)
                AppRoute.Diagnostics -> DiagnosticsScreen(
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
}
