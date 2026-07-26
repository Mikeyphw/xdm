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
@UiSurface(UiAudience.User, "Review one media capture and select tracks")
internal fun MediaCaptureCard(
    capture: MediaCaptureRecord,
    captureVariants: List<MediaVariant>,
    persistedSelection: MediaTrackSelection,
    resolverPlanner: MediaResolverWorkspacePlanner,
    onDownload: (MediaCaptureRecord, MediaTrackSelection) -> Unit,
    onResolve: (MediaCaptureRecord) -> Unit,
    onSelectVariant: (MediaCaptureRecord, String) -> Unit,
    onTrackSelectionChanged: (MediaCaptureRecord, MediaTrackSelection) -> Unit,
    onRemove: (MediaCaptureRecord) -> Unit,
    onTermuxMetadata: (MediaCaptureRecord, MediaTrackSelection) -> Unit,
    onTermuxInspect: (MediaCaptureRecord) -> Unit,
    onTermuxYtDlpDownload: (MediaCaptureRecord, MediaTrackSelection) -> Unit,
    onTermuxConvert: (MediaCaptureRecord, ConversionPreset) -> Unit,
    onPreviewPostProcessing: (MediaCaptureRecord) -> Unit,
    onRunPostProcessing: (MediaCaptureRecord) -> Unit,
) {
    var variantSelectorExpanded by remember(capture.id) { mutableStateOf(false) }
    var moreActionsExpanded by remember(capture.id) { mutableStateOf(false) }
    var trackSelection by remember(capture.id) {
        mutableStateOf(persistedSelection.copy(videoVariantId = persistedSelection.videoVariantId ?: capture.selectedVariantId))
    }
    LaunchedEffect(persistedSelection, capture.selectedVariantId) {
        trackSelection = persistedSelection.copy(videoVariantId = persistedSelection.videoVariantId ?: capture.selectedVariantId)
    }

    val mediaPlanner = remember { MediaDownloadPlanner() }
    val mediaPlan = remember(capture, captureVariants, trackSelection) {
        mediaPlanner.plan(capture, captureVariants, selection = trackSelection)
    }
    val pickerGroups = remember(capture, captureVariants, trackSelection) {
        mediaPlanner.pickerGroups(capture, captureVariants, trackSelection)
    }
    val playbackCandidate = remember(capture, captureVariants) {
        mediaPlanner.playbackCandidate(capture, captureVariants)
    }
    val selectedVariant = mediaPlan.selectedVariantId?.let { id -> captureVariants.firstOrNull { it.id == id } }
        ?: captureVariants.firstOrNull { it.id == capture.selectedVariantId }
        ?: captureVariants.firstOrNull()

    XdmListCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                XdmCardTitle(capture.title, maxLines = 1)
                XdmMetadataText(mediaOriginLabel(capture), maxLines = 1)
            }
            StatusPill(capture.kind.uiLabel(), tone = XdmStatusTone.Info)
        }
        XdmSupportingText(
            listOfNotNull(
                capture.mimeType,
                capture.container,
                capture.durationMs?.let { formatDurationSeconds(it) },
            ).joinToString(" • ").ifBlank { "Media details will appear after resolution." },
            maxLines = 2,
        )
        XdmActionFlowRow {
            StatusPill(capture.status.uiLabel(), if (capture.status == MediaCaptureStatus.DownloadCreated) XdmStatusTone.Success else XdmStatusTone.Neutral)
            StatusPill(capture.resolutionStatus.uiLabel(), if (capture.resolutionStatus == MediaResolutionStatus.Failed || capture.resolutionStatus == MediaResolutionStatus.RequiresRefresh) XdmStatusTone.Warning else XdmStatusTone.Neutral)
            capture.downloadId?.let { StatusPill("Added", tone = XdmStatusTone.Success) }
        }
        XdmSupportingText(mediaPlan.explanation, maxLines = 3)

        if (captureVariants.isNotEmpty()) {
            XdmListCard(compact = true) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        XdmMetadataText("Selected quality")
                        XdmMetricText(selectedVariant?.qualityLabel ?: "Automatic")
                        XdmSupportingText("Choose video, audio, and subtitle tracks before downloading.", maxLines = 2)
                    }
                    TextButton(onClick = { variantSelectorExpanded = !variantSelectorExpanded }) {
                        Text(if (variantSelectorExpanded) "Done" else "Choose tracks")
                    }
                }
                if (variantSelectorExpanded) {
                    pickerGroups.forEach { group ->
                        VariantPickerGroupCard(
                            group = group,
                            onSelect = { variant ->
                                val nextSelection = when (group.kind) {
                                    MediaVariantKind.Audio -> trackSelection.copy(audioVariantId = variant.id)
                                    MediaVariantKind.Subtitle -> trackSelection.copy(subtitleVariantId = variant.id)
                                    MediaVariantKind.Primary, MediaVariantKind.Video -> trackSelection.copy(videoVariantId = variant.id)
                                    MediaVariantKind.Thumbnail -> trackSelection
                                }
                                trackSelection = nextSelection
                                onTrackSelectionChanged(capture, nextSelection)
                                if (group.kind == MediaVariantKind.Primary || group.kind == MediaVariantKind.Video) {
                                    onSelectVariant(capture, variant.id)
                                }
                            },
                        )
                    }
                }
            }
        } else {
            XdmMetadataText("Resolve this item to discover available quality, audio, and subtitle options.")
        }

        playbackCandidate?.let { candidate -> Media3DirectPlayerCard(candidate) }

        XdmActionFlowRow {
            Button(
                onClick = { onDownload(capture, mediaPlan.trackSelection) },
                enabled = mediaPlan.canQueueDirectly && capture.status != MediaCaptureStatus.DownloadCreated && capture.resolutionStatus != MediaResolutionStatus.RequiresRefresh,
            ) { Text(if (capture.status == MediaCaptureStatus.DownloadCreated) "Added" else "Add to downloads") }
            TextButton(onClick = { onResolve(capture) }) {
                Text(if (capture.resolutionStatus == MediaResolutionStatus.RequiresRefresh) "Refresh" else "Resolve")
            }
            TextButton(onClick = { moreActionsExpanded = !moreActionsExpanded }) {
                Text(if (moreActionsExpanded) "Fewer actions" else "More actions")
            }
        }

        if (moreActionsExpanded) {
            XdmListCard(compact = true) {
                XdmMetadataText("External tools")
                XdmSupportingText("Optional metadata, inspection, download, conversion, and post-processing actions.", maxLines = 2)
                XdmActionFlowRow {
                    TextButton(onClick = { onTermuxMetadata(capture, mediaPlan.trackSelection) }) { Text("Read metadata") }
                    TextButton(onClick = { onTermuxInspect(capture) }) { Text("Inspect file") }
                    TextButton(onClick = { onTermuxYtDlpDownload(capture, mediaPlan.trackSelection) }, enabled = mediaPlan.canQueueDirectly) { Text("Download with yt-dlp") }
                    TextButton(onClick = { onTermuxConvert(capture, ConversionPreset.VideoFastStart) }) { Text("Optimize MP4") }
                    TextButton(onClick = { onTermuxConvert(capture, ConversionPreset.AudioExtract) }) { Text("Extract audio") }
                }
                XdmActionFlowRow {
                    TextButton(onClick = { onPreviewPostProcessing(capture) }) { Text("Preview rules") }
                    TextButton(onClick = { onRunPostProcessing(capture) }) { Text("Run rules") }
                    TextButton(onClick = { onRemove(capture) }) { Text("Remove capture") }
                }
            }
        }
    }
}


