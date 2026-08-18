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
internal fun MediaFinalValidationGateCard(dashboard: MediaFinalValidationDashboard) {
    Card(Modifier.fillMaxWidth().semantics { contentDescription = "Media final validation gate ${dashboard.summary}" }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            XdmCardTitle("Media final validation gate")
        XdmMetadataText("Phase 33 re-enables validation; the current release gate retains and extends that evidence.", maxLines = 2)
            XdmSupportingText(
                "Overlay 13 is the final remediation gate: static validators, Gradle build/test/lint, warning-zero policy, route contracts, Termux/chroot safety, and real-filesystem privacy scans must all provide evidence before release readiness.",
                maxLines = 4,
            )
            XdmActionFlowRow {
                StatusPill(if (dashboard.releaseReady) "release-ready" else "validation pending or blocked", if (dashboard.releaseReady) XdmStatusTone.Success else XdmStatusTone.Warning)
                dashboard.blockerCount.takeIf { it > 0 }?.let { StatusPill("$it blocker", tone = XdmStatusTone.Error) }
                dashboard.reviewCount.takeIf { it > 0 }?.let { StatusPill("$it review", tone = XdmStatusTone.Warning) }
                StatusPill("${dashboard.commandCount} commands", tone = XdmStatusTone.Info)
                StatusPill(if (dashboard.warningGate) "warning-zero" else "warning review", if (dashboard.warningGate) XdmStatusTone.Success else XdmStatusTone.Warning)
                StatusPill(if (dashboard.noNewTopLevelRoutes) "no new routes" else "route review", if (dashboard.noNewTopLevelRoutes) XdmStatusTone.Success else XdmStatusTone.Error)
                StatusPill(if (dashboard.secretSafe) "secret-safe" else "redaction review", if (dashboard.secretSafe) XdmStatusTone.Success else XdmStatusTone.Error)
            }
            XdmMetadataText(dashboard.summary, maxLines = 3)
            dashboard.checks.take(6).forEach { check ->
                XdmListCard(compact = true) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            XdmMetadataText(check.title, maxLines = 1)
                            XdmSupportingText(check.summary, maxLines = 2)
                            XdmMetadataText(check.evidence, maxLines = 2)
                        }
                        StatusPill(if (check.passing) "pass" else check.severity.label, toneForFinalValidation(check.severity, check.passing))
                    }
                }
            }
            XdmListCard(compact = true) {
                XdmMetadataText("Final command ledger", maxLines = 1)
                dashboard.commands.take(5).forEach { command ->
                    XdmMetadataText("${command.label}: ${command.safePreview}", maxLines = 2)
                }
            }
        }
    }
}
internal fun toneForFinalValidation(severity: MediaFinalValidationSeverity, passing: Boolean): XdmStatusTone = when {
    passing -> XdmStatusTone.Success
    severity == MediaFinalValidationSeverity.Blocker -> XdmStatusTone.Error
    severity == MediaFinalValidationSeverity.Review -> XdmStatusTone.Warning
    else -> XdmStatusTone.Neutral
}
@Composable
internal fun MediaMobilePolishCard(dashboard: MediaMobilePolishDashboard) {
    Card(Modifier.fillMaxWidth().semantics { contentDescription = "Media mobile polish ${dashboard.summary}" }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            XdmCardTitle("Media mobile polish")
            XdmSupportingText(
                "Phase 32 makes the Media stack phone-friendly with a sticky current-job summary, compact action strip, collapsed diagnostics, explicit empty/offline/error states, accessibility labels, and foldable guidance without adding routes.",
                maxLines = 4,
            )
            XdmActionFlowRow {
                StatusPill(dashboard.mode.label, tone = XdmStatusTone.Info)
                StatusPill("${dashboard.visiblePrimarySectionCount} primary", tone = XdmStatusTone.Neutral)
                dashboard.collapsedDiagnosticsCount.takeIf { it > 0 }?.let { StatusPill("$it collapsed", tone = XdmStatusTone.Info) }
                dashboard.attentionCount.takeIf { it > 0 }?.let { StatusPill("$it attention", tone = XdmStatusTone.Warning) }
                StatusPill(if (dashboard.noTinyScrollIslands) "no tiny scroll islands" else "scroll review", if (dashboard.noTinyScrollIslands) XdmStatusTone.Success else XdmStatusTone.Warning)
                StatusPill(if (dashboard.accessibilityReady) "accessibility-ready" else "accessibility review", if (dashboard.accessibilityReady) XdmStatusTone.Success else XdmStatusTone.Warning)
                StatusPill(if (dashboard.secretSafe) "secret-safe" else "redaction review", if (dashboard.secretSafe) XdmStatusTone.Success else XdmStatusTone.Error)
            }
            XdmListCard(compact = true) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        XdmMetadataText("Sticky current job summary", maxLines = 1)
                        XdmSupportingText(dashboard.currentJob.summary, maxLines = 2)
                        XdmMetadataText(dashboard.currentJob.safeDiagnostic, maxLines = 2)
                    }
                    StatusPill(dashboard.currentJob.primaryActionLabel, if (dashboard.currentJob.attentionRequired) XdmStatusTone.Warning else XdmStatusTone.Success)
                }
            }
            XdmMetadataText(dashboard.emptyStateLabel, maxLines = 2)
            dashboard.sections.take(4).forEach { section ->
                XdmListCard(compact = true) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            XdmMetadataText(section.title, maxLines = 1)
                            XdmSupportingText(section.summary, maxLines = 2)
                            XdmMetadataText(section.accessibilityLabel, maxLines = 2)
                        }
                        StatusPill(section.priority.label, toneForMobilePriority(section.priority))
                    }
                    XdmActionFlowRow {
                        StatusPill("max ${section.recommendedMaxRows}", tone = XdmStatusTone.Neutral)
                        if (section.collapsedByDefault) StatusPill("collapsed", tone = XdmStatusTone.Info)
                    }
                }
            }
            dashboard.recommendations.take(4).forEach { recommendation ->
                XdmMetadataText("${recommendation.signal.label}: ${recommendation.detail}", maxLines = 2)
            }
        }
    }
}
internal fun toneForMobilePriority(priority: MediaMobileSectionPriority): XdmStatusTone = when (priority) {
    MediaMobileSectionPriority.Sticky -> XdmStatusTone.Success
    MediaMobileSectionPriority.Primary -> XdmStatusTone.Info
    MediaMobileSectionPriority.Secondary -> XdmStatusTone.Neutral
    MediaMobileSectionPriority.Collapsed -> XdmStatusTone.Neutral
    MediaMobileSectionPriority.HiddenUntilNeeded -> XdmStatusTone.Warning
}
@Composable
internal fun MediaCaptureQualityCard(dashboard: MediaCaptureQualityDashboard) {
    Card(Modifier.fillMaxWidth().semantics { contentDescription = "Media capture quality ${dashboard.summary}" }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            XdmCardTitle("Media capture quality")
            XdmSupportingText(
                "Phase 30 improves sniffing quality by grouping duplicates, suppressing analytics noise, flagging stale sessions, and scoring captured media without storing secret query strings.",
                maxLines = 3,
            )
            XdmActionFlowRow {
                StatusPill("${dashboard.treasureCount} treasure", if (dashboard.treasureCount > 0) XdmStatusTone.Success else XdmStatusTone.Neutral)
                dashboard.noiseCount.takeIf { it > 0 }?.let { StatusPill("$it noise", tone = XdmStatusTone.Warning) }
                dashboard.duplicateCount.takeIf { it > 0 }?.let { StatusPill("$it grouped", tone = XdmStatusTone.Info) }
                dashboard.refreshCount.takeIf { it > 0 }?.let { StatusPill("$it refresh", tone = XdmStatusTone.Warning) }
                dashboard.protectedCount.takeIf { it > 0 }?.let { StatusPill("$it protected", tone = XdmStatusTone.Warning) }
                dashboard.liveCount.takeIf { it > 0 }?.let { StatusPill("$it live", tone = XdmStatusTone.Info) }
                StatusPill(if (dashboard.secretSafe) "secret-safe" else "redaction review", if (dashboard.secretSafe) XdmStatusTone.Success else XdmStatusTone.Warning)
            }
            XdmMetadataText(dashboard.summary, maxLines = 3)
            if (dashboard.empty) {
                XdmMetadataText("No media capture quality rows yet. Browse or share media to let the sniffer rank captures.", maxLines = 2)
            } else {
                dashboard.rows.take(5).forEach { row ->
                    XdmListCard(compact = true) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                            Column(Modifier.weight(1f)) {
                                XdmMetadataText(row.title, maxLines = 1)
                                XdmSupportingText(row.summary, maxLines = 2)
                                XdmMetadataText(row.safeDiagnostics, maxLines = 3)
                            }
                            StatusPill(row.disposition.label, toneForCaptureQuality(row.disposition))
                        }
                        XdmActionFlowRow {
                            StatusPill("confidence ${row.confidenceScore}", tone = XdmStatusTone.Info)
                            StatusPill(row.sourceHost.ifBlank { "unknown host" }, tone = XdmStatusTone.Neutral)
                            if (row.ignoredByDefault) StatusPill("ignored by default", tone = XdmStatusTone.Warning)
                            if (row.refreshMetadataAvailable) StatusPill("refresh metadata", tone = XdmStatusTone.Warning)
                            row.duplicateOfCaptureId?.let { StatusPill("grouped", tone = XdmStatusTone.Info) }
                        }
                    }
                }
            }
        }
    }
}
internal fun toneForCaptureQuality(disposition: CaptureQualityDisposition): XdmStatusTone = when (disposition) {
    CaptureQualityDisposition.Treasure -> XdmStatusTone.Success
    CaptureQualityDisposition.NeedsMetadataRefresh -> XdmStatusTone.Warning
    CaptureQualityDisposition.IgnoreNoise -> XdmStatusTone.Neutral
    CaptureQualityDisposition.GroupWithExisting -> XdmStatusTone.Info
    CaptureQualityDisposition.ProtectedDiagnostic -> XdmStatusTone.Warning
    CaptureQualityDisposition.LiveReview -> XdmStatusTone.Info
}
@Composable
internal fun SessionPrivacyAuditCard(dashboard: MediaSessionPrivacyAuditDashboard) {
    Card(Modifier.fillMaxWidth().semantics { contentDescription = "Session privacy audit ${dashboard.summary}" }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            XdmCardTitle("Session privacy audit")
            XdmSupportingText(
                "Phase 31 audits external page context, resolver handoffs, queue specs, sidecars, logs, notifications, temp files, and Termux previews for secret leaks and cleanup gaps.",
                maxLines = 3,
            )
            XdmActionFlowRow {
                dashboard.blockerCount.takeIf { it > 0 }?.let { StatusPill("$it blocker", tone = XdmStatusTone.Error) }
                dashboard.reviewCount.takeIf { it > 0 }?.let { StatusPill("$it review", tone = XdmStatusTone.Warning) }
                dashboard.cleanupDueCount.takeIf { it > 0 }?.let { StatusPill("$it cleanup due", tone = XdmStatusTone.Warning) }
                dashboard.cleanupVerifiedCount.takeIf { it > 0 }?.let { StatusPill("$it cleanup verified", tone = XdmStatusTone.Success) }
                StatusPill(if (dashboard.durableSecretSafe) "durable secret-safe" else "durable leak blocked", if (dashboard.durableSecretSafe) XdmStatusTone.Success else XdmStatusTone.Error)
                StatusPill(if (dashboard.transientCleanupHealthy) "cleanup healthy" else "cleanup review", if (dashboard.transientCleanupHealthy) XdmStatusTone.Success else XdmStatusTone.Warning)
            }
            XdmMetadataText(dashboard.summary, maxLines = 3)
            if (dashboard.empty) {
                XdmMetadataText("No privacy findings yet. The audit still scans all planned surfaces when media jobs appear.", maxLines = 2)
            } else {
                dashboard.findings.take(6).forEach { finding ->
                    XdmListCard(compact = true) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                            Column(Modifier.weight(1f)) {
                                XdmMetadataText(finding.surface.label, maxLines = 1)
                                XdmSupportingText(finding.remediation, maxLines = 2)
                                XdmMetadataText(finding.redactedPreview, maxLines = 3)
                            }
                            StatusPill(finding.severity.label, toneForPrivacySeverity(finding.severity))
                        }
                        XdmActionFlowRow {
                            StatusPill(finding.cleanupState.label, tone = XdmStatusTone.Neutral)
                            finding.captureId?.let { StatusPill(it.take(18), tone = XdmStatusTone.Info) }
                        }
                    }
                }
            }
        }
    }
}
internal fun toneForPrivacySeverity(severity: MediaPrivacySeverity): XdmStatusTone = when (severity) {
    MediaPrivacySeverity.Pass -> XdmStatusTone.Success
    MediaPrivacySeverity.Review -> XdmStatusTone.Warning
    MediaPrivacySeverity.Blocker -> XdmStatusTone.Error
}
@Composable
internal fun MediaDispatchDashboardCard(dashboard: MediaDispatchDashboard, plans: List<MediaDispatchPlan>) {
    Card(Modifier.fillMaxWidth().semantics { contentDescription = "Media dispatch control tower ${dashboard.summary}" }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            XdmCardTitle("Media dispatch control tower")
            XdmSupportingText("Phase 22 dispatch runbook maps each resolver choice to a safe lane, background policy, retry policy, progress signals, and terminal cleanup before the job leaves the Media inbox.", maxLines = 3)
            XdmActionFlowRow {
                StatusPill("${dashboard.readyCount} ready", if (dashboard.readyCount > 0) XdmStatusTone.Success else XdmStatusTone.Neutral)
                dashboard.blockedCount.takeIf { it > 0 }?.let { StatusPill("$it blocked", tone = XdmStatusTone.Warning) }
                dashboard.refreshCount.takeIf { it > 0 }?.let { StatusPill("$it refresh", tone = XdmStatusTone.Warning) }
                dashboard.termuxSetupCount.takeIf { it > 0 }?.let { StatusPill("$it Termux setup", tone = XdmStatusTone.Info) }
                StatusPill(if (dashboard.secretSafe) "secret-safe" else "redaction review", if (dashboard.secretSafe) XdmStatusTone.Success else XdmStatusTone.Warning)
            }
            XdmMetadataText(dashboard.summary, maxLines = 3)
            if (plans.isEmpty()) {
                XdmMetadataText("No dispatch plans yet. Share media from IronFox or another app, or inspect a reviewed link, to generate a runbook.", maxLines = 2)
            } else {
                plans.take(4).forEach { plan ->
                    XdmListCard(compact = true) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                            Column(Modifier.weight(1f)) {
                                XdmMetadataText(plan.title, maxLines = 1)
                                XdmSupportingText(plan.summary, maxLines = 2)
                                XdmMetadataText(plan.safeDiagnostics, maxLines = 3)
                            }
                            StatusPill(plan.readiness.label, toneForDispatchReadiness(plan.readiness))
                        }
                        XdmActionFlowRow {
                            StatusPill(plan.lane.label, tone = XdmStatusTone.Info)
                            StatusPill(plan.primaryActionLabel, if (plan.queueButtonEnabled) XdmStatusTone.Success else XdmStatusTone.Neutral)
                            StatusPill("${plan.steps.size} steps", tone = XdmStatusTone.Neutral)
                            plan.progressSignals.firstOrNull()?.let { StatusPill(it.label, tone = XdmStatusTone.Info) }
                        }
                        plan.warnings.takeIf { it.isNotEmpty() }?.let { warnings ->
                            XdmMetadataText("Warnings: ${warnings.joinToString(" • ")}", maxLines = 2)
                        }
                    }
                }
            }
        }
    }
}
@Composable
internal fun MediaQueueTelemetryCard(deck: MediaQueueTelemetryDeck) {
    Card(Modifier.fillMaxWidth().semantics { contentDescription = "Media queue telemetry ${deck.summary}" }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            XdmCardTitle("Media queue telemetry")
            XdmSupportingText(
                "Phase 23 turns dispatch runbooks into a control-room telemetry deck with progress pulse, next action, cleanup status, and redaction health before final validation.",
                maxLines = 3,
            )
            XdmActionFlowRow {
                StatusPill("${deck.readyToLaunchCount} ready", if (deck.readyToLaunchCount > 0) XdmStatusTone.Success else XdmStatusTone.Neutral)
                deck.activeCount.takeIf { it > 0 }?.let { StatusPill("$it active", tone = XdmStatusTone.Info) }
                deck.needsAttentionCount.takeIf { it > 0 }?.let { StatusPill("$it attention", tone = XdmStatusTone.Warning) }
                deck.cleanupArmedCount.takeIf { it > 0 }?.let { StatusPill("$it cleanup armed", tone = XdmStatusTone.Neutral) }
                deck.terminalCount.takeIf { it > 0 }?.let { StatusPill("$it terminal", tone = XdmStatusTone.Neutral) }
                StatusPill(if (deck.secretSafe) "secret-safe telemetry" else "redaction review", if (deck.secretSafe) XdmStatusTone.Success else XdmStatusTone.Warning)
            }
            XdmMetadataText(deck.summary, maxLines = 3)
            if (deck.empty) {
                XdmMetadataText("No queue telemetry yet. Capture media and prepare a dispatch runbook to populate the deck.", maxLines = 2)
            } else {
                deck.rows.take(5).forEach { row ->
                    XdmListCard(compact = true) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                            Column(Modifier.weight(1f)) {
                                XdmMetadataText(row.title, maxLines = 1)
                                XdmSupportingText(row.summary, maxLines = 2)
                                XdmMetadataText(row.safeDiagnostic, maxLines = 3)
                            }
                            StatusPill(row.tone.label, toneForQueueTelemetry(row.tone))
                        }
                        XdmActionFlowRow {
                            StatusPill(row.progressLabel, tone = XdmStatusTone.Info)
                            StatusPill(row.nextActionLabel, if (row.stalled) XdmStatusTone.Warning else XdmStatusTone.Neutral)
                            if (row.cleanupArmed) StatusPill("Terminal cleanup", tone = XdmStatusTone.Success)
                            StatusPill(if (row.secretSafe) "No leak" else "Leak blocked", if (row.secretSafe) XdmStatusTone.Success else XdmStatusTone.Warning)
                        }
                    }
                }
            }
        }
    }
}
@Composable
internal fun MediaNativeDirectDownloadEngineCard(dashboard: NativeDirectDashboard) {
    Card(Modifier.fillMaxWidth().semantics { contentDescription = "Native direct download engine ${dashboard.summary}" }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            XdmCardTitle("Native direct download engine")
            XdmSupportingText(
                "Phase 27 plans Android-native direct media transfers with transient headers, resume metadata, destination policy, and redacted diagnostics before any byte writer is enabled.",
                maxLines = 3,
            )
            XdmActionFlowRow {
                StatusPill("${dashboard.readyCount} ready", if (dashboard.readyCount > 0) XdmStatusTone.Success else XdmStatusTone.Neutral)
                dashboard.resumeCount.takeIf { it > 0 }?.let { StatusPill("$it resumable", tone = XdmStatusTone.Info) }
                dashboard.permissionCount.takeIf { it > 0 }?.let { StatusPill("$it permission", tone = XdmStatusTone.Warning) }
                dashboard.unsupportedCount.takeIf { it > 0 }?.let { StatusPill("$it adaptive", tone = XdmStatusTone.Neutral) }
                StatusPill(if (dashboard.secretSafe) "secret-safe" else "redaction review", if (dashboard.secretSafe) XdmStatusTone.Success else XdmStatusTone.Warning)
            }
            dashboard.plans.take(3).forEach { plan -> NativeDirectDownloadPlanRow(plan) }
            if (dashboard.plans.isEmpty()) {
                XdmSupportingText("No direct download requests yet. Direct MP4, WebM, MP3, and M4A captures will appear here when the worker bridge is ready.")
            }
        }
    }
}
@Composable
internal fun NativeDirectDownloadPlanRow(plan: NativeDirectDownloadRequestPlan) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(plan.summary, style = MaterialTheme.typography.labelLarge)
        XdmSupportingText(plan.redactedDiagnostics.take(220), maxLines = 3)
    }
}
@Composable
internal fun MediaTermuxRuntimeAdapterCard(dashboard: TermuxRuntimeDashboard) {
    Card(Modifier.fillMaxWidth().semantics { contentDescription = "Media Termux runtime adapter ${dashboard.summary}" }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            XdmCardTitle("Media Termux runtime adapter")
            XdmSupportingText(
                "Phase 26 turns worker bridge requests into typed yt-dlp and aria2 Termux launch plans with capability probes, transient Netscape cookie/input/session files, and terminal cleanup checks.",
                maxLines = 3,
            )
            XdmActionFlowRow {
                StatusPill("${dashboard.launchableCount} launchable", if (dashboard.launchableCount > 0) XdmStatusTone.Success else XdmStatusTone.Neutral)
                dashboard.missingToolCount.takeIf { it > 0 }?.let { StatusPill("$it missing tools", tone = XdmStatusTone.Warning) }
                dashboard.cleanupArmedCount.takeIf { it > 0 }?.let { StatusPill("$it cleanup armed", tone = XdmStatusTone.Info) }
                StatusPill(if (dashboard.secretSafe) "secret-safe" else "redaction review", if (dashboard.secretSafe) XdmStatusTone.Success else XdmStatusTone.Warning)
            }
            dashboard.plans.take(3).forEach { plan -> TermuxRuntimeLaunchPlanRow(plan) }
            if (dashboard.plans.isEmpty()) {
                XdmSupportingText("No worker bridge requests yet. Capture media, choose tracks, then review the Termux launch plan before execution.")
            }
        }
    }
}
@Composable
internal fun TermuxRuntimeLaunchPlanRow(plan: TermuxRuntimeLaunchPlan) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(plan.summary, style = MaterialTheme.typography.labelLarge)
        XdmSupportingText(plan.redactedPreview.take(220), maxLines = 3)
        plan.missingToolHints.takeIf { it.isNotEmpty() }?.let { hints ->
            XdmSupportingText("Install/help only: ${hints.joinToString(" • ")}", maxLines = 3)
        }
    }
}
@Composable
internal fun MediaWorkerBridgeCard(dashboard: MediaWorkerBridgeDashboard) {
    Card(Modifier.fillMaxWidth().semantics { contentDescription = "Media worker bridge ${dashboard.summary}" }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            XdmCardTitle("Media worker bridge")
            XdmSupportingText(
                "Phase 25 converts ready media actions into Android UIDT, WorkManager foreground, aria2, native, or Termux yt-dlp bridge requests without launching services yet.",
                maxLines = 3,
            )
            XdmActionFlowRow {
                StatusPill("${dashboard.launchableCount} launchable", if (dashboard.launchableCount > 0) XdmStatusTone.Success else XdmStatusTone.Neutral)
                dashboard.androidWorkerCount.takeIf { it > 0 }?.let { StatusPill("$it Android", tone = XdmStatusTone.Info) }
                dashboard.termuxWorkerCount.takeIf { it > 0 }?.let { StatusPill("$it Termux", tone = XdmStatusTone.Info) }
                dashboard.blockedCount.takeIf { it > 0 }?.let { StatusPill("$it blocked", tone = XdmStatusTone.Warning) }
                dashboard.confirmationCount.takeIf { it > 0 }?.let { StatusPill("$it confirm", tone = XdmStatusTone.Warning) }
                StatusPill(if (dashboard.secretSafe) "secret-safe bridge" else "redaction review", if (dashboard.secretSafe) XdmStatusTone.Success else XdmStatusTone.Warning)
            }
            XdmMetadataText(dashboard.summary, maxLines = 3)
            if (dashboard.empty) {
                XdmMetadataText("No worker bridge requests yet. Queue actions become bridge requests after dispatch planning.", maxLines = 2)
            } else {
                dashboard.requests.take(5).forEach { request -> MediaWorkerBridgePlanCard(request) }
            }
        }
    }
}
@Composable
internal fun MediaWorkerBridgePlanCard(request: MediaWorkerBridgeRequest) {
    XdmListCard(compact = true) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                XdmMetadataText(request.title, maxLines = 1)
                XdmSupportingText(request.summary, maxLines = 3)
                XdmMetadataText(request.notification.summary, maxLines = 2)
                XdmMetadataText(request.adapter.redactedPreview, maxLines = 3)
            }
            StatusPill(request.readiness.label, toneForWorkerBridge(request.readiness, request.kind))
        }
        XdmActionFlowRow {
            StatusPill(request.kind.label, toneForWorkerBridge(request.readiness, request.kind))
            StatusPill(request.backgroundPolicy.workKind.label, tone = XdmStatusTone.Neutral)
            StatusPill(if (request.adapter.rawShellExposed) "raw shell" else "typed adapter", if (request.adapter.rawShellExposed) XdmStatusTone.Warning else XdmStatusTone.Success)
            if (request.cleanupAfterTerminal.isNotEmpty()) StatusPill("cleanup owned", tone = XdmStatusTone.Success)
            StatusPill(if (request.secretSafe) "No leak" else "Leak blocked", if (request.secretSafe) XdmStatusTone.Success else XdmStatusTone.Warning)
        }
    }
}
internal fun toneForWorkerBridge(readiness: MediaWorkerBridgeReadiness, kind: MediaWorkerBridgeKind): XdmStatusTone = when {
    readiness == MediaWorkerBridgeReadiness.Ready -> XdmStatusTone.Success
    readiness == MediaWorkerBridgeReadiness.Blocked || kind == MediaWorkerBridgeKind.BlockedDiagnostic -> XdmStatusTone.Warning
    readiness == MediaWorkerBridgeReadiness.NeedsConfirmation -> XdmStatusTone.Warning
    readiness == MediaWorkerBridgeReadiness.WaitingForTermux || readiness == MediaWorkerBridgeReadiness.WaitingForMetadata -> XdmStatusTone.Info
    else -> XdmStatusTone.Neutral
}
@Composable
internal fun MediaQueueActionsCard(dashboard: MediaQueueActionDashboard) {
    Card(Modifier.fillMaxWidth().semantics { contentDescription = "Media queue actions ${dashboard.summary}" }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            XdmCardTitle("Media queue actions")
            XdmSupportingText(
                "Phase 24 turns telemetry into safe action eligibility for pause, resume, retry, cancel, cleanup, refresh, Termux setup, and library handoff without executing raw commands.",
                maxLines = 3,
            )
            XdmActionFlowRow {
                StatusPill("${dashboard.launchableCount} launch", if (dashboard.launchableCount > 0) XdmStatusTone.Success else XdmStatusTone.Neutral)
                dashboard.pausableCount.takeIf { it > 0 }?.let { StatusPill("$it pause", tone = XdmStatusTone.Info) }
                dashboard.retryableCount.takeIf { it > 0 }?.let { StatusPill("$it retry/resume", tone = XdmStatusTone.Warning) }
                dashboard.cancellableCount.takeIf { it > 0 }?.let { StatusPill("$it cancel", tone = XdmStatusTone.Warning) }
                dashboard.cleanupCount.takeIf { it > 0 }?.let { StatusPill("$it cleanup", tone = XdmStatusTone.Neutral) }
                StatusPill(if (dashboard.secretSafe) "secret-safe actions" else "redaction review", if (dashboard.secretSafe) XdmStatusTone.Success else XdmStatusTone.Warning)
            }
            XdmMetadataText(dashboard.summary, maxLines = 3)
            if (dashboard.empty) {
                XdmMetadataText("No queue actions yet. Capture media and let dispatch telemetry build action eligibility.", maxLines = 2)
            } else {
                dashboard.bulkActions.takeIf { it.isNotEmpty() }?.let { bulkActions ->
                    XdmListCard(compact = true) {
                        XdmMetadataText("Bulk actions")
                        bulkActions.forEach { bulk ->
                            XdmMetadataText("${bulk.label} • ${if (bulk.requiresConfirmation) "confirmation required" else "ready"} • ${bulk.safeSummary}", maxLines = 2)
                        }
                    }
                }
                dashboard.plans.take(5).forEach { plan -> MediaQueueActionPlanCard(plan) }
            }
        }
    }
}
@Composable
internal fun MediaQueueActionPlanCard(plan: MediaQueueActionPlan) {
    XdmListCard(compact = true) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                XdmMetadataText(plan.title, maxLines = 1)
                XdmSupportingText(plan.safeSummary, maxLines = 3)
                plan.unavailableReasons.takeIf { it.isNotEmpty() }?.let { reasons ->
                    XdmMetadataText("Unavailable: ${reasons.joinToString(" • ")}", maxLines = 2)
                }
            }
            StatusPill(plan.primaryAction.kind.label, toneForQueueAction(plan.primaryAction.kind, plan.primaryAction.availability))
        }
        XdmActionFlowRow {
            plan.actions.filter { it.availability != MediaQueueActionAvailability.Hidden }.take(6).forEach { action ->
                StatusPill(action.kind.label, toneForQueueAction(action.kind, action.availability))
            }
        }
    }
}
internal fun toneForQueueAction(kind: MediaQueueActionKind, availability: MediaQueueActionAvailability): XdmStatusTone = when {
    availability == MediaQueueActionAvailability.Disabled || availability == MediaQueueActionAvailability.Hidden -> XdmStatusTone.Neutral
    kind == MediaQueueActionKind.Cancel || availability == MediaQueueActionAvailability.ConfirmationRequired -> XdmStatusTone.Warning
    kind == MediaQueueActionKind.Launch || kind == MediaQueueActionKind.OpenLibrary -> XdmStatusTone.Success
    kind == MediaQueueActionKind.Retry || kind == MediaQueueActionKind.Resume || kind == MediaQueueActionKind.Pause -> XdmStatusTone.Info
    else -> XdmStatusTone.Neutral
}
internal fun toneForQueueTelemetry(tone: MediaQueueTelemetryTone): XdmStatusTone = when (tone) {
    MediaQueueTelemetryTone.Stable -> XdmStatusTone.Success
    MediaQueueTelemetryTone.Active -> XdmStatusTone.Info
    MediaQueueTelemetryTone.Attention,
    MediaQueueTelemetryTone.Blocked -> XdmStatusTone.Warning
}
internal fun toneForDispatchReadiness(readiness: MediaDispatchReadiness): XdmStatusTone = when (readiness) {
    MediaDispatchReadiness.Ready -> XdmStatusTone.Success
    MediaDispatchReadiness.AwaitingUserChoice,
    MediaDispatchReadiness.NeedsTermuxSetup -> XdmStatusTone.Info
    MediaDispatchReadiness.NeedsMetadataRefresh,
    MediaDispatchReadiness.BlockedProtected,
    MediaDispatchReadiness.BlockedSecretLeak -> XdmStatusTone.Warning
}
@Composable
internal fun MediaExecutionQueueCard(jobs: List<MediaExecutionJob>) {
    Card(Modifier.fillMaxWidth().semantics { contentDescription = "Media execution jobs ${jobs.size}" }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            XdmCardTitle("Media download execution")
            XdmSupportingText("Tracks move through explicit states: Probing, Queued, Downloading, Completed, Failed, or Blocked. Retry and resume stay attached to the originating media capture.", maxLines = 3)
            if (jobs.isEmpty()) {
                XdmMetadataText("No media jobs yet.", maxLines = 1)
            } else {
                jobs.take(5).forEach { job ->
                    XdmListCard(compact = true) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                            Column(Modifier.weight(1f)) {
                                XdmMetadataText(job.title, maxLines = 1)
                                XdmSupportingText(job.detail, maxLines = 2)
                                XdmMetadataText(job.engine, maxLines = 1)
                            }
                            StatusPill(job.stage.label, when (job.stage) {
                                MediaExecutionStage.Completed -> XdmStatusTone.Success
                                MediaExecutionStage.Failed, MediaExecutionStage.Blocked -> XdmStatusTone.Warning
                                MediaExecutionStage.Downloading, MediaExecutionStage.Probing -> XdmStatusTone.Info
                                MediaExecutionStage.Queued -> XdmStatusTone.Neutral
                            })
                        }
                    }
                }
            }
        }
    }
}
@Composable
@UiSurface(UiAudience.Developer, "Inspect redacted runtime and planner diagnostics")
fun DiagnosticsScreen(
    state: MainUiState,
    browserStatus: BrowserIntegrationStatus,
    clipboardInbox: List<ClipboardInboxItem>,
    onRunAria2SmokeTest: () -> Unit,
    onRunTermuxProbe: () -> Unit,
    onRunTermuxRootProbe: () -> Unit,
    onCollectRootDiagnostics: () -> Unit,
    onKillStuckAria2WithRoot: () -> Unit,
    onStartTermuxAria2Daemon: () -> Unit,
    onStopTermuxAria2Daemon: () -> Unit,
    onProbeTermuxAria2Daemon: () -> Unit,
    onRefreshTermuxAria2Tasks: () -> Unit,
    onPauseAllTermuxAria2Tasks: () -> Unit,
    onResumeAllTermuxAria2Tasks: () -> Unit,
    onSaveTermuxAria2Session: () -> Unit,
    onRetryPostProcessing: () -> Unit,
    onClearPostProcessingEvents: () -> Unit,
    onScanClipboardText: (String) -> Unit,
    onAcceptClipboardItem: (ClipboardInboxItem) -> Unit,
    onDismissClipboardItem: (ClipboardInboxItem) -> Unit,
) {
    val context = LocalContext.current
    val redactedSummary = PrivacyDiagnosticsRedactor.redactedHealthSummary(
        report = state.releaseSecurityReport,
        downloadCount = state.downloads.size,
        mediaCaptureCount = state.mediaCaptures.size,
        automationCount = state.automationCommands.size,
        rejectedHandoffCount = state.automationCommands.count { it.status == AutomationCommandStatus.Rejected },
    )
    val installUpdateSummary = state.installUpdateReadinessReport.redactedSummary()
    val finalReleaseSummary = state.finalReleaseGateReport.redactedSummary()
    val supportSummary = "$redactedSummary\n\n$installUpdateSummary\n\n$finalReleaseSummary"
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { XdmSectionHeader("Runtime health") }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            XdmCardTitle("App integrity")
                            XdmSupportingText(state.releaseSecurityReport.summary)
                        }
                        TextButton(
                            onClick = { copyTextToClipboard(context, "XDM diagnostic summary", supportSummary) },
                            modifier = Modifier
                                .sizeIn(minWidth = 96.dp, minHeight = 48.dp)
                                .semantics { contentDescription = "Copy privacy-safe release summary" },
                        ) { Text("Copy summary") }
                    }
                    state.releaseSecurityReport.findings.take(3).forEach { finding ->
                        val severity = when (finding.severity) {
                            ReleaseSecuritySeverity.Info -> "Info"
                            ReleaseSecuritySeverity.Warning -> "Warning"
                            ReleaseSecuritySeverity.Blocking -> "Blocked"
                        }
                        XdmMetadataText("$severity: ${finding.title}")
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    XdmCardTitle("Update compatibility")
                    XdmSupportingText(state.installUpdateReadinessReport.summary)
                    state.installUpdateReadinessReport.checks.take(4).forEach { check ->
                        val severity = when (check.severity) {
                            ReleaseReadinessSeverity.Info -> "Info"
                            ReleaseReadinessSeverity.Warning -> "Warning"
                            ReleaseReadinessSeverity.Blocking -> "Blocked"
                        }
                        XdmMetadataText("$severity: ${check.title}")
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth().semantics { contentDescription = "Final release warning explainer ${state.finalReleaseGateReport.summary}" }) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    XdmCardTitle("Release warning explainer")
                    XdmSupportingText(state.finalReleaseGateReport.summary)
                    val explanations = state.finalReleaseGateReport.actionableExplanations.ifEmpty {
                        state.finalReleaseGateReport.explanations.take(1)
                    }
                    explanations.take(4).forEach { explanation ->
                        XdmMetadataText("${explanation.severityLabel}: ${explanation.title}")
                        XdmMetadataText("Impact: ${explanation.impact}")
                        XdmMetadataText("Safe to ignore: ${explanation.safeToIgnore}")
                        XdmMetadataText("Fix action: ${explanation.fixAction}")
                        XdmMetadataText("Owning check: ${explanation.owner.validator} • ${explanation.owner.test}")
                    }
                    XdmSupportingText("Release warnings are engineering signals. They stay readable here without exposing raw request data, credential-bearing links, cookies, or authorization values.")
                }
            }
        }
        item { DiagnosticLine("Desktop parity", state.desktopParityReport.summary) }
        item { DiagnosticLine("Protocol coverage", state.protocolExpansionReport.summary) }
        item { DiagnosticLine("Settings exchange", "Import/export snapshot is available from Settings") }
        item { DiagnosticLine("Downloads", state.downloads.size.toString()) }
        item { DiagnosticLine("Queues", state.queues.size.toString()) }
        item { DiagnosticLine("Recovery records", state.recovery.size.toString()) }
        item { DiagnosticLine("Finalization journals", state.finalizationJournals.count { it.needsRecovery }.toString()) }
        item { DiagnosticLine("Media captures", state.mediaCaptures.size.toString()) }
        item { DiagnosticLine("Media variants", state.mediaVariants.size.toString()) }
        item { DiagnosticLine("Automation commands", state.automationCommands.size.toString()) }
        item { DiagnosticLine("Post-processing events", state.postProcessingAutomation.events.size.toString()) }
        item { DiagnosticLine("Browser origins", state.automationCommands.mapNotNull { it.originHost }.distinct().size.toString()) }
        item { DiagnosticLine("Rejected handoffs", state.automationCommands.count { it.status == AutomationCommandStatus.Rejected }.toString()) }
        item {
            BrowserIntegrationDiagnosticsCard(
                status = browserStatus,
                inbox = clipboardInbox,
                onScanClipboardText = onScanClipboardText,
                onAccept = onAcceptClipboardItem,
                onDismiss = onDismissClipboardItem,
            )
        }
        if (state.automationCommands.isNotEmpty()) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        XdmCardTitle("Recent browser handoffs")
                        state.automationCommands.take(4).forEach { command ->
                            XdmMetadataText(command.redactedDiagnosticLine())
                        }
                    }
                }
            }
        }
        item { DiagnosticLine("Native backend", "HTTP/HTTPS, checkpoints, resume and segmentation") }
        item { DiagnosticLine("Execution", "UIDT for visible Android 14+ starts; WorkManager for background/fallback; foreground dataSync on older visible starts") }
        item { DiagnosticLine("Active transfers", state.activeTransfers.activeCount.toString()) }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            XdmCardTitle("aria2 runtime")
                            XdmMetricText(state.aria2Diagnostics.status)
                        }
                        Button(
                            onClick = onRunAria2SmokeTest,
                            enabled = state.aria2Diagnostics.canRunSmokeTest,
                        ) {
                            Text(if (state.aria2Diagnostics.smokeTestRunning) "Testing…" else "Run probe")
                        }
                    }
                    XdmMetadataText(state.aria2Diagnostics.detail)
                }
            }
        }
        item {
            PostProcessingAutomationCard(
                automation = state.postProcessingAutomation,
                onEnabledChanged = null,
                onRetryFailed = onRetryPostProcessing,
                onClearEvents = onClearPostProcessingEvents,
            )
        }
        item {
            TermuxBridgeDiagnosticsCard(
                termux = state.termuxBridge,
                onRunProbe = onRunTermuxProbe,
                onRunRootProbe = onRunTermuxRootProbe,
                onCollectRootDiagnostics = onCollectRootDiagnostics,
                onKillStuckAria2WithRoot = onKillStuckAria2WithRoot,
            )
        }
        item {
            TermuxAria2CockpitCard(
                aria2 = state.termuxAria2,
                onStart = onStartTermuxAria2Daemon,
                onStop = onStopTermuxAria2Daemon,
                onProbe = onProbeTermuxAria2Daemon,
                onRefreshTasks = onRefreshTermuxAria2Tasks,
                onPauseAll = onPauseAllTermuxAria2Tasks,
                onResumeAll = onResumeAllTermuxAria2Tasks,
                onSaveSession = onSaveTermuxAria2Session,
            )
        }
        item { MediaDeveloperToolsSection(state) }
    }
}
@Composable
internal fun DiagnosticLine(label: String, value: String) {
    Card(Modifier.fillMaxWidth().semantics { contentDescription = "$label: $value" }) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            XdmCardTitle(label)
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
@Composable
internal fun BrowserIntegrationDiagnosticsCard(
    status: BrowserIntegrationStatus,
    inbox: List<ClipboardInboxItem>,
    onScanClipboardText: (String) -> Unit,
    onAccept: (ClipboardInboxItem) -> Unit,
    onDismiss: (ClipboardInboxItem) -> Unit,
) {
    val context = LocalContext.current
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    Card(Modifier.fillMaxWidth().semantics { contentDescription = "Browser integration ${status.summary}" }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    XdmCardTitle("Browser integration and clipboard inbox")
                    XdmSupportingText(status.summary)
                }
                Button(
                    onClick = {
                        val text = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                        onScanClipboardText(text)
                    },
                ) { Text("Scan clipboard") }
            }
            XdmMetadataText("Share sheet, browser VIEW handoff, typed extras, sanitized headers, duplicate command handling, and clipboard review are active.")
            if (inbox.isEmpty()) {
                XdmMetadataText("Clipboard inbox is empty.")
            } else {
                inbox.take(5).forEach { item ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            XdmMetadataText("${item.status}: ${item.title ?: hostFromUrl(item.url)}", maxLines = 1)
                            XdmMetadataText(item.url, maxLines = 1)
                        }
                        TextButton(onClick = { onAccept(item) }, enabled = item.status == "New") { Text("Add") }
                        TextButton(onClick = { onDismiss(item) }, enabled = item.status == "New") { Text("Dismiss") }
                    }
                }
            }
        }
    }
}
@Composable
internal fun TermuxBridgeDiagnosticsCard(
    termux: TermuxBridgeStatus,
    onRunProbe: () -> Unit,
    onRunRootProbe: () -> Unit,
    onCollectRootDiagnostics: () -> Unit,
    onKillStuckAria2WithRoot: () -> Unit,
) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth().semantics { contentDescription = "Termux bridge ${termux.readinessLabel}" }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    XdmCardTitle("Termux bridge")
                    XdmMetricText(termux.readinessLabel)
                }
                Button(onClick = onRunProbe, enabled = termux.canRunProbe) { Text("Probe tools") }
            }
            XdmSupportingText(termux.summary)
            XdmActionFlowRow {
                StatusPill(if (termux.termuxInstalled) "Termux installed" else "Termux missing", if (termux.termuxInstalled) XdmStatusTone.Success else XdmStatusTone.Warning)
                StatusPill(if (termux.runCommandPermissionGranted) "RUN_COMMAND ready" else "Permission needed", if (termux.runCommandPermissionGranted) XdmStatusTone.Success else XdmStatusTone.Warning)
                StatusPill(if (termux.rootAvailable) "Root available" else "Root optional", if (termux.rootAvailable) XdmStatusTone.Info else XdmStatusTone.Neutral)
            }
            termux.toolRows.forEach { row ->
                XdmMetadataText("${row.tool.displayName}: ${row.statusLabel} — ${row.versionLine}", maxLines = 2)
            }
            XdmSectionHeader("Optional root actions")
            XdmSupportingText("Root actions are launched through Termux as typed, logged operations. Root mode must be enabled before medium-risk actions can run.")
            XdmActionFlowRow {
                TextButton(onClick = onRunRootProbe, enabled = termux.canRunRootProbe) { Text("Probe root") }
                TextButton(onClick = onCollectRootDiagnostics, enabled = termux.canRunRootAction) { Text("Root diagnostics") }
                TextButton(onClick = onKillStuckAria2WithRoot, enabled = termux.canRunRootAction) { Text("Kill stuck aria2") }
            }
            XdmMetadataText(termux.lastRootMessage, maxLines = 3)
            termux.rootAudit.take(3).forEach { audit ->
                XdmMetadataText("Root audit: ${audit.summary}", maxLines = 2)
            }
            termux.recentRuns.firstOrNull()?.let { run ->
                XdmMetadataText("Last Termux run: ${run.summary} ${run.exitCode?.let { "exit $it" } ?: "pending"}", maxLines = 2)
            }
            TextButton(
                onClick = { copyTextToClipboard(context, "XDM Termux diagnostics", termux.diagnosticsSummary()) },
                modifier = Modifier.sizeIn(minWidth = 96.dp, minHeight = 48.dp),
            ) { Text("Copy Termux diagnostics") }
        }
    }
}
@Composable
internal fun TermuxBridgeSettingsCard(
    termux: TermuxBridgeStatus,
    onRunProbe: () -> Unit,
    onRunPrivacyAudit: () -> Unit,
    onOpenTermux: () -> Unit,
    onRootModeChanged: (TermuxRootMode) -> Unit,
    onRunRootProbe: () -> Unit,
    onCollectRootDiagnostics: () -> Unit,
    onKillStuckAria2WithRoot: () -> Unit,
    onFixDownloadPermissionsWithRoot: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().semantics { contentDescription = "Termux backend ${termux.readinessLabel}" }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    XdmCardTitle("External tools through Termux")
                    XdmSupportingText("Use Termux for aria2c, FFmpeg, FFprobe, yt-dlp, and Python without adding a raw shell to XDM.")
                }
                StatusPill(termux.readinessLabel, termux.statusTone())
            }
            XdmMetadataText(termux.summary)
            XdmActionFlowRow {
                Button(onClick = onRunProbe, enabled = termux.canRunProbe) { Text("Probe tools") }
                TextButton(onClick = onRunPrivacyAudit, enabled = termux.canRunProbe) { Text("Audit transient files") }
                TextButton(onClick = onOpenTermux, enabled = termux.termuxInstalled) { Text("Open Termux") }
            }
            XdmSectionHeader("Optional root mode")
            XdmSupportingText("Root is off by default and only unlocks typed file/process actions; XDM never exposes a raw root shell endpoint.")
            XdmActionFlowRow {
                TermuxRootMode.entries.forEach { mode ->
                    FilterChip(
                        selected = termux.rootMode == mode,
                        onClick = { onRootModeChanged(mode) },
                        label = { Text(mode.label) },
                    )
                }
            }
            XdmMetadataText(termux.rootMode.description)
            XdmActionFlowRow {
                TextButton(onClick = onRunRootProbe, enabled = termux.canRunRootProbe) { Text("Probe root") }
                TextButton(onClick = onCollectRootDiagnostics, enabled = termux.canRunRootAction) { Text("Root diagnostics") }
                TextButton(onClick = onKillStuckAria2WithRoot, enabled = termux.canRunRootAction) { Text("Kill stuck aria2") }
                TextButton(onClick = onFixDownloadPermissionsWithRoot, enabled = termux.canRunRootAction) { Text("Fix XDM permissions") }
            }
            termux.rootAudit.take(4).forEach { audit ->
                XdmMetadataText("Root audit: ${audit.summary}", maxLines = 2)
            }
        }
    }
}
@Composable
internal fun TermuxAria2CockpitCard(
    aria2: TermuxAria2CockpitStatus,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onProbe: () -> Unit,
    onRefreshTasks: () -> Unit,
    onPauseAll: () -> Unit,
    onResumeAll: () -> Unit,
    onSaveSession: () -> Unit,
) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth().semantics { contentDescription = "Termux aria2 cockpit ${aria2.readinessLabel}" }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    XdmCardTitle("Termux aria2 cockpit")
                    XdmMetricText(aria2.readinessLabel)
                }
                StatusPill(aria2.daemonState.label, aria2.statusTone())
            }
            XdmSupportingText("Manage a Termux-hosted aria2 RPC daemon with an app-generated secret, private session file, and typed controls.")
            aria2.config?.let { config ->
                XdmMetadataText("RPC: ${config.redactedEndpoint} • secret ${config.redactedSecret}")
                XdmMetadataText("Session: ${config.sessionFile}", maxLines = 2)
                XdmMetadataText("Downloads: ${config.downloadDir}", maxLines = 2)
            } ?: XdmMetadataText("Enable Termux aria2 in Settings to generate the RPC secret and session paths.")
            XdmActionFlowRow {
                Button(onClick = onStart, enabled = aria2.canStart) { Text("Start daemon") }
                TextButton(onClick = onStop, enabled = aria2.canStop) { Text("Stop") }
                TextButton(onClick = onProbe, enabled = aria2.canProbe) { Text("Probe RPC") }
                TextButton(onClick = onRefreshTasks, enabled = aria2.canControlTasks) { Text("Tasks") }
                TextButton(onClick = onSaveSession, enabled = aria2.canControlTasks) { Text("Save session") }
            }
            XdmActionFlowRow {
                TextButton(onClick = onPauseAll, enabled = aria2.canControlTasks) { Text("Pause all") }
                TextButton(onClick = onResumeAll, enabled = aria2.canControlTasks) { Text("Resume all") }
                TextButton(
                    onClick = { copyTextToClipboard(context, "XDM Termux aria2 diagnostics", aria2.diagnosticsSummary()) },
                    enabled = aria2.config != null,
                ) { Text("Copy aria2 diagnostics") }
            }
            XdmMetadataText("Health: ${aria2.lastHealth}", maxLines = 3)
            XdmMetadataText("Last action: ${aria2.lastAction}", maxLines = 3)
            if (aria2.taskRows.isEmpty()) {
                XdmMetadataText("No active Termux aria2 tasks have been parsed yet. Use Tasks after the daemon is running.")
            } else {
                aria2.taskRows.take(4).forEach { task ->
                    XdmMetadataText("${task.gid}: ${task.status} • ${task.progressLabel} • ${task.fileName}", maxLines = 2)
                }
            }
        }
    }
}
@Composable
internal fun TermuxAria2SettingsCard(
    aria2: TermuxAria2CockpitStatus,
    onEnabledChanged: (Boolean) -> Unit,
    onRotateSecret: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().semantics { contentDescription = "Termux aria2 backend ${aria2.readinessLabel}" }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    XdmCardTitle("Termux aria2 backend")
                    XdmSupportingText("Use a Termux-hosted aria2 RPC daemon for large, mirror-heavy or long-running jobs while keeping native Android as the fallback.")
                }
                Switch(
                    checked = aria2.enabled,
                    onCheckedChange = onEnabledChanged,
                    modifier = Modifier.semantics { stateDescription = if (aria2.enabled) "Termux aria2 enabled" else "Termux aria2 disabled" },
                )
            }
            XdmActionFlowRow {
                StatusPill(aria2.daemonState.label, aria2.statusTone())
                StatusPill(if (aria2.config != null) "Secret generated" else "No secret", if (aria2.config != null) XdmStatusTone.Success else XdmStatusTone.Neutral)
            }
            aria2.config?.let { config ->
                XdmMetadataText("Endpoint ${config.redactedEndpoint}; session and logs stay under Termux home.", maxLines = 2)
                TextButton(onClick = onRotateSecret, enabled = aria2.enabled && aria2.daemonState != TermuxAria2DaemonState.Running) { Text("Rotate RPC secret") }
            } ?: XdmMetadataText("Enable this to generate a local RPC secret, download directory, session file, and log path.")
        }
    }
}
internal fun TermuxAria2CockpitStatus.statusTone(): XdmStatusTone = when (daemonState) {
    TermuxAria2DaemonState.Running -> XdmStatusTone.Success
    TermuxAria2DaemonState.Starting, TermuxAria2DaemonState.Stopping -> XdmStatusTone.Info
    TermuxAria2DaemonState.Failed -> XdmStatusTone.Warning
    TermuxAria2DaemonState.Disabled, TermuxAria2DaemonState.Stopped -> XdmStatusTone.Neutral
}
internal fun TermuxBridgeStatus.statusTone(): XdmStatusTone = when {
    !termuxInstalled || !runCommandPermissionGranted -> XdmStatusTone.Warning
    toolRows.any { it.available } -> XdmStatusTone.Success
    else -> XdmStatusTone.Info
}
@Composable
internal fun StatusPill(text: String, tone: XdmStatusTone = XdmStatusTone.Neutral) {
    XdmStatusBadge(text, tone = tone)
}


