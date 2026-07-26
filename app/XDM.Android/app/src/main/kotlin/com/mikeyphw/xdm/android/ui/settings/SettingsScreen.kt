package com.mikeyphw.xdm.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.mikeyphw.xdm.android.model.BackendRecommendation
import com.mikeyphw.xdm.android.model.BackendCapabilityRow
import com.mikeyphw.xdm.android.model.BackendMigrationRecord
import com.mikeyphw.xdm.android.model.BackupRestoreReport
import com.mikeyphw.xdm.android.model.BrowserIntegrationStatus
import com.mikeyphw.xdm.android.model.ChecksumAlgorithm
import com.mikeyphw.xdm.android.model.ChecksumResult
import com.mikeyphw.xdm.android.model.ClipboardInboxItem
import com.mikeyphw.xdm.android.model.VerificationRecord
import com.mikeyphw.xdm.android.model.VerificationStatus
import com.mikeyphw.xdm.android.model.BackendType
import com.mikeyphw.xdm.android.model.Download
import com.mikeyphw.xdm.android.model.DownloadIntakeKind
import com.mikeyphw.xdm.android.model.ConversionPreset
import com.mikeyphw.xdm.android.model.DestinationRule
import com.mikeyphw.xdm.android.model.DestinationRuleMatch
import com.mikeyphw.xdm.android.model.DownloadTag
import com.mikeyphw.xdm.android.model.DownloadTagAssignment
import com.mikeyphw.xdm.android.model.DownloadState
import com.mikeyphw.xdm.android.model.DownloadDashboard
import com.mikeyphw.xdm.android.model.DownloadDashboardOrdering
import com.mikeyphw.xdm.android.model.DownloadDashboardPlanner
import com.mikeyphw.xdm.android.model.DownloadDashboardSection
import com.mikeyphw.xdm.android.model.DownloadReviewPlanner
import com.mikeyphw.xdm.android.model.DownloadReviewReadiness
import com.mikeyphw.xdm.android.model.ExternalUrlPolicy
import com.mikeyphw.xdm.android.model.DuplicateUrlAction
import com.mikeyphw.xdm.android.model.DuplicateUrlRule
import com.mikeyphw.xdm.android.model.HistoryManagementPolicy
import com.mikeyphw.xdm.android.model.HistoryManagementReport
import com.mikeyphw.xdm.android.model.OrganizationPowerToolsReport
import com.mikeyphw.xdm.android.model.OperationalActivitySummary
import com.mikeyphw.xdm.android.model.PostProcessingSettings
import com.mikeyphw.xdm.android.model.ProtocolExpansionReport
import com.mikeyphw.xdm.android.model.ProxyCredentialSettings
import com.mikeyphw.xdm.android.model.ReleasePackagingReport
import com.mikeyphw.xdm.android.model.displayName
import com.mikeyphw.xdm.android.model.DestinationPermission
import com.mikeyphw.xdm.android.model.FilenameConflictPolicy
import com.mikeyphw.xdm.android.model.FinalizationJournal
import com.mikeyphw.xdm.android.model.AutomationCommandStatus
import com.mikeyphw.xdm.android.model.MediaCaptureStatus
import com.mikeyphw.xdm.android.model.MediaCaptureRecord
import com.mikeyphw.xdm.android.model.MediaResolutionStatus
import com.mikeyphw.xdm.android.model.MediaVariant
import com.mikeyphw.xdm.android.model.MediaVariantKind
import com.mikeyphw.xdm.android.media.MediaDownloadPlanner
import com.mikeyphw.xdm.android.media.MediaDownloadPlan
import com.mikeyphw.xdm.android.media.MediaTrackSelection
import com.mikeyphw.xdm.android.media.MediaResolverWorkspace
import com.mikeyphw.xdm.android.media.MediaResolverWorkspacePlanner
import com.mikeyphw.xdm.android.media.MediaResolverStage
import com.mikeyphw.xdm.android.media.MediaResolverHistoryRow
import com.mikeyphw.xdm.android.media.MediaResolverFormatRow
import com.mikeyphw.xdm.android.media.MediaResolverTrackRow
import com.mikeyphw.xdm.android.media.MediaVariantPickerGroup
import com.mikeyphw.xdm.android.media.YtDlpMetadataProbeResult
import com.mikeyphw.xdm.android.media.OfflineMediaLibrarySummary
import com.mikeyphw.xdm.android.media.MediaDownloadStrategy
import com.mikeyphw.xdm.android.media.MediaExecutionLibraryPlanner
import com.mikeyphw.xdm.android.media.MediaExecutionJob
import com.mikeyphw.xdm.android.media.MediaExecutionStage
import com.mikeyphw.xdm.android.media.MediaExternalJobSnapshot
import com.mikeyphw.xdm.android.media.MediaExecutionEnginePlan
import com.mikeyphw.xdm.android.media.MediaDispatchDashboard
import com.mikeyphw.xdm.android.media.MediaDispatchPlan
import com.mikeyphw.xdm.android.media.MediaDispatchReadiness
import com.mikeyphw.xdm.android.media.MediaExecutionDispatcher
import com.mikeyphw.xdm.android.media.MediaQueueTelemetryDeck
import com.mikeyphw.xdm.android.media.MediaQueueTelemetryPlanner
import com.mikeyphw.xdm.android.media.MediaQueueTelemetryTone
import com.mikeyphw.xdm.android.media.MediaQueueActionAvailability
import com.mikeyphw.xdm.android.media.MediaQueueActionDashboard
import com.mikeyphw.xdm.android.media.MediaQueueActionKind
import com.mikeyphw.xdm.android.media.MediaQueueActionPlan
import com.mikeyphw.xdm.android.media.MediaQueueActionPlanner
import com.mikeyphw.xdm.android.media.MediaWorkerBridgeDashboard
import com.mikeyphw.xdm.android.media.MediaWorkerBridgeKind
import com.mikeyphw.xdm.android.media.MediaWorkerBridgePlanner
import com.mikeyphw.xdm.android.media.MediaWorkerBridgeReadiness
import com.mikeyphw.xdm.android.media.MediaWorkerBridgeRequest
import com.mikeyphw.xdm.android.media.MediaTermuxRuntimeAdapter
import com.mikeyphw.xdm.android.media.TermuxRuntimeDashboard
import com.mikeyphw.xdm.android.media.TermuxRuntimeLaunchPlan
import com.mikeyphw.xdm.android.media.MediaNativeDirectDownloadPlanner
import com.mikeyphw.xdm.android.media.NativeDirectDashboard
import com.mikeyphw.xdm.android.media.NativeDirectDownloadRequestPlan
import com.mikeyphw.xdm.android.media.OfflineMediaLibraryItem
import com.mikeyphw.xdm.android.media.MediaOfflineLibraryV2Planner
import com.mikeyphw.xdm.android.media.OfflineLibraryV2Dashboard
import com.mikeyphw.xdm.android.media.OfflineLibraryV2Filter
import com.mikeyphw.xdm.android.media.OfflineLibraryV2Health
import com.mikeyphw.xdm.android.media.MediaPlayerDiagnosticsPlanner
import com.mikeyphw.xdm.android.media.MediaPlayerDiagnosticBucket
import com.mikeyphw.xdm.android.media.MediaPlayerDiagnosticReport
import com.mikeyphw.xdm.android.media.MediaCaptureQualityPlanner
import com.mikeyphw.xdm.android.media.MediaCaptureQualityDashboard
import com.mikeyphw.xdm.android.media.CaptureQualityDisposition
import com.mikeyphw.xdm.android.media.MediaSessionPrivacyAuditPlanner
import com.mikeyphw.xdm.android.media.MediaSessionPrivacyAuditDashboard
import com.mikeyphw.xdm.android.media.MediaPrivacySeverity
import com.mikeyphw.xdm.android.media.MediaMobilePolishPlanner
import com.mikeyphw.xdm.android.media.MediaMobilePolishDashboard
import com.mikeyphw.xdm.android.media.MediaMobileSectionPriority
import com.mikeyphw.xdm.android.media.MediaMobilePolishSignal
import com.mikeyphw.xdm.android.media.MediaFinalValidationGatePlanner
import com.mikeyphw.xdm.android.media.MediaFinalValidationDashboard
import com.mikeyphw.xdm.android.media.MediaFinalValidationSeverity
import com.mikeyphw.xdm.android.storage.DestinationCatalog
import com.mikeyphw.xdm.android.model.QueueDefinition
import com.mikeyphw.xdm.android.model.QueueIntelligenceSummary
import com.mikeyphw.xdm.android.model.QueueNetworkRequirement
import com.mikeyphw.xdm.android.model.QueueRetryStrategy
import com.mikeyphw.xdm.android.model.RecoveryAction
import com.mikeyphw.xdm.android.model.RecoveryClassification
import com.mikeyphw.xdm.android.model.RecoveryRecord
import com.mikeyphw.xdm.android.model.ScheduleRule
import com.mikeyphw.xdm.android.model.SavedSearch
import com.mikeyphw.xdm.android.scheduler.ActiveTransferSummary
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.LocalContext
import com.mikeyphw.xdm.android.model.PrivacyDiagnosticsRedactor
import com.mikeyphw.xdm.android.model.redactedDiagnosticLine
import com.mikeyphw.xdm.android.model.ReleaseReadinessSeverity
import com.mikeyphw.xdm.android.model.FinalReleaseGateSeverity
import com.mikeyphw.xdm.android.model.ReleaseSecuritySeverity
import com.mikeyphw.xdm.android.util.formatBytes
import com.mikeyphw.xdm.android.util.formatSpeed
import com.mikeyphw.xdm.android.termux.TermuxRootMode
import com.mikeyphw.xdm.android.termux.TermuxBridgeStatus
import com.mikeyphw.xdm.android.termux.TermuxAria2CockpitStatus
import com.mikeyphw.xdm.android.termux.TermuxAria2DaemonState
import com.mikeyphw.xdm.android.termux.TermuxMediaJobStatus
import com.mikeyphw.xdm.android.termux.TermuxMediaPipelineStatus
import com.mikeyphw.xdm.android.termux.PostProcessingAutomationStatus
import com.mikeyphw.xdm.android.termux.PostProcessingAutomationEventStatus



