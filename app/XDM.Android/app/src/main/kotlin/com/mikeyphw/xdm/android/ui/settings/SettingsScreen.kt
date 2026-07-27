package com.mikeyphw.xdm.android

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

@Composable
@UiSurface(UiAudience.User, "Configure downloads, appearance, privacy, support, and optional developer tools")
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
                if (state.developerOptionsEnabled) "Developer options enabled" else "Developer options disabled",
            ),
    ) {
        when (state.settingsPanel) {
            SettingsPanel.Overview -> SettingsOverview(state, viewModel)
            SettingsPanel.AdvancedDownloads -> AdvancedDownloadSettingsScreen(state, viewModel)
            SettingsPanel.Privacy -> PrivacySettingsScreen(state, viewModel)
            SettingsPanel.BrowserExtension -> BrowserExtensionSettingsScreen(state, viewModel)
            SettingsPanel.DeveloperTools -> DeveloperSettingsScreen(state, viewModel)
        }
    }
}

@Composable
private fun SettingsOverview(state: MainUiState, viewModel: MainViewModel) {
    val context = LocalContext.current
    val destinationPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.toString()?.let(viewModel::registerSafDestination)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            XdmSectionHeader("Settings")
            XdmSupportingText("Everyday preferences first. Advanced engines and diagnostics stay one level deeper.", maxLines = 3)
        }
        item { XdmSectionHeader("Downloads") }
        item {
            SettingsActionRow(
                title = "Save location",
                summary = destinationSummary(state.destinationUri),
                actionLabel = "Change",
                onClick = { destinationPicker.launch(null) },
            )
        }
        item {
            SettingsActionRow(
                title = "Smart queue",
                summary = queueSummary(state),
                actionLabel = "Manage",
                onClick = { viewModel.navigateActivity(ActivityPanel.Queues) },
            )
        }
        item {
            SettingsActionRow(
                title = "Notifications",
                summary = "Choose whether Android shows active-transfer and completion notifications.",
                actionLabel = "Open",
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                    )
                },
            )
        }
        item {
            SettingsActionRow(
                title = "Advanced download rules",
                summary = "Destinations, duplicate handling, proxy, Termux, aria2, conversion, and settings backup.",
                actionLabel = "Open",
                onClick = { viewModel.selectSettingsPanel(SettingsPanel.AdvancedDownloads) },
            )
        }

        item { XdmSectionHeader("Appearance") }
        item {
            XdmListCard(compact = true) {
                XdmCardTitle("Theme")
                XdmSupportingText("Both themes stay dark and borderless. AMOLED black removes the remaining background glow.", maxLines = 3)
                XdmActionFlowRow {
                    XdmThemeMode.entries.forEach { mode ->
                        FilterChip(
                            selected = state.themeMode == mode,
                            onClick = { viewModel.setThemeMode(mode) },
                            label = { Text(mode.label) },
                        )
                    }
                }
            }
        }
        item {
            SettingsSwitchRow(
                title = "Compact rows",
                summary = "Fit more downloads on screen without hiding progress or primary actions.",
                checked = state.compactDensity,
                onCheckedChange = viewModel::setCompactDensity,
            )
        }

        item { XdmSectionHeader("Browser integration") }
        item {
            SettingsActionRow(
                title = "Browser extension",
                summary = browserExtensionSummary(state),
                actionLabel = "Open",
                onClick = { viewModel.selectSettingsPanel(SettingsPanel.BrowserExtension) },
            )
        }

        item { XdmSectionHeader("Privacy and support") }
        item {
            SettingsActionRow(
                title = "Privacy",
                summary = "Review redaction, private media sessions, clipboard intake, and cleanup behavior.",
                actionLabel = "Open",
                onClick = { viewModel.selectSettingsPanel(SettingsPanel.Privacy) },
            )
        }
        item {
            SettingsActionRow(
                title = "Copy support report",
                summary = "Copies a redacted report without cookies, authorization values, tokens, signatures, or credential-bearing URLs.",
                actionLabel = "Copy",
                onClick = { copyTextToClipboard(context, "XDM support report", state.supportReportText) },
            )
        }
        item {
            SettingsSwitchRow(
                title = "Developer options",
                summary = "Reveal runtime probes, engine matrices, planner diagnostics, redacted logs, and release checks.",
                checked = state.developerOptionsEnabled,
                onCheckedChange = viewModel::setDeveloperOptionsEnabled,
            )
        }
        if (state.developerOptionsEnabled) {
            item {
                SettingsActionRow(
                    title = "Developer tools",
                    summary = "Open the gated technical workspace. These dashboards never appear in normal Media, Library, Activity, or Settings pages.",
                    actionLabel = "Open",
                    onClick = viewModel::openDeveloperTools,
                )
            }
        }

        item { XdmSectionHeader("About") }
        item {
            XdmListCard(compact = true) {
                XdmCardTitle("XDM Android")
                XdmSupportingText("Version ${BuildConfig.VERSION_NAME.removeSuffix("-debug")} • ${releaseChannelLabel()}")
                XdmMetadataText("Downloader-only Android app with external browser handoff and no built-in browser.", maxLines = 3)
            }
        }
    }
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
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        XdmSectionHeader(title)
        TextButton(onClick = onBack) { Text("Back") }
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
                .semantics { stateDescription = if (checked) "$title enabled" else "$title disabled" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                XdmCardTitle(title)
                XdmSupportingText(summary, maxLines = 3)
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                modifier = Modifier.semantics { stateDescription = if (checked) "$title enabled" else "$title disabled" },
            )
        }
    }
}

private fun destinationSummary(uri: String): String = when {
    uri.contains("public-downloads", ignoreCase = true) -> "Public Downloads folder"
    uri.contains("app-private", ignoreCase = true) -> "App-private Downloads folder"
    uri.startsWith("content://") -> "Selected Android folder"
    else -> "Configured download folder"
}

private fun browserExtensionSummary(state: MainUiState): String = when {
    state.browserExtension.lastExportFileName.isNotBlank() -> "${state.browserExtension.lastExportFileName} • verified SHA-256 export"
    state.browserExtension.exportTreeUri.isNotBlank() -> "Export folder selected • ready to generate the Firefox XPI"
    else -> "Generate the XDM Firefox bridge into a folder you choose"
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