@Composable
internal fun OfflineLibraryV2Card(dashboard: OfflineLibraryV2Dashboard) {
    Card(Modifier.fillMaxWidth().semantics { contentDescription = "Offline Library 2.0 ${dashboard.summary}" }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            XdmCardTitle("Offline Library 2.0")
            XdmSupportingText(
                "Phase 28 makes completed media filterable by video, audio, failed, playable, missing file, source host, and cleanup state with safe sidecar actions.",
                maxLines = 3,
            )
            XdmActionFlowRow {
                StatusPill("${dashboard.visibleCount} visible", tone = XdmStatusTone.Neutral)
                dashboard.playableCount.takeIf { it > 0 }?.let { StatusPill("$it playable", tone = XdmStatusTone.Success) }
                dashboard.videoCount.takeIf { it > 0 }?.let { StatusPill("$it video", tone = XdmStatusTone.Info) }
                dashboard.audioCount.takeIf { it > 0 }?.let { StatusPill("$it audio", tone = XdmStatusTone.Info) }
                dashboard.failedCount.takeIf { it > 0 }?.let { StatusPill("$it failed", tone = XdmStatusTone.Warning) }
                dashboard.missingCount.takeIf { it > 0 }?.let { StatusPill("$it missing", tone = XdmStatusTone.Warning) }
                dashboard.cleanupCount.takeIf { it > 0 }?.let { StatusPill("$it cleanup", tone = XdmStatusTone.Neutral) }
                StatusPill(if (dashboard.secretSafe) "safe export" else "redaction review", if (dashboard.secretSafe) XdmStatusTone.Success else XdmStatusTone.Warning)
            }
            XdmMetadataText("Filters: ${OfflineLibraryV2Filter.entries.joinToString { it.label }}", maxLines = 2)
            dashboard.sourceHosts.takeIf { it.isNotEmpty() }?.let { hosts -> XdmMetadataText("Source hosts: ${hosts.take(5).joinToString()}", maxLines = 2) }
            if (dashboard.empty) {
                XdmMetadataText("No offline media rows yet. Completed downloads will appear here with sidecar actions and playback handoff.", maxLines = 2)
            } else {
                dashboard.rows.take(5).forEach { row -> OfflineLibraryV2RowCard(row) }
            }
        }
    }
}

