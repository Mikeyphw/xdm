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
@UiSurface(UiAudience.User, "Browse completed playable media")
fun MediaLibraryScreen(
    captures: List<MediaCaptureRecord>,
    variants: List<MediaVariant>,
    downloads: List<Download>,
    onResumeOrRetryDownload: (Download) -> Unit,
) {
    val mediaPlanner = remember { MediaDownloadPlanner() }
    val executionPlanner = remember { MediaExecutionLibraryPlanner(mediaPlanner) }
    val summary = remember(captures, variants) { mediaPlanner.summarizeOfflineLibrary(captures, variants) }
    val items = remember(captures, downloads, variants) {
        executionPlanner.offlineLibraryItems(captures, downloads, variants)
    }
    val playableCount = items.count { it.toPlaybackCandidate() != null }
    val failedCount = items.count { item ->
        item.downloadId?.let { id -> downloads.firstOrNull { it.id == id }?.state == DownloadState.Failed } == true
    }

    Column(Modifier.fillMaxSize()) {
        XdmListCard(compact = true) {
            XdmCardTitle("Library")
            XdmSupportingText("Completed media ready to play, open, or repair.", maxLines = 2)
            XdmActionFlowRow {
                StatusPill("${items.size} items", tone = XdmStatusTone.Neutral)
                StatusPill("$playableCount playable", if (playableCount > 0) XdmStatusTone.Success else XdmStatusTone.Neutral)
                if (failedCount > 0) StatusPill("$failedCount need attention", tone = XdmStatusTone.Warning)
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { OfflineMediaLibraryCard(summary, items, downloads, onResumeOrRetryDownload) }
            if (items.isEmpty()) {
                item {
                    EmptyFeatureScreen(
                        "Your library is empty",
                        "Completed media appears here automatically after a download finishes.",
                    )
                }
            }
        }
    }
}

@Composable
internal fun OfflineMediaLibraryCard(summary: OfflineMediaLibrarySummary, items: List<OfflineMediaLibraryItem>, downloads: List<Download>, onResumeOrRetryDownload: (Download) -> Unit) {
    var expandedPlayerId by remember { mutableStateOf<String?>(null) }
    Card(Modifier.fillMaxWidth().semantics { contentDescription = "Offline library ${summary.message}" }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            XdmCardTitle("Offline library and player")
            XdmSupportingText(summary.message, maxLines = 3)
            XdmActionFlowRow {
                StatusPill("${summary.playableCount} playable", if (summary.playableCount > 0) XdmStatusTone.Success else XdmStatusTone.Neutral)
                summary.adaptiveCount.takeIf { it > 0 }?.let { StatusPill("$it adaptive", tone = XdmStatusTone.Info) }
                summary.audioOnlyCount.takeIf { it > 0 }?.let { StatusPill("$it audio", tone = XdmStatusTone.Neutral) }
                summary.subtitleTrackCount.takeIf { it > 0 }?.let { StatusPill("$it subtitles", tone = XdmStatusTone.Neutral) }
            }
            XdmMetadataText("Offline media library persists redacted sidecar metadata beside completed files: title, thumbnail, duration, source host, selected track IDs, and safe playback state.", maxLines = 3)
            if (items.isEmpty()) {
                XdmMetadataText("No offline media items yet. Queue selected media to seed the library.", maxLines = 2)
            } else {
                items.take(5).forEach { item ->
                    XdmListCard(compact = true) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                            Column(Modifier.weight(1f)) {
                                XdmCardTitle(item.title, maxLines = 1)
                                XdmMetadataText(listOf(item.fileName, item.sourceHost, item.durationLabel).joinToString(" • "), maxLines = 1)
                                XdmSupportingText(item.detail, maxLines = 2)
                            }
                            StatusPill(item.state?.name ?: "Captured", if (item.isCompleted) XdmStatusTone.Success else XdmStatusTone.Neutral)
                        }
                        XdmActionFlowRow {
                            item.pageHost?.takeIf { it.isNotBlank() }?.let { StatusPill("Page $it", tone = XdmStatusTone.Info) }
                            item.thumbnailUrl?.takeIf { it.isNotBlank() }?.let { StatusPill("Thumbnail", tone = XdmStatusTone.Info) }
                            item.downloadId?.let { StatusPill("Job", tone = XdmStatusTone.Neutral) }
                        }
                        XdmMetadataText("Sidecar: ${item.sidecar.toRedactedJson()}", maxLines = 2)
                        val playbackCandidate = item.toPlaybackCandidate()
                        XdmActionFlowRow {
                            playbackCandidate?.let {
                                TextButton(onClick = { expandedPlayerId = if (expandedPlayerId == item.captureId) null else item.captureId }) { Text(if (expandedPlayerId == item.captureId) "Hide player" else "Open player") }
                            }
                            val actionLabel = when {
                                item.canResume -> "Resume media"
                                item.canRetry -> "Retry media"
                                else -> null
                            }
                            actionLabel?.let { label ->
                                val download = item.downloadId?.let { id -> downloads.firstOrNull { it.id == id } }
                                download?.let { TextButton(onClick = { onResumeOrRetryDownload(it) }) { Text(label) } }
                            }
                        }
                        if (expandedPlayerId == item.captureId) {
                            playbackCandidate?.let { Media3DirectPlayerCard(it) }
                        }
                    }
                }
            }
        }
    }
}
