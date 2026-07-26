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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mikeyphw.xdm.android.browserextension.BrowserExtensionSourceContract
import java.text.DateFormat
import java.util.Date

@Composable
internal fun BrowserExtensionSettingsScreen(
    state: MainUiState,
    viewModel: MainViewModel,
) {
    val preferences = state.browserExtension
    val runtime = state.browserExtensionRuntime
    val exportFolderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.toString()?.let(viewModel::registerBrowserExtensionExportDirectory)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { SettingsPageHeader("Browser extension", { viewModel.selectSettingsPanel(SettingsPanel.Overview) }) }
        item {
            XdmListCard {
                XdmCardTitle("Firefox bridge package")
                XdmSupportingText(
                    "XDM generates the repository-owned Firefox extension with this app variant's scheme and your selected target. The XPI is validated and checksum-verified before export.",
                    maxLines = 5,
                )
                XdmMetadataText("Extension ${BrowserExtensionSourceContract.DevelopmentVersion} • Contract ${BrowserExtensionSourceContract.ContractVersion}")
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
                }
            }
        }
        item {
            XdmListCard {
                XdmCardTitle("Default target")
                XdmSupportingText("The extension can open detected media in XDM, 1DM+, or ask on each page.", maxLines = 3)
                XdmActionFlowRow {
                    BrowserExtensionSourceContract.Target.entries.forEach { target ->
                        FilterChip(
                            selected = preferences.defaultTarget == target,
                            onClick = { viewModel.setBrowserExtensionDefaultTarget(target) },
                            label = { Text(target.label) },
                        )
                    }
                }
            }
        }
        item {
            XdmListCard {
                XdmCardTitle("Generated theme")
                XdmSupportingText("Phase 39 packages Dark and AMOLED palettes. Phase 40 will move these colors to the shared XDM theme-token contract.", maxLines = 4)
                XdmActionFlowRow {
                    BrowserExtensionSourceContract.ThemeMode.entries.forEach { theme ->
                        FilterChip(
                            selected = preferences.requestedTheme == theme,
                            onClick = { viewModel.setBrowserExtensionTheme(theme) },
                            label = { Text(theme.label) },
                        )
                    }
                }
            }
        }
        item {
            XdmListCard {
                XdmCardTitle("Generate XPI")
                XdmSupportingText(runtime.message, maxLines = 4)
                XdmActionFlowRow {
                    Button(
                        enabled = preferences.exportTreeUri.isNotBlank() && runtime.phase != BrowserExtensionExportPhase.Exporting,
                        onClick = viewModel::generateBrowserExtensionXpi,
                    ) {
                        Text(if (runtime.phase == BrowserExtensionExportPhase.Exporting) "Generating…" else "Generate XPI")
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
                        "App ${preferences.lastExportAppVersion} • Extension ${preferences.lastExportExtensionVersion} • ${preferences.lastExportTheme?.label ?: "Unknown theme"}",
                        maxLines = 3,
                    )
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