@Composable
internal fun OfflineLibraryV2RowCard(row: com.mikeyphw.xdm.android.media.OfflineLibraryV2Row) {
    XdmListCard(compact = true) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                XdmMetadataText(row.title, maxLines = 1)
                XdmSupportingText(row.summary, maxLines = 2)
                XdmMetadataText("Sidecar: ${row.sidecarFileName}", maxLines = 1)
                XdmMetadataText("Safe export: ${row.safeExportJson.take(180)}", maxLines = 2)
            }
            StatusPill(row.health.label, toneForOfflineLibraryHealth(row.health))
        }
        XdmActionFlowRow {
            row.actions.filter { it.enabled }.take(5).forEach { action ->
                StatusPill(action.kind.label, if (action.requiresConfirmation) XdmStatusTone.Warning else XdmStatusTone.Success)
            }
        }
    }
}

internal fun toneForOfflineLibraryHealth(health: OfflineLibraryV2Health): XdmStatusTone = when (health) {
    OfflineLibraryV2Health.Ready -> XdmStatusTone.Success
    OfflineLibraryV2Health.Failed,
    OfflineLibraryV2Health.MissingFile,
    OfflineLibraryV2Health.NeedsSidecarRepair -> XdmStatusTone.Warning
    OfflineLibraryV2Health.NeedsCleanup -> XdmStatusTone.Info
    OfflineLibraryV2Health.WaitingForDownload -> XdmStatusTone.Neutral
}

