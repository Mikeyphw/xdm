package com.mikeyphw.xdm.android

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.mikeyphw.xdm.android.storage.DestinationUris
import com.mikeyphw.xdm.android.storage.PersonalDirectStorage
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.dp
import com.mikeyphw.xdm.android.ui.debug.DebugWorkbenchSettingsScreen

@Composable
@UiSurface(UiAudience.User, "Configure downloads, appearance, privacy, support, and the optional Developer Center")
fun SettingsScreen(
    state: MainUiState,
    viewModel: MainViewModel,
) {
    BackHandler(enabled = state.settingsPanel != SettingsPanel.Overview) {
        viewModel.selectSettingsPanel(SettingsPanel.Overview)
    }

    Box(
        Modifier
            .fillMaxSize()
            .xdmScreen(XdmScreenTags.Settings, "Settings")
            .xdmStateDescription(
                if (state.developerOptionsEnabled) "Developer mode enabled" else "Developer mode disabled",
            ),
    ) {
        when (state.settingsPanel) {
            SettingsPanel.Overview -> SettingsOverview(state, viewModel)
            SettingsPanel.StorageDestinations -> StorageDestinationsSettingsScreen(state, viewModel)
            SettingsPanel.AdvancedDownloads -> AdvancedDownloadSettingsScreen(state, viewModel)
            SettingsPanel.Network -> NetworkSettingsScreen(state, viewModel)
            SettingsPanel.Media -> MediaCaptureSettingsScreen(state, viewModel)
            SettingsPanel.ExternalTools -> ExternalToolsSettingsScreen(state, viewModel)
            SettingsPanel.PostProcessing -> PostProcessingSettingsScreen(state, viewModel)
            SettingsPanel.Appearance -> AppearanceSettingsScreen(state, viewModel)
            SettingsPanel.BackupRestore -> BackupRestoreSettingsScreen(state, viewModel)
            SettingsPanel.Privacy -> PrivacySettingsScreen(state, viewModel)
            SettingsPanel.BrowserExtension -> BrowserExtensionSettingsScreen(state, viewModel)
            SettingsPanel.DebugWorkbench -> DebugWorkbenchSettingsScreen(state, viewModel)
            SettingsPanel.DeveloperTools -> DeveloperSettingsScreen(state, viewModel)
        }
    }
}

