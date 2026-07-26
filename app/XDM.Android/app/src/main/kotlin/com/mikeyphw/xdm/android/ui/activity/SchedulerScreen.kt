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
@UiSurface(UiAudience.Advanced, "Manage scheduled transfer rules")
fun SchedulerScreen(
    rules: List<ScheduleRule>,
    queues: List<QueueDefinition>,
    queueIntelligence: QueueIntelligenceSummary,
    onCreateSchedule: (String, String?, String) -> Unit,
    onUpdateSchedule: (ScheduleRule, String, String?, Boolean, String) -> Unit,
    onToggleSchedule: (ScheduleRule, Boolean) -> Unit,
    onDeleteSchedule: (ScheduleRule) -> Unit,
    onEvaluateNow: () -> Unit,
) {
    var newScheduleName by remember { mutableStateOf("") }
    var selectedQueueId by remember { mutableStateOf<String?>(queues.firstOrNull()?.id) }
    var networkRequirement by remember { mutableStateOf(QueueNetworkRequirement.Unmetered) }
    var chargingRequired by remember { mutableStateOf(false) }
    var minimumBattery by remember { mutableStateOf("30") }
    var retryStrategy by remember { mutableStateOf(QueueRetryStrategy.Balanced) }
    var maxAutoRetries by remember { mutableStateOf("4") }
    var stopOnStoragePressure by remember { mutableStateOf(true) }
    var minimumFreeStorageMb by remember { mutableStateOf("512") }
    var startTime by remember { mutableStateOf("01:00") }
    var endTime by remember { mutableStateOf("06:00") }
    var days by remember { mutableStateOf("Weekdays") }
    val createConstraints = buildScheduleConstraintsJson(
        days, startTime, endTime, networkRequirement, chargingRequired, minimumBattery,
        retryStrategy, maxAutoRetries, stopOnStoragePressure, minimumFreeStorageMb,
    )

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            XdmListCard(compact = true) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        XdmCardTitle("Queue intelligence")
                        XdmSupportingText(queueIntelligence.message, maxLines = 3)
                    }
                    Button(onClick = onEvaluateNow) { Text("Evaluate now") }
                }
                XdmMetadataText("Automatic evaluation also runs after queue or schedule changes, app startup, reboot, and periodic background checks.")
            }
        }
        item {
            XdmListCard {
                XdmCardTitle("Create schedule")
                XdmSupportingText("Schedules enable queues automatically when time, power, battery, and network conditions line up.")
                OutlinedTextField(
                    value = newScheduleName,
                    onValueChange = { newScheduleName = it.take(48) },
                    label = { Text("Schedule name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                QueuePicker(queues = queues, selectedQueueId = selectedQueueId, onSelected = { selectedQueueId = it })
                ScheduleConditionEditor(
                    days = days,
                    onDaysChanged = { days = it },
                    startTime = startTime,
                    onStartTimeChanged = { startTime = it.take(5) },
                    endTime = endTime,
                    onEndTimeChanged = { endTime = it.take(5) },
                    networkRequirement = networkRequirement,
                    onNetworkRequirementChanged = { networkRequirement = it },
                    chargingRequired = chargingRequired,
                    onChargingRequiredChanged = { chargingRequired = it },
                    minimumBattery = minimumBattery,
                    onMinimumBatteryChanged = { minimumBattery = it.filter { char -> char.isDigit() }.take(3) },
                    retryStrategy = retryStrategy,
                    onRetryStrategyChanged = { retryStrategy = it },
                    maxAutoRetries = maxAutoRetries,
                    onMaxAutoRetriesChanged = { maxAutoRetries = it.filter(Char::isDigit).take(2) },
                    stopOnStoragePressure = stopOnStoragePressure,
                    onStopOnStoragePressureChanged = { stopOnStoragePressure = it },
                    minimumFreeStorageMb = minimumFreeStorageMb,
                    onMinimumFreeStorageMbChanged = { minimumFreeStorageMb = it.filter(Char::isDigit).take(5) },
                )
                Button(
                    onClick = {
                        onCreateSchedule(newScheduleName, selectedQueueId, createConstraints)
                        newScheduleName = ""
                    },
                ) { Text("Create schedule") }
            }
        }
        if (rules.isEmpty()) {
            item {
                XdmListCard {
                    XdmCardTitle("No schedules")
                    XdmSupportingText("Create a schedule to run queues only during safe windows, such as Wi‑Fi while charging overnight.")
                }
            }
        } else {
            items(rules, key = ScheduleRule::id) { rule ->
                ScheduleManagementCard(
                    rule = rule,
                    queues = queues,
                    onUpdateSchedule = onUpdateSchedule,
                    onToggleSchedule = onToggleSchedule,
                    onDeleteSchedule = onDeleteSchedule,
                )
            }
        }
    }
}
@Composable
internal fun QueuePicker(queues: List<QueueDefinition>, selectedQueueId: String?, onSelected: (String?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        XdmMetadataText("Queue")
        XdmActionFlowRow {
            FilterChip(selected = selectedQueueId == null, onClick = { onSelected(null) }, label = { Text("All queues") })
            queues.forEach { queue ->
                FilterChip(selected = selectedQueueId == queue.id, onClick = { onSelected(queue.id) }, label = { Text(queue.name) })
            }
        }
    }
}
@Composable
internal fun ScheduleConditionEditor(
    days: String,
    onDaysChanged: (String) -> Unit,
    startTime: String,
    onStartTimeChanged: (String) -> Unit,
    endTime: String,
    onEndTimeChanged: (String) -> Unit,
    networkRequirement: QueueNetworkRequirement,
    onNetworkRequirementChanged: (QueueNetworkRequirement) -> Unit,
    chargingRequired: Boolean,
    onChargingRequiredChanged: (Boolean) -> Unit,
    minimumBattery: String,
    onMinimumBatteryChanged: (String) -> Unit,
    retryStrategy: QueueRetryStrategy,
    onRetryStrategyChanged: (QueueRetryStrategy) -> Unit,
    maxAutoRetries: String,
    onMaxAutoRetriesChanged: (String) -> Unit,
    stopOnStoragePressure: Boolean,
    onStopOnStoragePressureChanged: (Boolean) -> Unit,
    minimumFreeStorageMb: String,
    onMinimumFreeStorageMbChanged: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(days, onDaysChanged, label = { Text("Days") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                startTime,
                onStartTimeChanged,
                label = { Text("Start") },
                modifier = Modifier.weight(1f),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            OutlinedTextField(
                endTime,
                onEndTimeChanged,
                label = { Text("End") },
                modifier = Modifier.weight(1f),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }
        XdmMetadataText("Network")
        XdmActionFlowRow {
            QueueNetworkRequirement.entries.forEach { requirement ->
                FilterChip(
                    selected = networkRequirement == requirement,
                    onClick = { onNetworkRequirementChanged(requirement) },
                    label = { Text(requirement.label) },
                )
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            XdmSupportingText("Charging required", modifier = Modifier.weight(1f))
            Switch(checked = chargingRequired, onCheckedChange = onChargingRequiredChanged)
        }
        OutlinedTextField(
            minimumBattery,
            onMinimumBatteryChanged,
            label = { Text("Minimum battery %") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            supportingText = { Text("Leave blank to ignore battery level.") },
        )
        XdmMetadataText("Automatic retry")
        XdmActionFlowRow {
            QueueRetryStrategy.entries.forEach { strategy ->
                FilterChip(
                    selected = retryStrategy == strategy,
                    onClick = { onRetryStrategyChanged(strategy) },
                    label = { Text(strategy.label) },
                )
            }
        }
        OutlinedTextField(
            maxAutoRetries,
            onMaxAutoRetriesChanged,
            label = { Text("Maximum automatic retries") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            supportingText = { Text("Use 0–12. Authentication, permission, checksum, unsupported, and DRM failures still require review.") },
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            XdmSupportingText("Pause under storage pressure", modifier = Modifier.weight(1f))
            Switch(checked = stopOnStoragePressure, onCheckedChange = onStopOnStoragePressureChanged)
        }
        OutlinedTextField(
            minimumFreeStorageMb,
            onMinimumFreeStorageMbChanged,
            label = { Text("Storage reserve (MB)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            enabled = stopOnStoragePressure,
            supportingText = { Text("Queued transfers pause when app storage falls below this reserve.") },
        )
    }
}
@Composable
internal fun ScheduleManagementCard(
    rule: ScheduleRule,
    queues: List<QueueDefinition>,
    onUpdateSchedule: (ScheduleRule, String, String?, Boolean, String) -> Unit,
    onToggleSchedule: (ScheduleRule, Boolean) -> Unit,
    onDeleteSchedule: (ScheduleRule) -> Unit,
) {
    var editing by remember(rule.id) { mutableStateOf(false) }
    var draftName by remember(rule.id, editing) { mutableStateOf(rule.name) }
    var draftQueueId by remember(rule.id, editing) { mutableStateOf(rule.queueId) }
    var draftEnabled by remember(rule.id, editing) { mutableStateOf(rule.enabled) }
    var draftDays by remember(rule.id, editing) { mutableStateOf(scheduleString(rule.constraintsJson, "days", "Every day")) }
    var draftStart by remember(rule.id, editing) { mutableStateOf(scheduleString(rule.constraintsJson, "startTime", "")) }
    var draftEnd by remember(rule.id, editing) { mutableStateOf(scheduleString(rule.constraintsJson, "endTime", "")) }
    var draftNetworkRequirement by remember(rule.id, editing) { mutableStateOf(scheduleNetworkRequirement(rule.constraintsJson)) }
    var draftCharging by remember(rule.id, editing) { mutableStateOf(scheduleBoolean(rule.constraintsJson, "charging", false)) }
    var draftBattery by remember(rule.id, editing) { mutableStateOf(scheduleInt(rule.constraintsJson, "minimumBatteryPercent")?.toString().orEmpty()) }
    var draftRetryStrategy by remember(rule.id, editing) { mutableStateOf(scheduleRetryStrategy(rule.constraintsJson)) }
    var draftMaxAutoRetries by remember(rule.id, editing) { mutableStateOf(scheduleInt(rule.constraintsJson, "maxAutoRetries")?.toString() ?: "4") }
    var draftStopOnStoragePressure by remember(rule.id, editing) { mutableStateOf(scheduleBoolean(rule.constraintsJson, "stopOnStoragePressure", true)) }
    var draftMinimumFreeStorageMb by remember(rule.id, editing) { mutableStateOf(scheduleInt(rule.constraintsJson, "minimumFreeStorageMb")?.toString() ?: "512") }
    val draftConstraints = buildScheduleConstraintsJson(
        draftDays, draftStart, draftEnd, draftNetworkRequirement, draftCharging, draftBattery,
        draftRetryStrategy, draftMaxAutoRetries, draftStopOnStoragePressure, draftMinimumFreeStorageMb,
    )

    XdmListCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                XdmCardTitle(rule.name)
                XdmMetadataText(rule.queueId?.let { id -> "Queue: ${queues.firstOrNull { it.id == id }?.name ?: id}" } ?: "All queues")
                XdmMetadataText(nextRunSummary(rule.enabled, rule.constraintsJson))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusPill(enabledLabel(rule.enabled), enabledTone(rule.enabled))
                Switch(
                    checked = rule.enabled,
                    onCheckedChange = { onToggleSchedule(rule, it) },
                    modifier = Modifier.semantics { stateDescription = if (rule.enabled) "Schedule enabled" else "Schedule disabled" },
                )
            }
        }
        scheduleConstraintSummary(rule.constraintsJson).forEach { summary -> XdmMetadataText(summary) }
        if (editing) {
            OutlinedTextField(draftName, { draftName = it.take(48) }, label = { Text("Schedule name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            QueuePicker(queues = queues, selectedQueueId = draftQueueId, onSelected = { draftQueueId = it })
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                XdmSupportingText("Enabled", modifier = Modifier.weight(1f))
                Switch(checked = draftEnabled, onCheckedChange = { draftEnabled = it })
            }
            ScheduleConditionEditor(
                days = draftDays,
                onDaysChanged = { draftDays = it },
                startTime = draftStart,
                onStartTimeChanged = { draftStart = it.take(5) },
                endTime = draftEnd,
                onEndTimeChanged = { draftEnd = it.take(5) },
                networkRequirement = draftNetworkRequirement,
                onNetworkRequirementChanged = { draftNetworkRequirement = it },
                chargingRequired = draftCharging,
                onChargingRequiredChanged = { draftCharging = it },
                minimumBattery = draftBattery,
                onMinimumBatteryChanged = { draftBattery = it.filter { char -> char.isDigit() }.take(3) },
                retryStrategy = draftRetryStrategy,
                onRetryStrategyChanged = { draftRetryStrategy = it },
                maxAutoRetries = draftMaxAutoRetries,
                onMaxAutoRetriesChanged = { draftMaxAutoRetries = it.filter(Char::isDigit).take(2) },
                stopOnStoragePressure = draftStopOnStoragePressure,
                onStopOnStoragePressureChanged = { draftStopOnStoragePressure = it },
                minimumFreeStorageMb = draftMinimumFreeStorageMb,
                onMinimumFreeStorageMbChanged = { draftMinimumFreeStorageMb = it.filter(Char::isDigit).take(5) },
            )
            XdmActionFlowRow {
                Button(
                    onClick = {
                        onUpdateSchedule(rule, draftName, draftQueueId, draftEnabled, draftConstraints)
                        editing = false
                    },
                    enabled = draftName.isNotBlank(),
                ) { Text("Save schedule") }
                TextButton(onClick = { editing = false }) { Text("Cancel") }
                TextButton(onClick = { onDeleteSchedule(rule); editing = false }) { Text("Delete schedule") }
            }
        } else {
            XdmActionFlowRow {
                TextButton(onClick = { editing = true }) { Text("Edit") }
                TextButton(onClick = { onDeleteSchedule(rule) }) { Text("Delete") }
            }
        }
    }
}
