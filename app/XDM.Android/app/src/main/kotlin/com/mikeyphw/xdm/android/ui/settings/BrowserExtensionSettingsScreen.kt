package com.mikeyphw.xdm.android

import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
    var showTechnicalDetails by remember { mutableStateOf(false) }
    val exportFolderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.toString()?.let(viewModel::registerBrowserExtensionExportDirectory)
    }
    val setupInstructions = browserBridgeIronFoxInstructions(BuildConfig.XDM_BROWSER_SCHEME)

    LaunchedEffect(Unit) { viewModel.refreshBrowserExtensionStatus() }
    LaunchedEffect(
        preferences.autoRegenerateOnThemeChange,
        themeStale,
        state.themeMode,
        preferences.lastExportFileName,
        preferences.exportTreeUri,
    ) {
        if (
            preferences.autoRegenerateOnThemeChange &&
            themeStale &&
            preferences.lastExportFileName.isNotBlank() &&
            preferences.exportTreeUri.isNotBlank() &&
            runtime.phase != BrowserExtensionExportPhase.Exporting
        ) {
            viewModel.generateBrowserExtensionXpi()
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { SettingsPageHeader("Browser integration", { viewModel.selectSettingsPanel(SettingsPanel.Overview) }) }
        item {
            XdmListCard {
                XdmCardTitle("Firefox extension")
                XdmStatusBadge(
                    text = if (health.isReady) "Connected" else "Needs action",
                    tone = if (health.isReady) XdmStatusTone.Success else XdmStatusTone.Warning,
                )
                XdmSupportingText(
                    if (health.isReady) {
                        "XDM can receive supported browser handoffs. Use Test connection after changing Firefox or extension settings."
                    } else {
                        "Browser handoff is not fully ready yet. XDM can test the bridge and help prepare a verified extension package."
                    },
                    maxLines = 4,
                )
                XdmActionFlowRow {
                    Button(onClick = viewModel::refreshBrowserExtensionStatus) { Text("Test connection") }
                    if (health.canOpenExport) {
                        Button(onClick = viewModel::openBrowserExtensionXpi) { Text("Install / Update") }
                    }
                    TextButton(onClick = { showTechnicalDetails = !showTechnicalDetails }) {
                        Text(if (showTechnicalDetails) "Hide technical details" else "Technical details")
                    }
                }
                if (showTechnicalDetails) {
                    XdmTechnicalText("Scheme registration: ${health.schemeState.displayLabel}")
                    XdmTechnicalText("Document access: ${health.safState.displayLabel}")
                    XdmTechnicalText("Scheme: ${BuildConfig.XDM_BROWSER_SCHEME}", maxLines = 2)
                    XdmTechnicalText(
                        "Extension ${BrowserExtensionSourceContract.DevelopmentVersion} • Contract ${BrowserExtensionSourceContract.ContractVersion}",
                        maxLines = 2,
                    )
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
                        "Regeneration is safe: XDM validates the replacement before promoting it and keeps the previous verified XPI if replacement fails.",
                        maxLines = 5,
                    )
                }
            }
        }
        item {
            XdmListCard {
                XdmCardTitle("Extension package folder")
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
                XdmCardTitle("Default handoff target")
                XdmSupportingText("Choose what the extension opens when it detects supported media.", maxLines = 3)
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
                    XdmStatusBadge("Update the extension package to apply this target", tone = XdmStatusTone.Warning)
                }
            }
        }
        item {
            XdmListCard {
                XdmCardTitle("Extension theme")
                XdmSupportingText(
                    "Follow app uses XDM's current palette. The preview below shows the theme that will be built into the next package.",
                    maxLines = 4,
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
                XdmListCard(compact = true) {
                    XdmMetadataText("Preview")
                    XdmCardTitle(resolvedTheme.label)
                    XdmSupportingText(
                        if (resolvedTheme.wireValue.contains("amoled", ignoreCase = true)) {
                            "Pure-black surfaces with minimal background glow."
                        } else {
                            "Dark XDM surfaces with standard tonal separation."
                        },
                        maxLines = 2,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Regenerate when app theme changes")
                        XdmSupportingText("Only applies after at least one verified package has been generated.", maxLines = 2)
                    }
                    Switch(
                        checked = preferences.autoRegenerateOnThemeChange,
                        onCheckedChange = viewModel::setBrowserExtensionAutoRegenerateOnThemeChange,
                    )
                }
                if (themeStale) {
                    XdmStatusBadge(
                        if (preferences.autoRegenerateOnThemeChange) "Theme update queued automatically" else "Package theme needs updating",
                        tone = XdmStatusTone.Warning,
                    )
                }
            }
        }
        item {
            XdmListCard {
                XdmCardTitle(if (staleReasons.isNotEmpty()) "Update extension package" else "Extension package")
                XdmSupportingText(runtime.message, maxLines = 4)
                XdmActionFlowRow {
                    Button(
                        enabled = preferences.exportTreeUri.isNotBlank() && runtime.phase != BrowserExtensionExportPhase.Exporting,
                        onClick = viewModel::generateBrowserExtensionXpi,
                    ) {
                        Text(
                            when {
                                runtime.phase == BrowserExtensionExportPhase.Exporting -> "Generating…"
                                staleReasons.isNotEmpty() && preferences.lastExportFileName.isNotBlank() -> "Regenerate"
                                else -> "Generate"
                            },
                        )
                    }
                    if (health.canOpenExport) {
                        TextButton(onClick = viewModel::openBrowserExtensionXpi) { Text("Install / Update") }
                    }
                }
            }
        }
        if (preferences.lastExportFileName.isNotBlank()) {
            item {
                XdmListCard {
                    XdmCardTitle("Verified extension package")
                    XdmStatusBadge("Verified", tone = XdmStatusTone.Success)
                    XdmSupportingText(preferences.lastExportFileName, maxLines = 2)
                    XdmMetadataText("${formatByteCount(preferences.lastExportByteCount)} • ${formatExportTime(preferences.lastExportEpochMs)}")
                    if (showTechnicalDetails) {
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
        }
        item {
            XdmListCard {
                XdmCardTitle("Firefox / IronFox setup")
                XdmSupportingText(
                    "Install or update the verified XPI, allow supported links to open in apps, then use Test connection above.",
                    maxLines = 4,
                )
                XdmActionFlowRow {
                    TextButton(onClick = { copyTextToClipboard(context, "XDM Firefox setup", setupInstructions) }) {
                        Text("Copy setup instructions")
                    }
                }
            }
        }
        if (showTechnicalDetails) {
            item {
                XdmListCard {
                    XdmCardTitle("Redacted diagnostics")
                    XdmTechnicalText(
                        "Last accepted link: ${diagnostics.lastAcceptedSummary.ifBlank { "None recorded" }}${diagnosticTime(diagnostics.lastAcceptedEpochMs)}",
                        maxLines = 4,
                    )
                    XdmTechnicalText(
                        "Last rejected link: ${diagnostics.lastRejectedSummary.ifBlank { "None recorded" }}${diagnosticTime(diagnostics.lastRejectedEpochMs)}",
                        maxLines = 4,
                    )
                    XdmTechnicalText(
                        "Last generation: ${diagnostics.lastGenerationPhase} • ${diagnostics.lastGenerationMessage.ifBlank { "No result recorded" }}${diagnosticTime(diagnostics.lastGenerationEpochMs)}",
                        maxLines = 5,
                    )
                    XdmTechnicalText(
                        "Detector build ${health.detectorVersion} • contract ${health.contractVersion} • inspection cap ${BrowserExtensionSourceContract.BodyInspectionLimitBytes / 1024} KiB",
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
}

private fun exportFolderSummary(uri: String): String {
    if (uri.isBlank()) return "No folder selected. Android will grant XDM persistent access to the folder you choose."
    if (!uri.startsWith("content://")) return "Configured extension package folder"
    return runCatching {
        val parsed = Uri.parse(uri)
        val documentId = DocumentsContract.getTreeDocumentId(parsed)
        val decoded = Uri.decode(documentId)
        val parts = decoded.split(':', limit = 2)
        val volume = when (parts.firstOrNull()?.lowercase()) {
            "primary" -> "Internal storage"
            null, "" -> "Android storage"
            else -> parts.first()
        }
        val relative = parts.getOrNull(1).orEmpty().trim('/')
        if (relative.isBlank()) volume else "$volume/$relative"
    }.getOrElse { "Selected Android folder" }
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
