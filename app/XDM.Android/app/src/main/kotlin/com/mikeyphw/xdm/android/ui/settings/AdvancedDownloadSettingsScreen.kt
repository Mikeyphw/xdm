package com.mikeyphw.xdm.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.mikeyphw.xdm.android.model.ConversionPreset
import com.mikeyphw.xdm.android.model.DestinationRuleMatch
import com.mikeyphw.xdm.android.model.DuplicateUrlAction
import com.mikeyphw.xdm.android.model.PostProcessingSettings
import com.mikeyphw.xdm.android.model.ProxyCredentialSettings
import com.mikeyphw.xdm.android.model.displayName

@Composable
@UiSurface(UiAudience.Advanced, "Configure advanced download rules and optional integrations")
internal fun AdvancedDownloadSettingsScreen(state: MainUiState, viewModel: MainViewModel) {
    val context = LocalContext.current
    var importText by remember { mutableStateOf("") }
    var destinationRuleName by remember { mutableStateOf("") }
    var destinationRulePattern by remember { mutableStateOf("") }
    var destinationRuleMatch by remember { mutableStateOf(DestinationRuleMatch.Host) }
    var duplicateHost by remember { mutableStateOf("") }
    var duplicateAction by remember { mutableStateOf(DuplicateUrlAction.OpenExisting) }

    var proxyEnabled by remember(state.proxySettings) { mutableStateOf(state.proxySettings.enabled) }
    var proxyHost by remember(state.proxySettings) { mutableStateOf(state.proxySettings.host) }
    var proxyPort by remember(state.proxySettings) { mutableStateOf(state.proxySettings.port?.toString().orEmpty()) }
    var proxyUsername by remember(state.proxySettings) { mutableStateOf(state.proxySettings.username) }
    var proxyAlias by remember(state.proxySettings) { mutableStateOf(state.proxySettings.credentialAlias) }
    val proxyDraft = ProxyCredentialSettings(
        enabled = proxyEnabled,
        host = proxyHost,
        port = proxyPort.toIntOrNull()?.takeIf { it in 1..65535 },
        username = proxyUsername,
        credentialAlias = proxyAlias,
    )
    val proxyDirty = proxyDraft != state.proxySettings
    val proxyPortValid = proxyPort.isBlank() || proxyPort.toIntOrNull()?.let { it in 1..65535 } == true

    var postEnabled by remember(state.postProcessingSettings) { mutableStateOf(state.postProcessingSettings.enabled) }
    var postPreset by remember(state.postProcessingSettings) { mutableStateOf(state.postProcessingSettings.preset) }
    var postLabel by remember(state.postProcessingSettings) { mutableStateOf(state.postProcessingSettings.customCommandLabel) }
    val postDraft = PostProcessingSettings(postEnabled, postPreset, postLabel)
    val postDirty = postDraft != state.postProcessingSettings

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { SettingsPageHeader("Advanced download rules", { viewModel.selectSettingsPanel(SettingsPanel.Overview) }) }

        item { XdmSectionHeader("Destination and duplicate rules") }
        item {
            XdmListCard {
                XdmCardTitle("Destination rules")
                XdmSupportingText("Route new downloads by host, extension, MIME type, or fallback before they enter the queue.", maxLines = 3)
                XdmActionFlowRow {
                    DestinationRuleMatch.entries.forEach { match ->
                        FilterChip(
                            selected = destinationRuleMatch == match,
                            onClick = { destinationRuleMatch = match },
                            label = { Text(humanizeAdvancedName(match.name)) },
                        )
                    }
                }
                OutlinedTextField(destinationRuleName, { destinationRuleName = it }, label = { Text("Rule name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(destinationRulePattern, { destinationRulePattern = it }, label = { Text("Pattern") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Button(
                    onClick = {
                        viewModel.saveDestinationRule(destinationRuleName, destinationRuleMatch, destinationRulePattern, state.destinationUri)
                        destinationRuleName = ""
                        destinationRulePattern = ""
                    },
                    enabled = destinationRuleName.isNotBlank() && destinationRulePattern.isNotBlank(),
                ) { Text("Save destination rule") }
                state.destinationRules.take(4).forEach { rule ->
                    XdmMetadataText("${rule.name}: ${humanizeAdvancedName(rule.match.name)} ${rule.pattern}", maxLines = 2)
                }
            }
        }
        item {
            XdmListCard {
                XdmCardTitle("Duplicate URL rules")
                XdmSupportingText("Choose what XDM should do when a source URL is already in the download history.", maxLines = 3)
                OutlinedTextField(duplicateHost, { duplicateHost = it }, label = { Text("Host pattern") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                XdmActionFlowRow {
                    DuplicateUrlAction.entries.forEach { action ->
                        FilterChip(
                            selected = duplicateAction == action,
                            onClick = { duplicateAction = action },
                            label = { Text(humanizeAdvancedName(action.name)) },
                        )
                    }
                }
                Button(
                    onClick = {
                        viewModel.saveDuplicateRule(duplicateHost, duplicateAction)
                        duplicateHost = ""
                    },
                    enabled = duplicateHost.isNotBlank(),
                ) { Text("Save duplicate rule") }
                state.duplicateRules.take(4).forEach { rule ->
                    XdmMetadataText("${rule.hostPattern}: ${humanizeAdvancedName(rule.action.name)}")
                }
            }
        }

        item { XdmSectionHeader("Proxy profile") }
        item {
            XdmListCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        XdmCardTitle("Proxy and credentials")
                        XdmMetadataText(state.proxySettings.redactedSummary)
                    }
                    StatusPill(if (proxyDirty) "Unsaved" else "Saved", if (proxyDirty) XdmStatusTone.Warning else XdmStatusTone.Success)
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    XdmSupportingText("Use proxy", modifier = Modifier.weight(1f))
                    Switch(checked = proxyEnabled, onCheckedChange = { proxyEnabled = it })
                }
                OutlinedTextField(proxyHost, { proxyHost = it }, label = { Text("Host") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(
                    value = proxyPort,
                    onValueChange = { proxyPort = it.filter(Char::isDigit).take(5) },
                    label = { Text("Port") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = !proxyPortValid,
                    supportingText = { Text(if (proxyPortValid) "Optional. Use 1–65535." else "Port must be between 1 and 65535.") },
                )
                OutlinedTextField(proxyUsername, { proxyUsername = it }, label = { Text("Username") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(proxyAlias, { proxyAlias = it }, label = { Text("Credential alias") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                XdmActionFlowRow {
                    Button(onClick = { viewModel.setProxySettings(proxyDraft) }, enabled = proxyDirty && proxyPortValid) { Text("Save proxy profile") }
                    if (proxyDirty) {
                        TextButton(onClick = {
                            proxyEnabled = state.proxySettings.enabled
                            proxyHost = state.proxySettings.host
                            proxyPort = state.proxySettings.port?.toString().orEmpty()
                            proxyUsername = state.proxySettings.username
                            proxyAlias = state.proxySettings.credentialAlias
                        }) { Text("Reset") }
                    }
                }
            }
        }

        item { XdmSectionHeader("Termux integration") }
        item {
            TermuxBridgeSettingsCard(
                termux = state.termuxBridge,
                onRunProbe = viewModel::runTermuxToolProbe,
                onOpenTermux = viewModel::openTermux,
                onRootModeChanged = viewModel::setTermuxRootMode,
                onRunRootProbe = viewModel::runTermuxRootProbe,
                onCollectRootDiagnostics = viewModel::collectTermuxRootProcessDiagnostics,
                onKillStuckAria2WithRoot = viewModel::killStuckTermuxAria2WithRoot,
                onFixDownloadPermissionsWithRoot = viewModel::fixTermuxDownloadPermissionsWithRoot,
            )
        }
        item {
            TermuxAria2SettingsCard(
                aria2 = state.termuxAria2,
                onEnabledChanged = viewModel::setTermuxAria2Enabled,
                onRotateSecret = viewModel::rotateTermuxAria2Secret,
            )
        }

        item { XdmSectionHeader("Conversion and post-processing") }
        item {
            PostProcessingAutomationCard(
                automation = state.postProcessingAutomation,
                onEnabledChanged = viewModel::setPostProcessingAutomationEnabled,
                onRetryFailed = viewModel::retryFailedPostProcessing,
                onClearEvents = viewModel::clearPostProcessingEvents,
            )
        }
        item {
            XdmListCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        XdmCardTitle("Post-processing hook")
                        XdmMetadataText(state.postProcessingSettings.redactedSummary)
                    }
                    StatusPill(if (postDirty) "Unsaved" else "Saved", if (postDirty) XdmStatusTone.Warning else XdmStatusTone.Success)
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    XdmSupportingText("Run after completion", modifier = Modifier.weight(1f))
                    Switch(checked = postEnabled, onCheckedChange = { postEnabled = it })
                }
                XdmActionFlowRow {
                    ConversionPreset.entries.forEach { preset ->
                        FilterChip(selected = postPreset == preset, onClick = { postPreset = preset }, label = { Text(preset.displayName()) })
                    }
                }
                OutlinedTextField(postLabel, { postLabel = it }, label = { Text("Custom label") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                XdmActionFlowRow {
                    Button(onClick = { viewModel.setPostProcessingSettings(postDraft) }, enabled = postDirty) { Text("Save post-processing") }
                    if (postDirty) {
                        TextButton(onClick = {
                            postEnabled = state.postProcessingSettings.enabled
                            postPreset = state.postProcessingSettings.preset
                            postLabel = state.postProcessingSettings.customCommandLabel
                        }) { Text("Reset") }
                    }
                }
            }
        }

        item { XdmSectionHeader("Settings import/export") }
        item {
            XdmListCard {
                XdmCardTitle("Portable settings snapshot")
                XdmSupportingText("Copy a safe backup or paste one here. Passwords and developer options are not exported.", maxLines = 3)
                XdmMetadataText(state.backupRestoreReport.summary)
                Button(onClick = { copyTextToClipboard(context, "XDM settings snapshot", state.settingsExportText) }) { Text("Copy export") }
                OutlinedTextField(
                    value = importText,
                    onValueChange = { importText = it },
                    label = { Text("Paste settings snapshot") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 6,
                )
                XdmActionFlowRow {
                    Button(
                        onClick = {
                            viewModel.importSettingsSnapshot(importText)
                            importText = ""
                        },
                        enabled = importText.isNotBlank(),
                    ) { Text("Import snapshot") }
                    if (importText.isNotBlank()) TextButton(onClick = { importText = "" }) { Text("Clear") }
                }
            }
        }
    }
}

private fun humanizeAdvancedName(value: String): String = value
    .replace(Regex("([a-z])([A-Z])"), "$1 $2")
    .replace('_', ' ')
    .lowercase()
    .replaceFirstChar(Char::titlecase)