@Composable
internal fun PlayerDiagnosticsDeckCard(reports: List<MediaPlayerDiagnosticReport>) {
    Card(Modifier.fillMaxWidth().semantics { contentDescription = "Player diagnostics deck ${reports.size}" }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            XdmCardTitle("Player diagnostics deck")
            XdmSupportingText(
                "Phase 29 makes Media3 playback failures explainable with source, network, decoder, codec, subtitle, and protected-media buckets plus retry-prepare guidance.",
                maxLines = 3,
            )
            XdmActionFlowRow {
                StatusPill("${reports.size} reports", tone = XdmStatusTone.Neutral)
                reports.count { it.retryPrepareAvailable }.takeIf { it > 0 }?.let { StatusPill("$it retry", tone = XdmStatusTone.Info) }
                reports.count { it.protectedDiagnosticOnly }.takeIf { it > 0 }?.let { StatusPill("$it protected", tone = XdmStatusTone.Warning) }
                StatusPill(if (reports.all { it.sourceSafe }) "source-safe" else "redaction review", if (reports.all { it.sourceSafe }) XdmStatusTone.Success else XdmStatusTone.Warning)
            }
            reports.take(3).forEach { report -> PlayerDiagnosticsReportCard(report) }
            if (reports.isEmpty()) {
                XdmMetadataText("Completed direct media will expose Player 2.0 diagnostics here.", maxLines = 2)
            }
        }
    }
}

