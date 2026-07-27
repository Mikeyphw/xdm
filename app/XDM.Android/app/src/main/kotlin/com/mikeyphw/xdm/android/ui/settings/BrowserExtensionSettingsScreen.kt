package com.mikeyphw.xdm.android

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mikeyphw.xdm.android.browserextension.BrowserExtensionSourceContract
import java.text.DateFormat
import java.util.Date

@Composable
internal fun BrowserExtensionSettingsScreen(
    state: MainUiState,
    viewModel: MainViewModel,
) {
    val context = LocalContext.current
    val preferences = state.browserExtension
    val runtime = state.browserExtensionRuntime
    val health = state.browserBridgeStatus
    val diagnostics = state.browserBridgeDiagnostics
    val resolvedTheme = preferences.resolvedTheme(state.themeMode)
    val staleReasons = preferences.staleReasons(
        appTheme = state.themeMode,
        appVersion = BuildConfig.VERSION_NAME,
        applicationId = BuildConfig.APPLICATION_ID,
        scheme = BuildConfig.XDM_BROWSER_SCHEME,
    )
    val themeStale = preferences.isThemeStale(state.themeMode)
    val exportFolderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.toString()?.let(viewModel::registerBrowserExtensionExportDirectory)
    }
    val setupInstructions = browserBridgeIronFoxInstructions(BuildConfig.XDM_BROWSER_SCHEME)

    LaunchedEffect(Unit) { viewModel.refreshBrowserExtensionStatus() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { SettingsPageHeader("Browser extension", { viewModel.selectSettingsPanel(SettingsPanel.Overview) }) }
        item {
            XdmListCard {
                XdmCardTitle("Bridge status")
                XdmStatusBadge(
                    text = if (health.isReady) "Ready" else "Needs attention",
                    tone = if (health.isReady) XdmStatusTone.Success else XdmStatusTone.Warning,
                )
                XdmSupportingText(health.schemeDetail, maxLines = 4)
                XdmSupportingText(health.safDetail, maxLines = 4)
                XdmMetadataText(
                    "Extension ${BrowserExtensionSourceContract.DevelopmentVersion} • Contract ${BrowserExtensionSourceContract.ContractVersion} • ${BuildConfig.XDM_BROWSER_SCHEME}",
                    maxLines = 3,
                )
                XdmActionFlowRow {
                    TextButton(onClick = viewModel::refreshBrowserExtensionStatus) { Text("Refresh status") }
                    if (health.canOpenExport) {
                        TextButton(onClick = viewModel::openBrowserExtensionXpi) { Text("Open exported XPI") }
                    }
                }
            }
        }
        if (health.compatibilityIssues.isNotEmpty()) {
            item {
                XdmListCard {
                    XdmCardTitle("Compatibility and recovery")
                    health.compatibilityIssues.forEach { issue ->
                        XdmStatusBadge(issue, tone = XdmStatusTone.Warning)
                    }
                    XdmSupportingText(
                        "Regeneration is safe: XDM stages and validates the replacement before promoting it, and preserves the previous verified XPI when replacement fails.",
                        maxLines = 5,
                    )
                }
            }
        }
        item {
            XdmListCard {
                XdmCardTitle("Export folder")
                XdmSupportingText(exportFolderSummary(preferences.exportTreeUri), maxLines = 3)
                XdmActionFlowRow {
                    Button(onClick = { exportFolderPicker.launch(null) }) {
                        Text(if (preferences.exportTreeUri.isBlank()) "Choose folder" else "Change folder")
                    }
                    if (preferences.exportTreeUri.isNotBlank()) {
                        TextButton(onClick = viewModel::clearBrowserExtensionExportFolder) { Text("Clear folder") }
                    }
                }
            }
        }
        item {
            XdmListCard {
                XdmCardTitle("Default target")
                XdmSupportingText("The themed page FAB can open detected media in XDM, 1DM+, or expand compact target choices.", maxLines = 3)
                XdmActionFlowRow {
                    BrowserExtensionSourceContract.Target.entries.forEach { target ->
                        FilterChip(
                            selected = preferences.defaultTarget == target,
                            onClick = { viewModel.setBrowserExtensionDefaultTarget(target) },
                            label = { Text(target.label) },
                        )
                    }
                }
                if (preferences.lastExportTarget != null && preferences.lastExportTarget != preferences.defaultTarget) {
                    XdmStatusBadge("Regenerate to apply the new target", tone = XdmStatusTone.Warning)
                }
            }
        }
        item {
            XdmListCard {
                XdmCardTitle("Generated theme")
                XdmSupportingText(
                    "Follow app captures XDM's current ${state.themeMode.label} palette. Firefox cannot read later Android theme changes, so a changed app theme requires regeneration.",
                    maxLines = 5,
                )
                XdmActionFlowRow {
                    BrowserExtensionSourceContract.ThemeSelection.entries.forEach { theme ->
                        FilterChip(
                            selected = preferences.requestedTheme == theme,
                            onClick = { viewModel.setBrowserExtensionTheme(theme) },
                            label = { Text(theme.label) },
                        )
                    }
                }
                XdmMetadataText("Next package: ${resolvedTheme.label}")
                if (themeStale) {
                    XdmStatusBadge("Regeneration needed", tone = XdmStatusTone.Warning)
                }
            }
        }
        item {
            XdmListCard {
                XdmCardTitle(if (staleReasons.isNotEmpty()) "Regenerate XPI" else "Generate XPI")
                XdmSupportingText(runtime.message, maxLines = 4)
                XdmActionFlowRow {
                    Button(
                        enabled = preferences.exportTreeUri.isNotBlank() && runtime.phase != BrowserExtensionExportPhase.Exporting,
                        onClick = viewModel::generateBrowserExtensionXpi,
                    ) {
                        Text(
                            when {
                                runtime.phase == BrowserExtensionExportPhase.Exporting -> "Generating…"
                                staleReasons.isNotEmpty() && preferences.lastExportFileName.isNotBlank() -> "Regenerate XPI"
                                else -> "Generate XPI"
                            },
                        )
                    }
                    if (health.canOpenExport) {
                        TextButton(onClick = viewModel::openBrowserExtensionXpi) { Text("Open XPI") }
                    }
                }
            }
        }
        if (preferences.lastExportFileName.isNotBlank()) {
            item {
                XdmListCard {
                    XdmCardTitle("Last verified export")
                    XdmSupportingText(preferences.lastExportFileName, maxLines = 2)
                    XdmMetadataText("${formatByteCount(preferences.lastExportByteCount)} • ${formatExportTime(preferences.lastExportEpochMs)}")
                    XdmMetadataText("SHA-256 ${preferences.lastExportSha256}", maxLines = 3)
                    XdmMetadataText(
                        "${preferences.lastExportApplicationId.ifBlank { "Unknown variant" }} • ${preferences.lastExportScheme.ifBlank { "Unknown scheme" }}",
                        maxLines = 3,
                    )
                    XdmMetadataText(
                        "App ${preferences.lastExportAppVersion} • Extension ${preferences.lastExportExtensionVersion} • Contract ${preferences.lastExportContractVersion} • ${preferences.lastExportTheme?.label ?: "Unknown theme"}",
                        maxLines = 4,
                    )
                }
            }
        }
        item {
            XdmListCard {
                XdmCardTitle("IronFox setup")
                XdmSupportingText(
                    "Use the generated variant scheme ${BuildConfig.XDM_BROWSER_SCHEME}. IronFox must expose that protocol and allow links to open in apps.",
                    maxLines = 4,
                )
                XdmActionFlowRow {
                    TextButton(onClick = { copyTextToClipboard(context, "XDM IronFox setup", setupInstructions) }) {
                        Text("Copy setup instructions")
                    }
                }
            }
        }
        item {
            XdmListCard {
                XdmCardTitle("Redacted diagnostics")
                XdmMetadataText("Scheme registration: ${health.schemeState.displayLabel}")
                XdmMetadataText("SAF state: ${health.safState.displayLabel}")
                XdmMetadataText(
                    "Last accepted link: ${diagnostics.lastAcceptedSummary.ifBlank { "None recorded" }}${diagnosticTime(diagnostics.lastAcceptedEpochMs)}",
                    maxLines = 4,
                )
                XdmMetadataText(
                    "Last rejected link: ${diagnostics.lastRejectedSummary.ifBlank { "None recorded" }}${diagnosticTime(diagnostics.lastRejectedEpochMs)}",
                    maxLines = 4,
                )
                XdmMetadataText(
                    "Last generation: ${diagnostics.lastGenerationPhase} • ${diagnostics.lastGenerationMessage.ifBlank { "No result recorded" }}${diagnosticTime(diagnostics.lastGenerationEpochMs)}",
                    maxLines = 5,
                )
                XdmMetadataText(
                    "Detector build ${health.detectorVersion} • contract ${health.contractVersion} • body inspection cap ${BrowserExtensionSourceContract.BodyInspectionLimitBytes / 1024} KiB",
                    maxLines = 3,
                )
                XdmActionFlowRow {
                    TextButton(
                        onClick = {
                            copyTextToClipboard(
                                context,
                                "XDM Browser Bridge diagnostics",
                                health.redactedReport(diagnostics),
                            )
                        },
                    ) { Text("Copy diagnostics") }
                }
            }
        }
    }
}

private fun exportFolderSummary(uri: String): String = when {
    uri.isBlank() -> "No folder selected. Android will grant XDM persistent access to the folder you choose."
    uri.startsWith("content://") -> "Selected Android document-tree folder • ${uri.substringAfterLast('/').take(64)}"
    else -> "Configured export folder"
}

private fun formatByteCount(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes bytes"
}

private fun formatExportTime(epochMs: Long): String = if (epochMs <= 0L) {
    "Unknown time"
} else {
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochMs))
}

private fun diagnosticTime(epochMs: Long): String = if (epochMs <= 0L) "" else " • ${formatExportTime(epochMs)}"
