package com.mikeyphw.xdm.android

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private val primaryRoutes = listOf(AppRoute.Downloads, AppRoute.Queues, AppRoute.Scheduler, AppRoute.Media)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun XdmApp(viewModel: MainViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 840.dp
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                NavigationRail {
                    AppRoute.entries.forEach { route ->
                        NavigationRailItem(
                            selected = state.route == route,
                            onClick = { viewModel.navigate(route) },
                            icon = { Icon(route.icon, route.label) },
                            label = { Text(route.label) },
                        )
                    }
                }
                AppScaffold(state, viewModel, Modifier.weight(1f), showBottomBar = false)
            }
        } else {
            AppScaffold(state, viewModel, Modifier.fillMaxSize(), showBottomBar = true)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppScaffold(state: MainUiState, viewModel: MainViewModel, modifier: Modifier, showBottomBar: Boolean) {
    var showMoreMenu by remember { mutableStateOf(false) }
    Scaffold(
        modifier = modifier,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(state.route.label) },
                actions = {
                    if (showBottomBar) {
                        IconButton(onClick = { showMoreMenu = true }) {
                            Icon(Icons.Rounded.MoreVert, "More sections")
                        }
                        DropdownMenu(expanded = showMoreMenu, onDismissRequest = { showMoreMenu = false }) {
                            listOf(AppRoute.Add, AppRoute.Recovery, AppRoute.Diagnostics, AppRoute.Settings).forEach { route ->
                                DropdownMenuItem(
                                    text = { Text(route.label) },
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
                            icon = { Icon(route.icon, route.label) },
                            label = { Text(route.label) },
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            if (state.route == AppRoute.Downloads) {
                FloatingActionButton(onClick = { viewModel.navigate(AppRoute.Add) }) {
                    Icon(Icons.Rounded.Add, "Add download")
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (state.route) {
                AppRoute.Downloads -> DownloadsScreen(state.downloads, state.compactDensity, state.activeTransfers, viewModel::togglePause, viewModel::pauseAll, viewModel::resumeAll)
                AppRoute.Add -> AddDownloadScreen(
                    destinationUri = state.destinationUri,
                    conflictPolicy = state.conflictPolicy,
                    savedDestinations = state.destinationPermissions,
                    onDestinationChanged = viewModel::setDestination,
                    onSafDestinationSelected = viewModel::registerSafDestination,
                    onConflictPolicyChanged = viewModel::setConflictPolicy,
                    onAdd = viewModel::addDownload,
                    recommend = viewModel::backendRecommendation,
                )
                AppRoute.Queues -> QueuesScreen(state.queues)
                AppRoute.Scheduler -> SchedulerScreen(state.schedules)
                AppRoute.Media -> EmptyFeatureScreen("Media inbox", "Detected HLS and DASH streams will appear here in a later milestone.")
                AppRoute.Recovery -> RecoveryScreen(state.recovery)
                AppRoute.Diagnostics -> DiagnosticsScreen(state)
                AppRoute.Settings -> SettingsScreen(state.compactDensity, viewModel::setCompactDensity)
            }
        }
    }
}