@Composable
internal fun MediaResolverHistoryCard(rows: List<MediaResolverHistoryRow>) {
    XdmListCard(compact = true) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                XdmCardTitle("Recent resolutions")
                XdmSupportingText("Resolver history is derived from downloader media captures. No browser history, page archive, cookie value, or authorization value is stored.", maxLines = 3)
            }
            StatusPill("${rows.size} recent", tone = XdmStatusTone.Neutral)
        }
        if (rows.isEmpty()) {
            XdmMetadataText("Share a media or page URL to create the first review-first resolver entry.", maxLines = 2)
        } else {
            rows.take(5).forEach { row ->
                XdmListCard(compact = true) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            XdmMetadataText(row.title, maxLines = 1)
                            XdmSupportingText("${row.sourceHost} • ${row.kindLabel}", maxLines = 1)
                            XdmMetadataText(row.selectionSummary, maxLines = 2)
                        }
                        StatusPill(row.statusLabel, if (row.statusLabel == "Failed" || row.statusLabel == "Refresh required" || row.statusLabel == "Protected") XdmStatusTone.Warning else XdmStatusTone.Info)
                    }
                }
            }
        }
    }
}
@Composable
internal fun MediaResolverWorkspaceCard(workspace: MediaResolverWorkspace) {
    XdmListCard(compact = true) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                XdmMetadataText("Resolver workspace")
                XdmSupportingText(workspace.summary, maxLines = 2)
                XdmMetadataText("Source: ${workspace.sourceLabel}", maxLines = 1)
            }
            StatusPill(workspace.stage.label, toneForResolverStage(workspace.stage))
        }
        XdmActionFlowRow {
            workspace.stageSequence.forEach { stage ->
                StatusPill(stage.label, if (stage == workspace.stage) toneForResolverStage(stage) else XdmStatusTone.Neutral)
            }
        }
        XdmListCard(compact = true) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    XdmMetadataText("yt-dlp / manifest probe")
                    XdmSupportingText("${workspace.probe.extractorLabel} • ${workspace.probe.probeTargetLabel}", maxLines = 2)
                    XdmMetadataText(workspace.probe.authenticationLabel, maxLines = 1)
                }
                StatusPill(workspace.probe.statusLabel, if (workspace.probe.warnings.isEmpty()) XdmStatusTone.Success else XdmStatusTone.Warning)
            }
            XdmActionFlowRow {
                StatusPill("${workspace.probe.formatCount} formats", tone = XdmStatusTone.Info)
                StatusPill(if (workspace.session.cookiesAvailable) "Cookies available / redacted" else "No cookies", tone = XdmStatusTone.Neutral)
                if (workspace.session.authorizationAvailable) StatusPill("Authorization present / redacted", tone = XdmStatusTone.Warning)
                if (workspace.session.referrerHost != null) StatusPill("Referrer ${workspace.session.referrerHost}", tone = XdmStatusTone.Neutral)
            }
            workspace.probe.warnings.take(3).forEach { warning -> XdmMetadataText(warning, maxLines = 2) }
            XdmMetadataText(workspace.session.redactedSummary, maxLines = 3)
        }
        if (workspace.formats.isNotEmpty()) {
            MediaFormatComparisonCard(workspace.formats, workspace.comparisonNotes)
        }
        if (workspace.audioTracks.isNotEmpty() || workspace.subtitleTracks.isNotEmpty()) {
            MediaTrackSelectionSummaryCard(workspace.audioTracks, workspace.subtitleTracks)
        }
        XdmListCard(compact = true) {
            XdmMetadataText("Download review")
            XdmMetricText(workspace.selectedSummary)
            XdmSupportingText(
                if (workspace.readyToQueue) "The reviewed selection is ready to hand off to the existing queue and engine planner."
                else "Resolve or refresh the source and review the available streams before queueing.",
                maxLines = 3,
            )
            XdmActionFlowRow {
                StatusPill(if (workspace.readyToQueue) "Ready to queue" else "Review required", if (workspace.readyToQueue) XdmStatusTone.Success else XdmStatusTone.Warning)
                StatusPill(if (workspace.protectedDiagnostic.protected) "Diagnostic only" else "Direct download allowed", if (workspace.protectedDiagnostic.protected) XdmStatusTone.Warning else XdmStatusTone.Info)
            }
        }
    }
}
@Composable
internal fun MediaFormatComparisonCard(rows: List<MediaResolverFormatRow>, notes: List<String>) {
    XdmListCard(compact = true) {
        XdmMetadataText("Quality comparison")
        rows.take(6).forEach { row ->
            XdmListCard(compact = true) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        XdmMetadataText(row.title, maxLines = 1)
                        XdmSupportingText(row.detail, maxLines = 2)
                        XdmMetadataText(listOfNotNull(row.bitrateLabel, row.estimatedSizeLabel, row.containerLabel).joinToString(" • "), maxLines = 2)
                    }
                    if (row.selected) StatusPill("Selected", tone = XdmStatusTone.Success)
                }
                XdmActionFlowRow {
                    row.recommendations.forEach { recommendation ->
                        StatusPill(recommendation, when (recommendation) {
                            "Best quality" -> XdmStatusTone.Info
                            "Compatible" -> XdmStatusTone.Success
                            "HDR" -> XdmStatusTone.Warning
                            else -> XdmStatusTone.Neutral
                        })
                    }
                }
            }
        }
        notes.take(4).forEach { note -> XdmMetadataText(note, maxLines = 2) }
    }
}
@Composable
internal fun MediaTrackSelectionSummaryCard(audio: List<MediaResolverTrackRow>, subtitles: List<MediaResolverTrackRow>) {
    XdmListCard(compact = true) {
        XdmMetadataText("Audio and subtitle tracks")
        audio.take(4).forEach { row ->
            XdmListCard(compact = true) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        XdmMetadataText("Audio • ${row.languageLabel}", maxLines = 1)
                        XdmSupportingText(row.detail, maxLines = 2)
                    }
                    if (row.selected) StatusPill("Selected", tone = XdmStatusTone.Success)
                }
            }
        }
        subtitles.take(4).forEach { row ->
            XdmListCard(compact = true) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        XdmMetadataText("Subtitles • ${row.languageLabel}", maxLines = 1)
                        XdmSupportingText(row.detail, maxLines = 2)
                    }
                    if (row.selected) StatusPill("Selected", tone = XdmStatusTone.Success)
                }
                XdmActionFlowRow {
                    if (row.forced) StatusPill("Forced", tone = XdmStatusTone.Warning)
                    if (row.autoGenerated) StatusPill("Auto-generated", tone = XdmStatusTone.Neutral)
                    if (!row.forced && !row.autoGenerated) StatusPill("External track", tone = XdmStatusTone.Info)
                }
            }
        }
    }
}
internal fun toneForResolverStage(stage: MediaResolverStage): XdmStatusTone = when (stage) {
    MediaResolverStage.Ready -> XdmStatusTone.Success
    MediaResolverStage.Protected, MediaResolverStage.Failed -> XdmStatusTone.Warning
    MediaResolverStage.Probe, MediaResolverStage.Streams, MediaResolverStage.Selection, MediaResolverStage.Review -> XdmStatusTone.Info
    MediaResolverStage.Source -> XdmStatusTone.Neutral
}
@Composable
internal fun VariantPickerGroupCard(group: MediaVariantPickerGroup, onSelect: (MediaVariant) -> Unit) {
    XdmListCard(compact = true) {
        XdmMetadataText(group.title)
        XdmSupportingText(group.countLabel, maxLines = 1)
        group.variants.forEach { variant ->
            VariantSelectorRow(
                variant = variant,
                selected = group.selectedVariantId == variant.id,
                onSelect = { onSelect(variant) },
            )
        }
    }
}
@Composable
internal fun PostProcessingAutomationCard(
    automation: PostProcessingAutomationStatus,
    onEnabledChanged: ((Boolean) -> Unit)?,
    onRetryFailed: (() -> Unit)?,
    onClearEvents: (() -> Unit)?,
) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth().semantics { contentDescription = "Post-processing automation ${automation.readinessLabel}" }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    XdmCardTitle("Post-processing automation")
                    XdmMetricText(automation.readinessLabel)
                }
                onEnabledChanged?.let { callback ->
                    Switch(
                        checked = automation.enabled,
                        onCheckedChange = callback,
                        modifier = Modifier.semantics { stateDescription = if (automation.enabled) "Post-processing automation enabled" else "Post-processing automation disabled" },
                    )
                } ?: StatusPill(if (automation.enabled) "Enabled" else "Disabled", if (automation.enabled) XdmStatusTone.Success else XdmStatusTone.Neutral)
            }
            XdmSupportingText("Rules are previewable and execute only through typed Termux media, checksum, cleanup, move/rename, or optional root actions.")
            XdmMetadataText(automation.lastMessage, maxLines = 3)
            XdmActionFlowRow {
                onRetryFailed?.let { TextButton(onClick = it, enabled = automation.failedEvents.isNotEmpty()) { Text("Retry failed") } }
                onClearEvents?.let { TextButton(onClick = it, enabled = automation.events.isNotEmpty()) { Text("Clear events") } }
                TextButton(onClick = { copyTextToClipboard(context, "XDM post-processing diagnostics", automation.diagnosticsSummary()) }) { Text("Copy diagnostics") }
            }
            automation.enabledRules.take(3).forEach { rule ->
                XdmListCard(compact = true) {
                    XdmCardTitle(rule.name, maxLines = 1)
                    XdmMetadataText(rule.summary, maxLines = 2)
                    rule.conditions.takeIf { it.isNotEmpty() }?.let { conditions ->
                        XdmMetadataText("When ${conditions.joinToString { it.summary }}", maxLines = 2)
                    }
                }
            }
            automation.recentEvents.take(4).forEach { event ->
                val tone = when (event.status) {
                    PostProcessingAutomationEventStatus.Failed -> XdmStatusTone.Warning
                    PostProcessingAutomationEventStatus.Completed, PostProcessingAutomationEventStatus.Queued -> XdmStatusTone.Info
                    PostProcessingAutomationEventStatus.Preview, PostProcessingAutomationEventStatus.Skipped -> XdmStatusTone.Neutral
                }
                XdmMetadataText("${event.summary}: ${event.message}", maxLines = 2)
                StatusPill(event.status.label, tone)
            }
        }
    }
}
@Composable
internal fun VariantSelectorRow(variant: MediaVariant, selected: Boolean, onSelect: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                XdmCardTitle(variant.qualityLabel)
                XdmMetadataText(variantDetails(variant), maxLines = 2)
            }
            FilterChip(selected = selected, onClick = onSelect, label = { Text(if (selected) "Selected" else "Select") })
        }
    }
}