@Composable
internal fun PlayerDiagnosticsReportCard(report: MediaPlayerDiagnosticReport) {
    XdmListCard(compact = true) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                XdmMetadataText(report.title, maxLines = 1)
                XdmSupportingText(report.message, maxLines = 3)
                XdmMetadataText("Playback position: ${report.positionMemory.summary}", maxLines = 2)
                XdmMetadataText("Track availability: ${report.tracks.joinToString { it.summary }}", maxLines = 2)
                report.subtitleRows.takeIf { it.isNotEmpty() }?.let { rows -> XdmMetadataText("Subtitle availability: ${rows.joinToString { it.summary }}", maxLines = 2) }
            }
            StatusPill(report.bucket.label, toneForPlayerDiagnostic(report.bucket))
        }
        XdmActionFlowRow {
            report.actions.take(5).forEach { action -> StatusPill(action.label, if (action.label.contains("Retry")) XdmStatusTone.Info else XdmStatusTone.Neutral) }
        }
    }
}

internal fun toneForPlayerDiagnostic(bucket: MediaPlayerDiagnosticBucket): XdmStatusTone = when (bucket) {
    MediaPlayerDiagnosticBucket.Ready -> XdmStatusTone.Success
    MediaPlayerDiagnosticBucket.Network,
    MediaPlayerDiagnosticBucket.Source,
    MediaPlayerDiagnosticBucket.Subtitle -> XdmStatusTone.Info
    MediaPlayerDiagnosticBucket.Decoder,
    MediaPlayerDiagnosticBucket.UnsupportedCodec,
    MediaPlayerDiagnosticBucket.ProtectedMedia,
    MediaPlayerDiagnosticBucket.Unknown -> XdmStatusTone.Warning
}

