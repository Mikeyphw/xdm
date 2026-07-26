package com.mikeyphw.xdm.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun XdmAdaptiveShell(
    windowClass: XdmWindowClass,
    selectedRoute: AppRoute,
    destinations: List<AppRoute>,
    activeTransferCount: Int,
    queuedTransferCount: Int,
    runtimeLabel: String,
    onNavigate: (AppRoute) -> Unit,
    onAddDownload: () -> Unit,
    content: @Composable () -> Unit,
) {
    if (windowClass.usesNavigationSidebar) {
        Row(Modifier.fillMaxSize()) {
            XdmNavigationSidebar(
                selectedRoute = selectedRoute,
                destinations = destinations,
                activeTransferCount = activeTransferCount,
                queuedTransferCount = queuedTransferCount,
                runtimeLabel = runtimeLabel,
                onNavigate = onNavigate,
                onAddDownload = onAddDownload,
            )
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.End + WindowInsetsSides.Bottom))
                        .imePadding(),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Column(Modifier.fillMaxSize().widthIn(max = 1480.dp)) {
                        if (selectedRoute !in setOf(AppRoute.Downloads, AppRoute.Media, AppRoute.Library)) {
                            XdmPageHeader(
                                title = selectedRoute.label,
                                subtitle = selectedRoute.shellSubtitle(),
                            )
                        }
                        Box(Modifier.fillMaxWidth().weight(1f)) { content() }
                    }
                }
            }
        }
    } else {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                XdmCompactTopBar(
                    route = selectedRoute,
                    onAddDownload = onAddDownload,
                )
            },
            bottomBar = {
                XdmBottomNavigation(
                    selectedRoute = selectedRoute,
                    destinations = destinations,
                    onNavigate = onNavigate,
                )
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).imePadding()) { content() }
        }
    }
}

@Composable
private fun XdmCompactTopBar(route: AppRoute, onAddDownload: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
        color = MaterialTheme.colorScheme.background,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(60.dp)
                .padding(start = 20.dp, end = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                route.label,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(
                onClick = onAddDownload,
                modifier = Modifier
                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .semantics { contentDescription = "New download" },
            ) {
                Icon(Icons.Rounded.Add, contentDescription = "New download")
            }
        }
    }
}

@Composable
private fun XdmBottomNavigation(
    selectedRoute: AppRoute,
    destinations: List<AppRoute>,
    onNavigate: (AppRoute) -> Unit,
) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
        windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
    ) {
        destinations.forEach { route ->
            val selected = selectedRoute == route
            NavigationBarItem(
                selected = selected,
                onClick = { onNavigate(route) },
                modifier = Modifier.semantics {
                    stateDescription = if (selected) "${route.label} selected" else "${route.label} not selected"
                },
                icon = { Icon(route.icon, contentDescription = route.label) },
                label = { Text(route.label, maxLines = 1) },
                alwaysShowLabel = true,
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    disabledIconColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                    disabledTextColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                ),
            )
        }
    }
}

@Composable
private fun XdmNavigationSidebar(
    selectedRoute: AppRoute,
    destinations: List<AppRoute>,
    activeTransferCount: Int,
    queuedTransferCount: Int,
    runtimeLabel: String,
    onNavigate: (AppRoute) -> Unit,
    onAddDownload: () -> Unit,
) {
    Surface(
        modifier = Modifier.width(224.dp).fillMaxHeight(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Start + WindowInsetsSides.Top + WindowInsetsSides.Bottom))
                .padding(horizontal = 14.dp, vertical = 18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(
                    modifier = Modifier.size(40.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = MaterialTheme.shapes.medium,
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(22.dp))
                    }
                }
                Column {
                    Text("XDM", style = MaterialTheme.typography.titleMedium)
                    Text("Download manager", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = onAddDownload,
                modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).semantics { contentDescription = "New download" },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp, pressedElevation = 0.dp, focusedElevation = 0.dp, hoveredElevation = 0.dp),
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("New download")
            }
            Spacer(Modifier.height(18.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                destinations.forEach { route ->
                    XdmSidebarDestination(
                        route = route,
                        selected = selectedRoute == route,
                        onClick = { onNavigate(route) },
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            XdmRuntimeSummary(
                activeTransferCount = activeTransferCount,
                queuedTransferCount = queuedTransferCount,
                runtimeLabel = runtimeLabel,
            )
        }
    }
}

@Composable
private fun XdmSidebarDestination(route: AppRoute, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .sizeIn(minHeight = 48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                this.selected = selected
                role = Role.Button
                stateDescription = if (selected) "${route.label} selected" else "${route.label} not selected"
            },
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(route.icon, contentDescription = null, modifier = Modifier.size(21.dp))
            Text(route.label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun XdmRuntimeSummary(activeTransferCount: Int, queuedTransferCount: Int, runtimeLabel: String) {
    Surface(
        modifier = Modifier.fillMaxWidth().semantics {
            contentDescription = "$activeTransferCount active downloads, $queuedTransferCount queued. $runtimeLabel"
        },
        color = XdmTheme.extendedColors.groupedSurface,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("Runtime", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                when {
                    activeTransferCount > 0 -> "$activeTransferCount active"
                    queuedTransferCount > 0 -> "$queuedTransferCount queued"
                    else -> "Idle"
                },
                style = MaterialTheme.typography.titleSmall,
            )
            Text(runtimeLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}


private fun AppRoute.shellSubtitle(): String = when (label) {
    "Downloads" -> "Track transfers, queue work, and resolve anything that needs attention."
    "Media" -> "Inspect captured media and choose what to download."
    "Library" -> "Open completed media and continue where you left off."
    "Activity" -> "Review transfer history, decisions, schedules, and recovery."
    "Settings" -> "Tune XDM behavior without exposing implementation noise."
    else -> "Review and manage XDM."
}