@Composable
private fun SettingsOverview(state: MainUiState, viewModel: MainViewModel) {
    val context = LocalContext.current

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            XdmSupportingText(
                "Choose a category. Everyday download, storage, network, media, and app settings stay separate from diagnostics and developer controls.",
                maxLines = 3,
            )
        }

        item { XdmSectionHeader("Downloads") }
        item {
            SettingsNavigationGroup(
                rows = listOf(
                    SettingsNavigationItem(
                        "Storage & destinations",
                        destinationSettingsSummary(state),
                    ) { viewModel.selectSettingsPanel(SettingsPanel.StorageDestinations) },
                    SettingsNavigationItem(
                        "Download behavior",
                        "Destination rules, duplicate handling, conflicts, and smart queue controls.",
                    ) { viewModel.selectSettingsPanel(SettingsPanel.AdvancedDownloads) },
                    SettingsNavigationItem(
                        "Network",
                        if (state.proxySettings.enabled) state.proxySettings.redactedSummary else "Proxy and connection profile.",
                    ) { viewModel.selectSettingsPanel(SettingsPanel.Network) },
                    SettingsNavigationItem(
                        "Post-processing",
                        state.postProcessingSettings.redactedSummary,
                    ) { viewModel.selectSettingsPanel(SettingsPanel.PostProcessing) },
                ),
            )
        }

        item { XdmSectionHeader("Media & integrations") }
        item {
            SettingsNavigationGroup(
                rows = listOf(
                    SettingsNavigationItem(
                        "Media & capture",
                        "Live Locator, browser capture, completed-media behavior, and media processing entry points.",
                    ) { viewModel.selectSettingsPanel(SettingsPanel.Media) },
                    SettingsNavigationItem(
                        "Browser integration",
                        browserExtensionSummary(state),
                    ) { viewModel.selectSettingsPanel(SettingsPanel.BrowserExtension) },
                    SettingsNavigationItem(
                        "External tools",
                        "Termux, aria2, optional privileged actions, and runtime integration.",
                    ) { viewModel.selectSettingsPanel(SettingsPanel.ExternalTools) },
                ),
            )
        }

        item { XdmSectionHeader("App") }
        item {
            SettingsNavigationGroup(
                rows = listOf(
                    SettingsNavigationItem(
                        "Notifications",
                        "Android transfer and completion notification settings.",
                    ) {
                        context.startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                        )
                    },
                    SettingsNavigationItem(
                        "Appearance",
                        "${state.themeMode.label} theme • ${if (state.compactDensity) "Compact" else "Comfortable"} rows",
                    ) { viewModel.selectSettingsPanel(SettingsPanel.Appearance) },
                    SettingsNavigationItem(
                        "Privacy",
                        "Redaction, private media sessions, clipboard intake, automation trust, and cleanup.",
                    ) { viewModel.selectSettingsPanel(SettingsPanel.Privacy) },
                    SettingsNavigationItem(
                        "Backup & restore",
                        state.backupRestoreReport.summary,
                    ) { viewModel.selectSettingsPanel(SettingsPanel.BackupRestore) },
                ),
            )
        }

        item { XdmSectionHeader("Support") }
        item {
            SettingsNavigationGroup(
                rows = listOf(
                    SettingsNavigationItem(
                        "Diagnostics & support",
                        "Safe health checks, recorder status, and redacted support exports.",
                    ) { viewModel.selectSettingsPanel(SettingsPanel.DebugWorkbench) },
                ),
            )
        }
        item {
            SettingsSwitchRow(
                title = "Developer mode",
                summary = "Unlock deep runtime, engine, media, worker, privacy, log, and release inspection in one Developer Center.",
                checked = state.developerOptionsEnabled,
                onCheckedChange = viewModel::setDeveloperOptionsEnabled,
            )
        }
        if (state.developerOptionsEnabled) {
            item {
                SettingsNavigationGroup(
                    rows = listOf(
                        SettingsNavigationItem(
                            "Developer Center",
                            "Inspect redacted technical state and advanced controls without mixing them into normal settings.",
                            viewModel::openDeveloperTools,
                        ),
                    ),
                )
            }
        }

        item { XdmSectionHeader("About") }
        item {
            XdmGroupedList {
                XdmListRow(
                    headline = "XDM Android",
                    supporting = "Version ${BuildConfig.VERSION_NAME.removeSuffix("-debug")} • ${releaseChannelLabel()} • Downloader-only Android app",
                )
            }
        }
    }
}

private data class SettingsNavigationItem(
    val title: String,
    val summary: String,
    val onClick: () -> Unit,
)

@Composable
private fun SettingsNavigationGroup(rows: List<SettingsNavigationItem>) {
    XdmGroupedList {
        rows.forEachIndexed { index, row ->
            XdmListRow(
                headline = row.title,
                supporting = row.summary,
                trailing = { Text("Open", color = androidx.compose.material3.MaterialTheme.colorScheme.primary) },
                onClick = row.onClick,
            )
            if (index != rows.lastIndex) XdmListSeparator()
        }
    }
}

private fun destinationSettingsSummary(state: MainUiState): String {
    val current = state.destinationPermissions.firstOrNull { it.uri == state.destinationUri }
    val status = current?.status?.name
        ?.replace(Regex("([a-z])([A-Z])"), "$1 $2")
        ?.lowercase()
        ?.replaceFirstChar(Char::titlecase)
    return listOfNotNull(destinationSummary(state.destinationUri), status).joinToString(" • ")
}