@Composable
@UiSurface(UiAudience.Developer, "Inspect media execution, privacy, and readiness planners")
internal fun MediaDeveloperToolsSection(
    state: MainUiState,
    section: DeveloperToolSection = DeveloperToolSection.MediaPipeline,
) {
    val context = LocalContext.current
    val mediaPlanner = remember { MediaDownloadPlanner() }
    val executionPlanner = remember { MediaExecutionLibraryPlanner(mediaPlanner) }
    val dispatchPlanner = remember { MediaExecutionDispatcher() }
    val externalJobs = remember(state.termuxMediaPipeline.jobs) {
        state.termuxMediaPipeline.jobs.map { job ->
            MediaExternalJobSnapshot(
                id = job.id,
                captureId = job.captureId,
                kindLabel = job.kind.label,
                statusLabel = job.status.label,
                running = job.status in setOf(TermuxMediaJobStatus.Preparing, TermuxMediaJobStatus.Running, TermuxMediaJobStatus.Publishing),
                completed = job.status == TermuxMediaJobStatus.Completed,
                failed = job.status in setOf(TermuxMediaJobStatus.Failed, TermuxMediaJobStatus.Cancelled, TermuxMediaJobStatus.TimedOut, TermuxMediaJobStatus.RecoveryRequired),
                metadataOnly = job.kind in setOf(com.mikeyphw.xdm.android.termux.TermuxMediaJobKind.YtDlpMetadata, com.mikeyphw.xdm.android.termux.TermuxMediaJobKind.FfprobeInspect),
                attemptGeneration = job.attemptGeneration.toLong(),
                output = job.output,
                message = job.message,
            )
        }
    }
    val libraryItems = remember(state.mediaCaptures, state.downloads, state.mediaVariants, state.mediaOutputs, externalJobs) {
        executionPlanner.offlineLibraryItems(
            captures = state.mediaCaptures,
            downloads = state.downloads,
            variants = state.mediaVariants,
            outputs = state.mediaOutputs,
            externalJobs = externalJobs,
            allowLegacyFallback = false,
        )
    }
    val executionJobs = remember(state.mediaCaptures, state.downloads, state.mediaVariants, externalJobs, state.mediaOutputs) {
        executionPlanner.executionJobs(state.mediaCaptures, state.downloads, state.mediaVariants, externalJobs, state.mediaOutputs)
    }
    val dispatchPlans = remember(state.mediaCaptures, state.mediaVariants, state.termuxMediaPipeline.enabled) {
        state.mediaCaptures.map { capture ->
            val captureVariants = state.mediaVariants.filter { it.captureId == capture.id }.sortedBy { it.position }
            val selection = state.mediaTrackSelections[capture.id]
                ?: MediaTrackSelection(videoVariantId = capture.selectedVariantId)
            val spec = executionPlanner.queueSpec(capture, captureVariants, selection, "content://downloads")
            val engine = executionPlanner.enginePlan(spec, Build.VERSION.SDK_INT)
            dispatchPlanner.dispatchPlan(
                spec = spec,
                enginePlan = engine,
                capture = capture,
                termuxReady = state.termuxMediaPipeline.enabled,
            )
        }
    }
    val dispatchDashboard = remember(dispatchPlans) { dispatchPlanner.aggregate(dispatchPlans) }
    val queueTelemetry = remember(dispatchPlans, executionJobs) {
        MediaQueueTelemetryPlanner().deck(dispatchPlans, executionJobs)
    }
    val queueActions = remember(queueTelemetry, dispatchPlans, executionJobs) {
        MediaQueueActionPlanner().dashboard(queueTelemetry, dispatchPlans, executionJobs)
    }
    val workerBridge = remember(state.mediaCaptures, state.mediaVariants, state.mediaTrackSelections, state.termuxMediaPipeline.enabled, queueActions) {
        val bridgePlanner = MediaWorkerBridgePlanner()
        val actionPlanner = MediaQueueActionPlanner()
        val requests = state.mediaCaptures.map { capture ->
            val captureVariants = state.mediaVariants.filter { it.captureId == capture.id }.sortedBy { it.position }
            val selection = state.mediaTrackSelections[capture.id]
                ?: MediaTrackSelection(videoVariantId = capture.selectedVariantId)
            val spec = executionPlanner.queueSpec(capture, captureVariants, selection, "content://downloads")
            val engine = executionPlanner.enginePlan(spec, Build.VERSION.SDK_INT)
            val dispatch = dispatchPlanner.dispatchPlan(spec, engine, capture, termuxReady = state.termuxMediaPipeline.enabled)
            val actionPlan = queueActions.plans.firstOrNull { it.captureId == capture.id }
                ?: actionPlanner.actionPlan(dispatch, null)
            bridgePlanner.request(spec, engine, dispatch, actionPlan)
        }
        bridgePlanner.dashboard(requests)
    }
    val termuxRuntime = remember(workerBridge, state.termuxMediaPipeline.enabled) {
        val tools = if (state.termuxMediaPipeline.enabled) setOf("yt-dlp", "aria2c", "ffmpeg", "ffprobe") else emptySet()
        val adapter = MediaTermuxRuntimeAdapter()
        adapter.dashboard(workerBridge.requests.map { request -> adapter.launchPlan(request, availableTools = tools) })
    }
    val nativeDirect = remember(workerBridge) {
        val planner = MediaNativeDirectDownloadPlanner()
        planner.dashboard(workerBridge.requests.map { request -> planner.plan(request, destinationUri = "content://downloads") })
    }
    val libraryV2 = remember(libraryItems, queueTelemetry) {
        val cleanupIds = queueTelemetry.rows.filter { it.cleanupArmed }.map { it.captureId }.toSet()
        MediaOfflineLibraryV2Planner().dashboard(libraryItems, cleanupArmedCaptureIds = cleanupIds)
    }
    val playerDiagnostics = remember(libraryItems) {
        val planner = MediaPlayerDiagnosticsPlanner()
        libraryItems.mapNotNull { item -> item.toPlaybackCandidate()?.let(planner::report) }
    }
    val captureQuality = remember(state.mediaCaptures, state.mediaVariants) {
        MediaCaptureQualityPlanner().dashboard(state.mediaCaptures, state.mediaVariants)
    }
    val privacyAudit = remember(state.mediaCaptures, state.mediaVariants, libraryItems, executionJobs, termuxRuntime, nativeDirect, captureQuality) {
        MediaSessionPrivacyAuditPlanner().audit(
            captures = state.mediaCaptures,
            variants = state.mediaVariants,
            libraryItems = libraryItems,
            executionJobs = executionJobs,
            diagnostics = listOf(termuxRuntime.summary, nativeDirect.summary, captureQuality.summary),
            cleanupLedger = executionJobs.associate { job ->
                job.captureId to (job.stage == MediaExecutionStage.Completed || job.stage == MediaExecutionStage.Failed || job.stage == MediaExecutionStage.Blocked)
            },
            filesystemRoots = listOf(
                java.io.File(context.noBackupFilesDir, "secure-request-envelopes-v1"),
                java.io.File(context.filesDir, "browser-capture-import-journal"),
                java.io.File(context.filesDir, "browser-capture-session-index"),
                java.io.File(context.filesDir, "queue-scheduling-recovery"),
            ),
        )
    }
    val mobilePolish = remember(state.mediaCaptures, queueTelemetry, queueActions, libraryV2, playerDiagnostics, captureQuality, privacyAudit) {
        MediaMobilePolishPlanner().dashboard(
            captures = state.mediaCaptures,
            queueTelemetry = queueTelemetry,
            queueActions = queueActions,
            library = libraryV2,
            playerReports = playerDiagnostics,
            captureQuality = captureQuality,
            privacyAudit = privacyAudit,
            compactPreferred = true,
            widthClassLabel = "phone",
        )
    }
    val finalValidation = remember(mobilePolish, privacyAudit, captureQuality, playerDiagnostics, libraryV2, termuxRuntime, nativeDirect) {
        MediaFinalValidationGatePlanner().dashboard(
            currentOverlay = MediaFinalValidationGatePlanner.FinalOverlayArtifact,
            currentRoomSchemaVersion = 20,
            mediaMobilePolish = mobilePolish,
            privacyAudit = privacyAudit,
            captureQuality = captureQuality,
            playerReports = playerDiagnostics,
            library = libraryV2,
            termuxRuntime = termuxRuntime,
            nativeDirect = nativeDirect,
            staticValidationPassed = BuildConfig.XDM_STATIC_VALIDATION_PASSED,
            fullValidationPassed = BuildConfig.XDM_FULL_VALIDATION_PASSED,
            noNewTopLevelRoutes = BuildConfig.XDM_STATIC_VALIDATION_PASSED,
            keepDebugSymbolsProtected = BuildConfig.XDM_STATIC_VALIDATION_PASSED,
            warningsAsErrors = BuildConfig.XDM_STATIC_VALIDATION_PASSED,
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        XdmSectionHeader(section.label)
        XdmSupportingText(
            when (section) {
                DeveloperToolSection.MediaPipeline -> "Resolver, execution queue, offline library, capture quality, and player readiness."
                DeveloperToolSection.DispatchWorkers -> "Dispatch plans, queue telemetry, worker requests, Termux launches, and native direct-download plans."
                DeveloperToolSection.PrivacyCleanup -> "Session redaction, cleanup readiness, capture quality, and offline-library health."
                DeveloperToolSection.ValidationRelease -> "Media release gates, mobile polish checks, warnings-as-errors readiness, and final validation."
                else -> "Redacted media diagnostics."
            },
            maxLines = 4,
        )
        when (section) {
            DeveloperToolSection.MediaPipeline -> {
                MediaExecutionQueueCard(executionJobs)
                MediaCaptureQualityCard(captureQuality)
                OfflineLibraryV2Card(libraryV2)
                PlayerDiagnosticsDeckCard(playerDiagnostics)
            }
            DeveloperToolSection.DispatchWorkers -> {
                MediaDispatchDashboardCard(dispatchDashboard, dispatchPlans)
                MediaQueueTelemetryCard(queueTelemetry)
                MediaQueueActionsCard(queueActions)
                MediaWorkerBridgeCard(workerBridge)
                MediaTermuxRuntimeAdapterCard(termuxRuntime)
                MediaNativeDirectDownloadEngineCard(nativeDirect)
            }
            DeveloperToolSection.PrivacyCleanup -> {
                SessionPrivacyAuditCard(privacyAudit)
                MediaCaptureQualityCard(captureQuality)
                OfflineLibraryV2Card(libraryV2)
            }
            DeveloperToolSection.ValidationRelease -> {
                MediaFinalValidationGateCard(finalValidation)
                MediaMobilePolishCard(mobilePolish)
                SessionPrivacyAuditCard(privacyAudit)
            }
            else -> Unit
        }
    }
}



@Composable
internal fun MetadataProbePreviewCard(preview: YtDlpMetadataProbeResult, plan: MediaDownloadPlan) {
    XdmListCard(compact = true) {
        XdmMetadataText("yt-dlp metadata preview")
        XdmSupportingText(preview.summary, maxLines = 2)
        XdmActionFlowRow {
            preview.thumbnailUrl?.takeIf { it.isNotBlank() }?.let { StatusPill("Thumbnail", tone = XdmStatusTone.Info) }
            preview.durationMs?.let { StatusPill(preview.durationLabel, tone = XdmStatusTone.Neutral) }
            StatusPill("Probe ${if (preview.webpageUrl != null) "page" else "stream"}", tone = XdmStatusTone.Info)
            plan.ytDlpFormatSelector?.let { StatusPill("Format selector", tone = XdmStatusTone.Neutral) }
        }
        plan.ytDlpFormatSelector?.let { XdmMetadataText("yt-dlp format: $it", maxLines = 2) }
    }
}

@Composable
internal fun SessionHandoffCard(plan: MediaDownloadPlan) {
    XdmListCard(compact = true) {
        XdmMetadataText("Cookie/header session handoff")
        XdmSupportingText(
            if (plan.sessionHandoff.needsSession) "Resolver will forward referer/header context to yt-dlp or aria2 while diagnostics stay redacted."
            else "No page cookies or special headers were detected for this capture.",
            maxLines = 3,
        )
        XdmMetadataText(plan.sessionHandoff.redactedSummary, maxLines = 3)
    }
}

@Composable
internal fun MediaDispatchRunbookCard(plan: MediaDispatchPlan) {
    XdmListCard(compact = true) {
        XdmMetadataText("Dispatch runbook")
        XdmSupportingText("Safe dispatch is gated by readiness, selected lane, retry policy, progress signals, redacted diagnostics, and terminal cleanup.", maxLines = 3)
        XdmActionFlowRow {
            StatusPill(plan.readiness.label, toneForDispatchReadiness(plan.readiness))
            StatusPill(plan.primaryActionLabel, if (plan.queueButtonEnabled) XdmStatusTone.Success else XdmStatusTone.Neutral)
            StatusPill("${plan.steps.size} steps", tone = XdmStatusTone.Neutral)
            StatusPill("retry ${plan.retryPolicy.maxAttempts}", tone = XdmStatusTone.Info)
        }
        XdmMetadataText(plan.safeDiagnostics, maxLines = 4)
        plan.steps.take(3).forEach { step ->
            XdmMetadataText("${step.kind.label}: ${step.title}", maxLines = 1)
        }
    }
}

@Composable
internal fun MediaEngineHardeningCard(plan: MediaExecutionEnginePlan) {
    XdmListCard(compact = true) {
        XdmMetadataText("Download engine hardening")
        XdmSupportingText(
            "UIDT / WorkManager fallback / foreground service policy is selected before queueing, with transient cookie and aria2 files cleaned after terminal state.",
            maxLines = 3,
        )
        XdmActionFlowRow {
            StatusPill(plan.lane.label, tone = XdmStatusTone.Info)
            StatusPill(plan.backgroundPolicy.workKind.label, tone = XdmStatusTone.Neutral)
            plan.tempCookieFile?.let { StatusPill("Netscape cookie temp file", tone = XdmStatusTone.Warning) }
            plan.aria2Input?.let { StatusPill("aria2 transient input/session", tone = XdmStatusTone.Info) }
            StatusPill(if (plan.leakReport.safe) "No cookie leaks" else "Review leaks", if (plan.leakReport.safe) XdmStatusTone.Success else XdmStatusTone.Warning)
        }
        XdmMetadataText("Cleanup verified: ${plan.cleanupActions.joinToString()}", maxLines = 2)
    }
}

@Composable
internal fun ProtectedMediaDiagnosticsCard(plan: MediaDownloadPlan) {
    XdmListCard(compact = true) {
        XdmMetadataText("Protected media diagnostics")
        XdmSupportingText(plan.protectedDiagnostic.reason, maxLines = 3)
        XdmActionFlowRow {
            StatusPill(plan.protectedDiagnostic.label, if (plan.protectedDiagnostic.protected) XdmStatusTone.Warning else XdmStatusTone.Neutral)
            plan.protectedDiagnostic.scheme?.let { StatusPill(it, tone = XdmStatusTone.Warning) }
        }
        XdmMetadataText(plan.protectedDiagnostic.allowedAction, maxLines = 2)
    }
}

@Composable
internal fun TermuxMediaPipelineCard(
    pipeline: TermuxMediaPipelineStatus,
    onClearCompleted: () -> Unit,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onCancel: (String) -> Unit,
    onForceCancel: (String) -> Unit,
    onRetry: (String) -> Unit,
    onRecoverPublication: (String) -> Unit,
) {
    Card(Modifier.fillMaxWidth().semantics { contentDescription = "Termux media pipeline ${pipeline.readinessLabel}" }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    XdmCardTitle("Durable Termux post-processing")
                    XdmMetricText(pipeline.readinessLabel)
                }
                if (pipeline.jobs.any { it.status.terminal }) {
                    TextButton(onClick = onClearCompleted) { Text("Clear manual history") }
                }
            }
            XdmSupportingText(
                "Automatic claims, immutable retry attempts, exact process ownership, timeout, progress, bridge artifacts, and transactional output publication survive app process death.",
                maxLines = 4,
            )
            XdmMetadataText(pipeline.lastAction, maxLines = 3)
            pipeline.recentJobs.take(8).forEach { job ->
                XdmListCard(compact = true) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            XdmCardTitle(job.kind.label, maxLines = 1)
                            XdmMetadataText("${job.title} • attempt ${job.attemptGeneration}", maxLines = 1)
                            XdmMetadataText(job.message.ifBlank { job.output }, maxLines = 3)
                        }
                        val tone = when (job.status) {
                            TermuxMediaJobStatus.Completed -> XdmStatusTone.Success
                            TermuxMediaJobStatus.Failed, TermuxMediaJobStatus.TimedOut, TermuxMediaJobStatus.RecoveryRequired -> XdmStatusTone.Warning
                            TermuxMediaJobStatus.Cancelled -> XdmStatusTone.Neutral
                            else -> XdmStatusTone.Info
                        }
                        StatusPill(job.status.label, tone)
                    }
                    if (!job.status.terminal && job.status != TermuxMediaJobStatus.RecoveryRequired) {
                        LinearProgressIndicator(
                            progress = { job.progressPercent.coerceIn(0, 100) / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        XdmMetadataText(job.progressLabel)
                    }
                    val ownership = buildList {
                        job.processId?.let { add("PID $it") }
                        job.runId.takeIf(String::isNotBlank)?.let { add("run $it") }
                        job.processToken.takeIf(String::isNotBlank)?.let { add("owner ${it.take(8)}…") }
                    }
                    if (ownership.isNotEmpty()) XdmMetadataText(ownership.joinToString(" • "), maxLines = 2)
                    XdmActionFlowRow {
                        when (job.status) {
                            TermuxMediaJobStatus.Running -> {
                                TextButton(onClick = { onPause(job.id) }) { Text("Pause") }
                                TextButton(onClick = { onCancel(job.id) }) { Text("Cancel") }
                            }
                            TermuxMediaJobStatus.WaitingForPrerequisites,
                            TermuxMediaJobStatus.Preparing,
                            TermuxMediaJobStatus.Queued ->
                                TextButton(onClick = { onCancel(job.id) }) { Text("Cancel") }
                            TermuxMediaJobStatus.Paused -> {
                                Button(onClick = { onResume(job.id) }) { Text("Resume") }
                                TextButton(onClick = { onCancel(job.id) }) { Text("Cancel") }
                            }
                            TermuxMediaJobStatus.Publishing ->
                                XdmMetadataText("Destination commit is in progress; controls resume after reconciliation.")
                            TermuxMediaJobStatus.Cancelling ->
                                TextButton(onClick = { onForceCancel(job.id) }) { Text("Force owned process") }
                            TermuxMediaJobStatus.Failed, TermuxMediaJobStatus.Cancelled, TermuxMediaJobStatus.TimedOut ->
                                Button(onClick = { onRetry(job.id) }) { Text("New attempt") }
                            TermuxMediaJobStatus.RecoveryRequired -> {
                                Button(onClick = { onRecoverPublication(job.id) }) { Text("Publish staged output") }
                                TextButton(onClick = { onRetry(job.id) }) { Text("New attempt") }
                            }
                            TermuxMediaJobStatus.Completed -> Unit
                        }
                    }
                }
            }
        }
    }
}