@Composable
@UiSurface(UiAudience.User, "Configure app behavior and integrations")
fun SettingsScreen(
    compact: Boolean,
    capabilities: List<BackendCapabilityRow>,
    migrations: List<BackendMigrationRecord>,
    installUpdateReadinessReport: com.mikeyphw.xdm.android.model.InstallUpdateReadinessReport,
    finalReleaseGateReport: com.mikeyphw.xdm.android.model.FinalReleaseGateReport,
    proxySettings: ProxyCredentialSettings,
    postProcessingSettings: PostProcessingSettings,
    settingsExportText: String,
    backupRestoreReport: BackupRestoreReport,
    destinationRules: List<DestinationRule>,
    duplicateRules: List<DuplicateUrlRule>,
    protocolExpansionReport: ProtocolExpansionReport,
    releasePackagingReport: ReleasePackagingReport,
    termuxBridge: TermuxBridgeStatus,
    termuxAria2: TermuxAria2CockpitStatus,
    postProcessingAutomation: PostProcessingAutomationStatus,
    onCompactChanged: (Boolean) -> Unit,
    onProxyChanged: (ProxyCredentialSettings) -> Unit,
    onPostProcessingChanged: (PostProcessingSettings) -> Unit,
    onImportSettings: (String) -> Unit,
    onSaveDestinationRule: (String, DestinationRuleMatch, String, String) -> Unit,
    onSaveDuplicateRule: (String, DuplicateUrlAction) -> Unit,
    onRunTermuxProbe: () -> Unit,
    onOpenTermux: () -> Unit,
    onRootModeChanged: (TermuxRootMode) -> Unit,
    onRunTermuxRootProbe: () -> Unit,
    onCollectRootDiagnostics: () -> Unit,
    onKillStuckAria2WithRoot: () -> Unit,
    onFixDownloadPermissionsWithRoot: () -> Unit,
    onTermuxAria2EnabledChanged: (Boolean) -> Unit,
    onRotateTermuxAria2Secret: () -> Unit,
    onPostProcessingAutomationEnabledChanged: (Boolean) -> Unit,
    onRetryPostProcessing: () -> Unit,
    onClearPostProcessingEvents: () -> Unit,
) {
    val context = LocalContext.current
    var importText by remember { mutableStateOf("") }
    var proxyEnabled by remember(proxySettings) { mutableStateOf(proxySettings.enabled) }
    var proxyHost by remember(proxySettings) { mutableStateOf(proxySettings.host) }
    var proxyPort by remember(proxySettings) { mutableStateOf(proxySettings.port?.toString().orEmpty()) }
    var proxyUsername by remember(proxySettings) { mutableStateOf(proxySettings.username) }
    var proxyAlias by remember(proxySettings) { mutableStateOf(proxySettings.credentialAlias) }
    var postEnabled by remember(postProcessingSettings) { mutableStateOf(postProcessingSettings.enabled) }
    var postPreset by remember(postProcessingSettings) { mutableStateOf(postProcessingSettings.preset) }
    var postLabel by remember(postProcessingSettings) { mutableStateOf(postProcessingSettings.customCommandLabel) }
    var destinationRuleName by remember { mutableStateOf("") }
    var destinationRulePattern by remember { mutableStateOf("") }
    var destinationRuleMatch by remember { mutableStateOf(DestinationRuleMatch.Host) }
    var duplicateHost by remember { mutableStateOf("") }
    var duplicateAction by remember { mutableStateOf(DuplicateUrlAction.OpenExisting) }
    val proxyDraft = ProxyCredentialSettings(proxyEnabled, proxyHost, proxyPort.toIntOrNull()?.takeIf { it in 1..65535 }, proxyUsername, proxyAlias)
    val proxyDirty = proxyDraft != proxySettings
    val proxyPortValid = proxyPort.isBlank() || proxyPort.toIntOrNull()?.let { it in 1..65535 } == true
    val postDraft = PostProcessingSettings(postEnabled, postPreset, postLabel)
    val postDirty = postDraft != postProcessingSettings

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { XdmSectionHeader("Appearance") }
        item {
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        XdmCardTitle("Compact download cards")
                        XdmMetadataText("Reduce vertical spacing in the download list.")
                    }
                    Switch(
                        checked = compact,
                        onCheckedChange = onCompactChanged,
                        modifier = Modifier.semantics { stateDescription = if (compact) "Compact cards enabled" else "Compact cards disabled" },
                    )
                }
            }
        }
        item { XdmSectionHeader("Settings import/export") }
        item {
            Card(Modifier.fillMaxWidth().semantics { contentDescription = "Settings import export snapshot" }) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    XdmCardTitle("Portable settings snapshot")
                    XdmSupportingText("Copy a safe backup, paste one back here, and review whether it looks ready before importing.")
                    XdmMetadataText(backupRestoreReport.summary)
                    XdmActionFlowRow {
                        TextButton(onClick = { copyTextToClipboard(context, "XDM settings snapshot", settingsExportText) }) { Text("Copy export") }
                        StatusPill(if (importText.isBlank()) "No import" else "Import ready", if (importText.isBlank()) XdmStatusTone.Neutral else XdmStatusTone.Info)
                    }
                    OutlinedTextField(
                        importText,
                        { importText = it },
                        label = { Text("Paste settings snapshot") },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 6,
                        supportingText = { Text("Secrets are not included in exported snapshots.") },
                    )
                    XdmActionFlowRow {
                        Button(onClick = { onImportSettings(importText); importText = "" }, enabled = importText.isNotBlank()) { Text("Import snapshot") }
                        if (importText.isNotBlank()) {
                            TextButton(onClick = { importText = "" }) { Text("Clear") }
                        }
                    }
                }
            }
        }
        item { XdmSectionHeader("Rules and restore hardening") }
        item {
            Card(Modifier.fillMaxWidth().semantics { contentDescription = "Destination rules ${destinationRules.size}" }) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    XdmCardTitle("Destination rules")
                    XdmSupportingText("Route new downloads by host, extension, MIME type, or fallback destination before the download is queued.")
                    XdmActionFlowRow {
                        DestinationRuleMatch.entries.forEach { match ->
                            FilterChip(selected = destinationRuleMatch == match, onClick = { destinationRuleMatch = match }, label = { Text(match.name.lowercase().replaceFirstChar { it.titlecase() }) })
                        }
                    }
                    OutlinedTextField(destinationRuleName, { destinationRuleName = it }, label = { Text("Rule name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(destinationRulePattern, { destinationRulePattern = it }, label = { Text("Pattern") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Button(
                        onClick = {
                            onSaveDestinationRule(destinationRuleName, destinationRuleMatch, destinationRulePattern, settingsExportText.lineSequence().firstOrNull { it.startsWith("destinationUri=") }?.substringAfter('=').orEmpty())
                            destinationRuleName = ""
                            destinationRulePattern = ""
                        },
                        enabled = destinationRuleName.isNotBlank() && destinationRulePattern.isNotBlank(),
                    ) { Text("Save destination rule") }
                    destinationRules.take(4).forEach { rule -> XdmMetadataText("${rule.name}: ${rule.match.name} ${rule.pattern} → ${rule.destinationUri}", maxLines = 2) }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth().semantics { contentDescription = "Duplicate URL rules ${duplicateRules.size}" }) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    XdmCardTitle("Duplicate URL rules")
                    XdmSupportingText("Detect repeated source URLs before enqueueing and prefer opening the existing record by default.")
                    OutlinedTextField(duplicateHost, { duplicateHost = it }, label = { Text("Host pattern") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    XdmActionFlowRow {
                        DuplicateUrlAction.entries.forEach { action ->
                            FilterChip(selected = duplicateAction == action, onClick = { duplicateAction = action }, label = { Text(action.name.replace(Regex("([a-z])([A-Z])"), "$1 $2")) })
                        }
                    }
                    Button(onClick = { onSaveDuplicateRule(duplicateHost, duplicateAction); duplicateHost = "" }, enabled = duplicateHost.isNotBlank()) { Text("Save duplicate rule") }
                    duplicateRules.take(4).forEach { rule -> XdmMetadataText("${rule.hostPattern}: ${rule.action.name} (${if (rule.enabled) "enabled" else "disabled"})") }
                }
            }
        }
        item { XdmSectionHeader("Proxy and credentials") }
        item {
            Card(Modifier.fillMaxWidth().semantics { contentDescription = "Proxy credential profile ${proxySettings.redactedSummary}" }) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            XdmCardTitle("Proxy profile")
                            XdmMetadataText(proxySettings.redactedSummary)
                        }
                        StatusPill(if (proxyDirty) "Unsaved" else "Saved", if (proxyDirty) XdmStatusTone.Warning else XdmStatusTone.Success)
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        XdmSupportingText("Use proxy", modifier = Modifier.weight(1f))
                        Switch(checked = proxyEnabled, onCheckedChange = { proxyEnabled = it })
                    }
                    OutlinedTextField(proxyHost, { proxyHost = it }, label = { Text("Host") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(
                        proxyPort,
                        { proxyPort = it.filter { char -> char.isDigit() }.take(5) },
                        label = { Text("Port") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isError = !proxyPortValid,
                        supportingText = { Text(if (proxyPortValid) "Optional. Use 1–65535." else "Port must be between 1 and 65535.") },
                    )
                    OutlinedTextField(proxyUsername, { proxyUsername = it }, label = { Text("Username") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(proxyAlias, { proxyAlias = it }, label = { Text("Credential alias") }, modifier = Modifier.fillMaxWidth(), singleLine = true, supportingText = { Text("Store a label only; passwords stay outside exported diagnostics and settings snapshots.") })
                    XdmActionFlowRow {
                        Button(onClick = { onProxyChanged(proxyDraft) }, enabled = proxyDirty && proxyPortValid) { Text("Save proxy profile") }
                        if (proxyDirty) {
                            TextButton(
                                onClick = {
                                    proxyEnabled = proxySettings.enabled
                                    proxyHost = proxySettings.host
                                    proxyPort = proxySettings.port?.toString().orEmpty()
                                    proxyUsername = proxySettings.username
                                    proxyAlias = proxySettings.credentialAlias
                                },
                            ) { Text("Reset") }
                        }
                    }
                }
            }
        }
        item { XdmSectionHeader("Termux backend") }
        item {
            TermuxBridgeSettingsCard(
                termux = termuxBridge,
                onRunProbe = onRunTermuxProbe,
                onOpenTermux = onOpenTermux,
                onRootModeChanged = onRootModeChanged,
                onRunRootProbe = onRunTermuxRootProbe,
                onCollectRootDiagnostics = onCollectRootDiagnostics,
                onKillStuckAria2WithRoot = onKillStuckAria2WithRoot,
                onFixDownloadPermissionsWithRoot = onFixDownloadPermissionsWithRoot,
            )
        }
        item { TermuxAria2SettingsCard(termuxAria2, onTermuxAria2EnabledChanged, onRotateTermuxAria2Secret) }
        item { XdmSectionHeader("Conversion and post-processing") }
        item { PostProcessingAutomationCard(postProcessingAutomation, onPostProcessingAutomationEnabledChanged, onRetryPostProcessing, onClearPostProcessingEvents) }
        item {
            Card(Modifier.fillMaxWidth().semantics { contentDescription = "Conversion post processing ${postProcessingSettings.redactedSummary}" }) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            XdmCardTitle("Post-processing hook")
                            XdmMetadataText(postProcessingSettings.redactedSummary)
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
                        Button(onClick = { onPostProcessingChanged(postDraft) }, enabled = postDirty) { Text("Save post-processing") }
                        if (postDirty) {
                            TextButton(
                                onClick = {
                                    postEnabled = postProcessingSettings.enabled
                                    postPreset = postProcessingSettings.preset
                                    postLabel = postProcessingSettings.customCommandLabel
                                },
                            ) { Text("Reset") }
                        }
                    }
                    XdmMetadataText("Conversion starts only when the selected backend supports the chosen preset.")
                }
            }
        }
        item { XdmSectionHeader("Protocol expansion") }
        item {
            Card(Modifier.fillMaxWidth().semantics { contentDescription = "Protocol expansion ${protocolExpansionReport.summary}" }) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    XdmCardTitle(protocolExpansionReport.summary)
                    protocolExpansionReport.rows.forEach { row -> XdmMetadataText("${row.protocol.uppercase()}: ${row.recommendation}") }
                }
            }
        }
        item { XdmSectionHeader("Backend strategy") }
        items(capabilities, key = { it.backend.name }) { capability ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        XdmCardTitle(capability.backend.uiLabel())
                        StatusPill(if (capability.available) "Available" else "Unavailable", if (capability.available) XdmStatusTone.Success else XdmStatusTone.Warning)
                    }
                    XdmSupportingText(capability.summary)
                    XdmMetadataText("Protocols: ${capability.protocols.sorted().joinToString().ifBlank { "None" }}")
                    Text(
                        listOfNotNull(
                            "Segments".takeIf { capability.segmentation },
                            "Mirrors".takeIf { capability.mirrors },
                            "Metalink".takeIf { capability.metalink },
                            "SAF".takeIf { capability.saf },
                            "Repair".takeIf { capability.selectiveRepair },
                            "Media".takeIf { capability.media },
                        ).joinToString(" • ").ifBlank { "No optional capabilities" },
                        style = MaterialTheme.typography.labelMedium,
                    )
                    XdmMetadataText("Diagnostics: ${capability.diagnosticDetail.uiLabel()} • Battery: ${capability.batteryImpact.uiLabel()}")
                }
            }
        }
        if (migrations.isNotEmpty()) {
            item { XdmSectionHeader("Recent backend migrations") }
            items(migrations.take(5), key = BackendMigrationRecord::id) { migration ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        XdmCardTitle("${migration.sourceBackend.uiLabel()} → ${migration.targetBackend.uiLabel()}")
                        XdmMetricText(migration.stage.uiLabel())
                        XdmMetadataText(migration.message)
                    }
                }
            }
        }
    }
}
