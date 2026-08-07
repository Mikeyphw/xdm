package com.mikeyphw.xdm.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
@UiSurface(UiAudience.Developer, "Open the gated redacted technical workspace")
internal fun DeveloperSettingsScreen(state: MainUiState, viewModel: MainViewModel) {
    if (!DeveloperWorkspacePolicy.shouldCompose(state.developerOptionsEnabled, state.settingsPanel)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().xdmScreen(XdmScreenTags.DeveloperTools, "Developer tools"),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { SettingsPageHeader("Developer tools", { viewModel.selectSettingsPanel(SettingsPanel.Overview) }) }
            item {
                XdmListCard {
                    XdmCardTitle("Developer options are off")
                    XdmSupportingText("Enable them to reveal redacted runtime probes, engine controls, media planners, intake diagnostics, and release-readiness checks.", maxLines = 4)
                    Button(onClick = { viewModel.setDeveloperOptionsEnabled(true) }) { Text("Enable developer options") }
                }
            }
        }
        return
    }

    Column(Modifier.fillMaxSize().xdmScreen(XdmScreenTags.DeveloperTools, "Developer tools")) {
        SettingsPageHeader(
            title = "Developer tools",
            onBack = { viewModel.selectSettingsPanel(SettingsPanel.Overview) },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        DeveloperToolsWorkspace(
            state = state,
            browserStatus = state.browserIntegrationStatus,
            clipboardInbox = state.clipboardInbox,
            onRunAria2SmokeTest = viewModel::runAria2SmokeTest,
            onRepairAria2 = viewModel::repairEmbeddedAria2,
            onRunTermuxProbe = viewModel::runTermuxToolProbe,
            onRunTermuxRootProbe = viewModel::runTermuxRootProbe,
            onCollectRootDiagnostics = viewModel::collectTermuxRootProcessDiagnostics,
            onKillStuckAria2WithRoot = viewModel::killStuckTermuxAria2WithRoot,
            onStartTermuxAria2Daemon = viewModel::startTermuxAria2Daemon,
            onStopTermuxAria2Daemon = viewModel::stopTermuxAria2Daemon,
            onProbeTermuxAria2Daemon = viewModel::probeTermuxAria2Daemon,
            onRefreshTermuxAria2Tasks = viewModel::refreshTermuxAria2Tasks,
            onPauseAllTermuxAria2Tasks = viewModel::pauseAllTermuxAria2Tasks,
            onResumeAllTermuxAria2Tasks = viewModel::resumeAllTermuxAria2Tasks,
            onSaveTermuxAria2Session = viewModel::saveTermuxAria2Session,
            onRetryPostProcessing = viewModel::retryFailedPostProcessing,
            onClearPostProcessingEvents = viewModel::clearPostProcessingEvents,
            onClearTermuxMediaJobs = viewModel::clearCompletedTermuxMediaJobs,
            onPauseTermuxMediaJob = viewModel::pauseTermuxMediaJob,
            onResumeTermuxMediaJob = viewModel::resumeTermuxMediaJob,
            onCancelTermuxMediaJob = viewModel::cancelTermuxMediaJob,
            onForceCancelTermuxMediaJob = viewModel::forceCancelTermuxMediaJob,
            onRetryTermuxMediaJob = viewModel::retryTermuxMediaJob,
            onRecoverTermuxMediaPublication = viewModel::recoverTermuxMediaPublication,
            onScanClipboardText = viewModel::scanClipboardText,
            onAcceptClipboardItem = viewModel::acceptClipboardItem,
            onDismissClipboardItem = viewModel::dismissClipboardItem,
            onOpenRecentActivity = { viewModel.navigateActivity(ActivityPanel.Timeline) },
            onClearActivityHistory = viewModel::clearActivityHistory,
        )
    }
}