@Composable
private fun PrivacySettingsScreen(state: MainUiState, viewModel: MainViewModel) {
    val context = LocalContext.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { SettingsPageHeader("Privacy", { viewModel.selectSettingsPanel(SettingsPanel.Overview) }) }
        item {
            XdmListCard {
                XdmCardTitle("Private by default")
                XdmSupportingText("Media page context is kept only long enough to review and hand off a download. Normal screens never print cookies, authorization headers, raw commands, or full secret-bearing URLs.", maxLines = 5)
            }
        }
        item {
            val trustStore = remember(context) { ExternalAutomationTrustStore(context) }
            var configured by remember { mutableStateOf(trustStore.isConfigured()) }
            var generatedSecret by remember { mutableStateOf<String?>(null) }
            XdmListCard {
                XdmCardTitle("External automation secret")
                XdmSupportingText(
                    if (configured) {
                        "Tasker-style actions require the current user-created secret or an on-screen confirmation. Rotate it to invalidate every previous integration."
                    } else {
                        "Create a one-time visible secret for trusted Tasker-style automation. Untrusted actions always require confirmation."
                    },
                    maxLines = 5,
                )
                generatedSecret?.let { secret ->
                    XdmMetadataText("Secret generated. Copy it now; XDM stores only a salted verifier and cannot show it again.", maxLines = 3)
                    XdmActionFlowRow {
                        Button(onClick = { copySensitiveTextToClipboard(context, "XDM integration secret", secret) }) { Text("Copy once") }
                        TextButton(onClick = { generatedSecret = null }) { Text("Hide") }
                    }
                } ?: XdmActionFlowRow {
                    Button(onClick = {
                        generatedSecret = trustStore.generateAndRotate()
                        configured = true
                    }) { Text(if (configured) "Rotate secret" else "Generate secret") }
                    if (configured) {
                        TextButton(onClick = {
                            trustStore.revoke()
                            configured = false
                            generatedSecret = null
                        }) { Text("Revoke") }
                    }
                }
            }
        }
        item {
            XdmListCard {
                XdmCardTitle("Clipboard and external links")
                XdmSupportingText("XDM reviews supported links before queueing. Rejected or incomplete handoffs appear as plain-language Activity items instead of raw intake payloads.", maxLines = 5)
                XdmActionFlowRow {
                    StatusPill("${state.clipboardInbox.size} clipboard items", XdmStatusTone.Neutral)
                    StatusPill("${state.automationCommands.size} handoffs", XdmStatusTone.Info)
                }
            }
        }
        item {
            XdmListCard {
                XdmCardTitle("Cleanup and support")
                XdmSupportingText("Support exports are redacted before copying. Clearing Activity history does not delete downloads, user files, queue definitions, or unresolved recovery records.", maxLines = 5)
                Button(onClick = { copyTextToClipboard(context, "XDM support report", state.supportReportText) }) {
                    Text("Copy support report")
                }
            }
        }
    }
}


@Composable
internal fun SettingsPageHeader(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .xdmMinimumTouchTarget()
                .semantics { contentDescription = "Back to Settings" },
        ) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
        }
        Text(
            title,
            modifier = Modifier.weight(1f).semantics { heading() },
            style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
        )
    }
}

@Composable
private fun SettingsActionRow(
    title: String,
    summary: String,
    actionLabel: String,
    onClick: () -> Unit,
) {
    XdmListCard(
        compact = true,
        modifier = Modifier
            .xdmMinimumTouchTarget()
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "$title. $summary. $actionLabel" },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                XdmCardTitle(title)
                XdmSupportingText(summary, maxLines = 3)
            }
            Text(
                text = actionLabel,
                style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
                color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
            )
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    XdmListCard(compact = true) {
        Row(
            Modifier
                .fillMaxWidth()
                .xdmMinimumTouchTarget()
                .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
                .semantics(mergeDescendants = true) {
                    contentDescription = "$title. $summary"
                    stateDescription = if (checked) "$title enabled" else "$title disabled"
                    role = Role.Switch
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                XdmCardTitle(title)
                XdmSupportingText(summary, maxLines = 3)
            }
            Switch(
                checked = checked,
                onCheckedChange = null,
                modifier = Modifier.clearAndSetSemantics { },
            )
        }
    }
}

private fun destinationSummary(uri: String): String = when {
    uri == DestinationUris.DIRECT_DOWNLOADS -> "Direct Download/XDM folder"
    uri.contains("public-downloads", ignoreCase = true) -> "Public Downloads via MediaStore"
    uri.contains("app-private", ignoreCase = true) -> "App-private Downloads folder"
    uri.startsWith("content://") -> "Selected Android folder"
    else -> "Configured download folder"
}

private fun browserExtensionSummary(state: MainUiState): String = when {
    state.browserBridgeStatus.isReady -> "Firefox extension connected • handoff ready"
    state.browserExtension.lastExportFileName.isNotBlank() -> "Verified Firefox extension package available"
    state.browserExtension.exportTreeUri.isNotBlank() -> "Package folder selected • ready to generate extension"
    else -> "Connect Firefox and prepare the XDM extension"
}

private fun queueSummary(state: MainUiState): String {
    val enabled = state.queues.count { it.isEnabled }
    val schedules = state.schedules.count { it.enabled }
    return "$enabled enabled queues • $schedules active schedules • ${state.queueIntelligence.message}"
}

private fun releaseChannelLabel(): String = when {
    BuildConfig.VERSION_NAME.contains("rc", ignoreCase = true) -> "Release candidate channel"
    else -> "Stable channel"
}
