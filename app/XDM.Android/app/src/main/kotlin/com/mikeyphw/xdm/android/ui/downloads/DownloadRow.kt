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
internal fun DownloadCard(
    download: Download,
    compact: Boolean,
    capabilities: List<BackendCapabilityRow>,
    checksumResults: List<ChecksumResult>,
    verificationRecords: List<VerificationRecord>,
    onTogglePause: (Download) -> Unit,
    onMigrateBackend: (Download) -> Unit,
    onRemoveHistory: (Download) -> Unit,
    onPreviewPostProcessing: (Download) -> Unit,
    onRunPostProcessing: (Download) -> Unit,
    onStartIgnoringQueuePolicy: (Download) -> Unit,
) {
    val context = LocalContext.current
    var expanded by remember(download.id) { mutableStateOf(false) }
    Card(
        Modifier
            .fillMaxWidth()
            .semantics { contentDescription = download.accessibilitySummary() },
    ) {
        Column(Modifier.padding(if (compact) 10.dp else 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    XdmCardTitle(download.fileName, maxLines = 1)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        XdmStatusBadge(download.state.uiLabel(), tone = download.state.statusTone())
                        XdmMetadataText(download.backend.uiLabel(), maxLines = 1)
                    }
                }
                if (download.state in setOf(
                        DownloadState.Queued,
                        DownloadState.Downloading,
                        DownloadState.Connecting,
                        DownloadState.Paused,
                        DownloadState.WaitingForNetwork,
                        DownloadState.WaitingForPower,
                        DownloadState.Failed,
                    )
                ) {
                    IconButton(
                        onClick = { onTogglePause(download) },
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                    ) {
                        val paused = download.state in setOf(
                            DownloadState.Paused,
                            DownloadState.WaitingForNetwork,
                            DownloadState.WaitingForPower,
                            DownloadState.Failed,
                        )
                        val action = if (paused) "Resume" else "Pause"
                        Icon(if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, "$action ${download.fileName}")
                    }
                }
            }
            val totalBytes = download.totalBytes
            if (totalBytes != null) {
                LinearProgressIndicator(
                    progress = { download.progressFraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { stateDescription = download.progressAccessibilitySummary() },
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    XdmMetricText("${download.bytesReceived.formatBytes()} / ${totalBytes.formatBytes()}")
                    XdmMetricText(if (download.speedBytesPerSecond > 0) download.speedBytesPerSecond.formatSpeed() else "Waiting")
                }
            } else {
                XdmMetadataText(download.destinationUri, maxLines = 1)
            }
            download.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, maxLines = if (expanded) Int.MAX_VALUE else 2) }
            val queuePolicyHeld = download.errorMessage.orEmpty().startsWith("Queue policy:") &&
                download.state !in setOf(DownloadState.Downloading, DownloadState.Connecting, DownloadState.Completed, DownloadState.Cancelled)
            if (queuePolicyHeld) {
                Button(
                    onClick = { onStartIgnoringQueuePolicy(download) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Start anyway") }
                XdmMetadataText("Explicit override bypasses soft queue policy, but XDM still requires a validated internet connection.")
            }
            DownloadDashboardPlanner.attentionSignal(download)?.let { signal ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        XdmStatusBadge(signal.label, tone = XdmStatusTone.Error)
                        XdmMetadataText(signal.guidance, maxLines = if (expanded) 4 else 2)
                    }
                }
            }
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide details" else "Details") }
            if (expanded) {
                DownloadDetails(
                    download = download,
                    capabilities = capabilities,
                    checksumResults = checksumResults,
                    verificationRecords = verificationRecords,
                    onMigrateBackend = onMigrateBackend,
                    onRemoveHistory = onRemoveHistory,
                    onCopyUrl = { copyTextToClipboard(context, "XDM source URL", download.sourceUrl) },
                    onCopyFileInfo = { copyTextToClipboard(context, "XDM file info", download.fileManagementSummary()) },
                    onPreviewPostProcessing = { onPreviewPostProcessing(download) },
                    onRunPostProcessing = { onRunPostProcessing(download) },
                )
            }
        }
    }
}
