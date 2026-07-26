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
@UiSurface(UiAudience.User, "Review and add a download")
fun AddDownloadScreen(
    destinationUri: String,
    conflictPolicy: FilenameConflictPolicy,
    savedDestinations: List<DestinationPermission>,
    externalDraftId: String? = null,
    initialUrl: String? = null,
    initialFileName: String? = null,
    externalSourceLabel: String? = null,
    externalKind: DownloadIntakeKind? = null,
    externalPageTitle: String? = null,
    externalPageUrl: String? = null,
    externalMimeType: String? = null,
    externalContentLength: Long? = null,
    externalCanInspectMedia: Boolean = false,
    onInspectMedia: (String, String) -> Unit = { _, _ -> },
    onDestinationChanged: (String) -> Unit,
    onSafDestinationSelected: (String) -> Unit,
    onConflictPolicyChanged: (FilenameConflictPolicy) -> Unit,
    onAdd: (String, String, BackendType, String, FilenameConflictPolicy, Boolean, String, ChecksumAlgorithm) -> Unit,
    recommend: (String, String, BackendType, String, FilenameConflictPolicy, Boolean) -> BackendRecommendation,
) {
    val context = LocalContext.current
    var url by remember { mutableStateOf(initialUrl.orEmpty()) }
    var name by remember { mutableStateOf(initialFileName.orEmpty()) }
    var backend by remember { mutableStateOf(BackendType.Automatic) }
    var allowFallback by remember { mutableStateOf(true) }
    var expectedChecksum by remember { mutableStateOf("") }
    var checksumAlgorithm by remember { mutableStateOf(ChecksumAlgorithm.Sha256) }
    var advancedExpanded by remember { mutableStateOf(false) }
    var clipboardMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(externalDraftId) {
        if (externalDraftId != null) {
            url = initialUrl.orEmpty()
            name = initialFileName.orEmpty()
        }
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { onSafDestinationSelected(it.toString()) }
    }
    val recommendation = url.takeIf(String::isNotBlank)?.let {
        recommend(url, name, backend, destinationUri, conflictPolicy, allowFallback)
    }
    val review = DownloadReviewPlanner.plan(
        url = url,
        fileName = name,
        mimeType = externalMimeType.takeIf { url == initialUrl },
        destinationUri = destinationUri,
    )
    val canSubmit = review.canStartDirectly && recommendation?.compatible != false
    val canInspectMedia = review.canInspectAsMedia && (externalDraftId == null || externalCanInspectMedia || url != initialUrl)

    Column(Modifier.fillMaxSize().imePadding()) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (externalDraftId != null) {
                item {
                    XdmFlatCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                                Column(Modifier.weight(1f)) {
                                    XdmCardTitle("Link received")
                                    XdmSupportingText(externalIntakeGuidance(externalKind))
                                }
                                externalKind?.let { kind -> XdmStatusBadge(kind.externalLabel(), tone = XdmStatusTone.Info) }
                            }
                            XdmMetadataText("Source: ${externalSourceLabel ?: "External app"}")
                            externalPageTitle?.takeIf(String::isNotBlank)?.let { XdmMetadataText("Page: ${it.take(120)}") }
                            if (!initialFileName.isNullOrBlank()) XdmMetadataText("Filename suggestion: ${initialFileName.take(96)}")
                            val metadata = listOfNotNull(
                                externalMimeType?.takeIf(String::isNotBlank),
                                externalContentLength?.takeIf { it > 0L }?.formatBytes(),
                                externalPageUrl?.takeIf { it.isNotBlank() && it != initialUrl }?.let { "page context" },
                            )
                            if (metadata.isNotEmpty()) XdmMetadataText(metadata.joinToString(" • "))
                            XdmMetadataText("Cookies, tokens, and request headers stay redacted; XDM never auto-queues external handoffs.")
                        }
                    }
                }
            }
            item {
                XdmFlatCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        XdmCardTitle("Review-first intake")
                        XdmSupportingText("Enter or paste a link, confirm what XDM detected, choose a destination, then explicitly add it to the queue.")
                        XdmActionFlowRow {
                            TextButton(onClick = {
                                val candidate = firstDownloadUrlFromClipboard(context)
                                if (candidate != null) {
                                    url = candidate
                                    clipboardMessage = "Link pasted from clipboard"
                                } else {
                                    clipboardMessage = "No supported HTTP, HTTPS, or FTP URL found"
                                }
                            }) { Text("Paste detected URL") }
                            clipboardMessage?.let { XdmMetadataText(it) }
                        }
                    }
                }
            }
            item {
                OutlinedTextField(url, { url = it }, label = { Text("URL") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            }
            item {
                OutlinedTextField(
                    name,
                    { name = it },
                    label = { Text("Filename") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    supportingText = { Text("Optional. XDM will infer a name from the URL when this is empty.") },
                )
            }
            item {
                XdmFlatCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                            Column(Modifier.weight(1f)) {
                                XdmCardTitle(review.title)
                                XdmSupportingText(review.guidance)
                            }
                            review.kind?.let { XdmStatusBadge(it.externalLabel(), tone = when (review.readiness) {
                                DownloadReviewReadiness.InvalidLink -> XdmStatusTone.Error
                                DownloadReviewReadiness.ChoiceRecommended -> XdmStatusTone.Warning
                                DownloadReviewReadiness.Ready -> XdmStatusTone.Success
                                else -> XdmStatusTone.Neutral
                            }) }
                        }
                        XdmActionFlowRow {
                            review.steps.forEach { step ->
                                XdmStatusBadge(
                                    "${step.label}: ${if (step.complete) "Ready" else "Pending"}",
                                    tone = if (step.complete) XdmStatusTone.Success else XdmStatusTone.Neutral,
                                    modifier = Modifier.semantics { stateDescription = "${step.label}: ${step.detail}" },
                                )
                            }
                        }
                        if (canInspectMedia) {
                            Button(
                                onClick = { onInspectMedia(url, name) },
                                enabled = review.normalizedUrl != null,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(if (review.mediaInspectionRecommended) "Inspect as media (recommended)" else "Inspect as media") }
                            XdmMetadataText("Media inspection opens the resolver and never queues a transfer automatically.")
                        }
                    }
                }
            }
            item {
                XdmFlatCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        XdmCardTitle("Destination")
                        XdmMetadataText(destinationUri.ifBlank { "Choose where completed files should be saved." }, maxLines = 2)
                        XdmActionFlowRow {
                            DestinationCatalog.available(Build.VERSION.SDK_INT).forEach { choice ->
                                FilterChip(selected = destinationUri == choice.uri, onClick = { onDestinationChanged(choice.uri) }, label = { Text(choice.label) })
                            }
                            savedDestinations.forEach { destination ->
                                FilterChip(selected = destinationUri == destination.uri, onClick = { onDestinationChanged(destination.uri) }, label = { Text(destination.displayName) })
                            }
                        }
                        Button(onClick = { folderPicker.launch(null) }) { Text("Choose folder or SD card") }
                    }
                }
            }
            recommendation?.let { recommendation ->
                item {
                    XdmFlatCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                XdmCardTitle("Recommended backend", modifier = Modifier.weight(1f))
                                XdmStatusBadge(recommendation.backend.uiLabel(), tone = if (recommendation.compatible) XdmStatusTone.Success else XdmStatusTone.Error)
                            }
                            XdmSupportingText(recommendation.explanation)
                            if (!recommendation.compatible) {
                                Text(
                                    recommendation.compatibilityIssue ?: "This backend cannot start the transfer.",
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            } else {
                                val fallbackBackend = recommendation.fallbackBackend
                                if (recommendation.fallbackAllowed && fallbackBackend != null) {
                                    XdmMetadataText("Fallback: ${fallbackBackend.uiLabel()}, before task creation only")
                                }
                            }
                        }
                    }
                }
            }
            item {
                XdmFlatCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                XdmCardTitle("Advanced download options")
                                XdmMetadataText("Existing-file behavior, backend selection, fallback, and checksum verification.")
                            }
                            TextButton(onClick = { advancedExpanded = !advancedExpanded }) { Text(if (advancedExpanded) "Hide" else "Show") }
                        }
                        if (advancedExpanded) {
                            XdmCardTitle("Existing filename")
                            XdmActionFlowRow {
                                FilenameConflictPolicy.entries.forEach { value ->
                                    FilterChip(selected = conflictPolicy == value, onClick = { onConflictPolicyChanged(value) }, label = { Text(value.uiLabel()) })
                                }
                            }
                            XdmCardTitle("Backend")
                            XdmActionFlowRow {
                                BackendType.entries.forEach { value ->
                                    FilterChip(selected = backend == value, onClick = { backend = value }, label = { Text(value.uiLabel()) })
                                }
                            }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    XdmCardTitle("Compatible fallback")
                                    XdmMetadataText("Allowed only before a backend task owns the destination.")
                                }
                                Switch(checked = allowFallback, onCheckedChange = { allowFallback = it })
                            }
                            XdmCardTitle("Verification")
                            OutlinedTextField(
                                expectedChecksum,
                                { expectedChecksum = it },
                                label = { Text("Expected checksum") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                supportingText = { Text("Optional SHA-256 or SHA-512. A matching checksum is required before final completion.") },
                            )
                            XdmActionFlowRow {
                                ChecksumAlgorithm.entries.forEach { value ->
                                    FilterChip(selected = checksumAlgorithm == value, onClick = { checksumAlgorithm = value }, label = { Text(value.uiLabel()) })
                                }
                            }
                        }
                    }
                }
            }
        }
        XdmFlatCard(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                XdmMetadataText(if (canSubmit) "Ready for explicit queue submission." else review.guidance)
                Button(
                    onClick = { onAdd(url, name, backend, destinationUri, conflictPolicy, allowFallback, expectedChecksum, checksumAlgorithm) },
                    enabled = canSubmit,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(when (review.kind) {
                        DownloadIntakeKind.PageOrUnknown -> "Start direct download"
                        DownloadIntakeKind.Torrent -> "Add torrent handoff"
                        else -> "Add to queue"
                    })
                }
            }
        }
    }
}
internal fun DownloadIntakeKind.externalLabel(): String = when (this) {
    DownloadIntakeKind.DirectFile -> "Direct file"
    DownloadIntakeKind.DirectMedia -> "Direct media"
    DownloadIntakeKind.AdaptiveMedia -> "HLS / DASH"
    DownloadIntakeKind.Torrent -> "Torrent"
    DownloadIntakeKind.PageOrUnknown -> "Page or unknown"
}
internal fun externalIntakeGuidance(kind: DownloadIntakeKind?): String = when (kind) {
    DownloadIntakeKind.DirectFile -> "A downloadable file was shared with XDM. Confirm its name, destination, and backend before starting."
    DownloadIntakeKind.DirectMedia -> "A direct audio or video URL was shared. Download it directly or inspect it in the media workbench."
    DownloadIntakeKind.AdaptiveMedia -> "An HLS or DASH manifest was shared. Inspect it as media to resolve variants, audio, and subtitles before queueing."
    DownloadIntakeKind.Torrent -> "A torrent handoff was detected. Review the destination and compatible backend before starting."
    DownloadIntakeKind.PageOrUnknown -> "This may be a webpage or an endpoint without file metadata. Inspect it as media for a yt-dlp probe, or start a direct download only when the URL itself is downloadable."
    null -> "Review the shared or browser-provided link, then start the download when ready."
}
