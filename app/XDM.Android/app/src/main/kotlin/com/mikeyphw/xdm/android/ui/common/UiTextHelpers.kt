package com.mikeyphw.xdm.android

import android.content.ClipData
import android.os.Looper
import android.os.Handler
import android.os.PersistableBundle
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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



internal fun Download.matchesQuery(query: String): Boolean {
    val needle = query.trim().lowercase()
    if (needle.isBlank()) return true
    return listOf(fileName, sourceUrl, destinationUri, userLabel.orEmpty(), backend.uiLabel(), state.uiLabel())
        .any { it.lowercase().contains(needle) }
}
internal fun firstDownloadUrlFromClipboard(context: Context): String? {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return null
    val clip = clipboard.primaryClip ?: return null
    for (index in 0 until clip.itemCount) {
        val item = clip.getItemAt(index)
        ExternalUrlPolicy.normalizedUrl(item.uri?.toString())?.let { return it }
        val text = item.coerceToText(context)?.toString().orEmpty()
        ExternalUrlPolicy.urlsInText(text).firstOrNull()?.let { return it }
        ExternalUrlPolicy.normalizedUrl(text)?.let { return it }
    }
    return null
}
internal fun copyTextToClipboard(context: Context, label: String, value: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard?.setPrimaryClip(ClipData.newPlainText(label, value))
}

internal fun copySensitiveTextToClipboard(
    context: Context,
    label: String,
    value: String,
    clearAfterMs: Long = 60_000L,
) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    val clip = ClipData.newPlainText(label, value)
    val extras = PersistableBundle().apply {
        val key = if (android.os.Build.VERSION.SDK_INT >= 33) {
            ClipDescription.EXTRA_IS_SENSITIVE
        } else {
            "android.content.extra.IS_SENSITIVE"
        }
        putBoolean(key, true)
    }
    clip.description.extras = extras
    clipboard.setPrimaryClip(clip)
    if (clearAfterMs > 0L) {
        Handler(Looper.getMainLooper()).postDelayed({
            val current = clipboard.primaryClip
            if (current != null && current.itemCount == 1 && current.getItemAt(0).text?.toString() == value) {
                if (android.os.Build.VERSION.SDK_INT >= 28) clipboard.clearPrimaryClip()
                else clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        }, clearAfterMs)
    }
}

internal fun shareTextReport(context: Context, title: String, value: String) {
    val intent = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, title)
        .putExtra(Intent.EXTRA_TEXT, value)
    context.startActivity(Intent.createChooser(intent, title))
}
internal fun Download.accessibilitySummary(): String = buildString {
    append(fileName)
    append(", ")
    append(state.uiLabel().lowercase())
    append(" using ")
    append(backend.uiLabel())
    totalBytes?.let { total ->
        append(", ")
        append(bytesReceived.formatBytes())
        append(" of ")
        append(total.formatBytes())
    }
}
internal fun Download.progressAccessibilitySummary(): String {
    val total = totalBytes ?: return "Progress unavailable"
    val percent = (progressFraction * 100).toInt().coerceIn(0, 100)
    return "$percent percent, ${bytesReceived.formatBytes()} of ${total.formatBytes()}"
}
internal fun buildScheduleConstraintsJson(
    days: String,
    startTime: String,
    endTime: String,
    networkRequirement: QueueNetworkRequirement,
    chargingRequired: Boolean,
    minimumBattery: String,
    retryStrategy: QueueRetryStrategy,
    maxAutoRetries: String,
    stopOnStoragePressure: Boolean,
    minimumFreeStorageMb: String,
): String {
    val json = JSONObject()
    days.trim().takeIf(String::isNotBlank)?.let { json.put("days", it) }
    startTime.trim().takeIf(String::isNotBlank)?.let { json.put("startTime", it) }
    endTime.trim().takeIf(String::isNotBlank)?.let { json.put("endTime", it) }
    json.put("networkRequirement", networkRequirement.name.lowercase())
    if (networkRequirement == QueueNetworkRequirement.Unmetered) json.put("unmetered", true)
    if (networkRequirement == QueueNetworkRequirement.Wifi) json.put("wifiOnly", true)
    if (chargingRequired) json.put("charging", true)
    minimumBattery.toIntOrNull()?.coerceIn(1, 100)?.let { json.put("minimumBatteryPercent", it) }
    json.put("retryStrategy", retryStrategy.name.lowercase())
    json.put("maxAutoRetries", maxAutoRetries.toIntOrNull()?.coerceIn(0, 12) ?: 4)
    json.put("stopOnStoragePressure", stopOnStoragePressure)
    minimumFreeStorageMb.toIntOrNull()?.coerceIn(128, 16_384)?.let { json.put("minimumFreeStorageMb", it) }
    return json.toString()
}
internal fun scheduleBoolean(rawJson: String, key: String, default: Boolean): Boolean = runCatching {
    JSONObject(rawJson).optBoolean(key, default)
}.getOrDefault(default)
internal fun scheduleString(rawJson: String, key: String, default: String): String = runCatching {
    JSONObject(rawJson).optString(key).ifBlank { default }
}.getOrDefault(default)
internal fun scheduleInt(rawJson: String, key: String): Int? = runCatching {
    JSONObject(rawJson).takeIf { it.has(key) }?.optInt(key)
}.getOrNull()
internal fun scheduleNetworkRequirement(rawJson: String): QueueNetworkRequirement = runCatching {
    val json = JSONObject(rawJson)
    when {
        json.optBoolean("wifiOnly", false) -> QueueNetworkRequirement.Wifi
        json.optBoolean("unmetered", false) || json.optBoolean("unmeteredOnly", false) -> QueueNetworkRequirement.Unmetered
        else -> QueueNetworkRequirement.entries.firstOrNull { it.name.equals(json.optString("networkRequirement"), ignoreCase = true) }
            ?: QueueNetworkRequirement.Any
    }
}.getOrDefault(QueueNetworkRequirement.Any)
internal fun scheduleRetryStrategy(rawJson: String): QueueRetryStrategy = runCatching {
    val value = JSONObject(rawJson).optString("retryStrategy", "balanced")
    QueueRetryStrategy.entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: QueueRetryStrategy.Balanced
}.getOrDefault(QueueRetryStrategy.Balanced)
internal fun nextRunSummary(enabled: Boolean, rawJson: String): String {
    if (!enabled) return "Disabled; it will not run until enabled."
    val start = scheduleString(rawJson, "startTime", "")
    val end = scheduleString(rawJson, "endTime", "")
    val days = scheduleString(rawJson, "days", "")
    val window = when {
        start.isNotBlank() && end.isNotBlank() -> "$start–$end"
        start.isNotBlank() -> "after $start"
        else -> "when conditions match"
    }
    return listOf(days.takeIf(String::isNotBlank), "Next eligible window: $window").filterNotNull().joinToString(" • ")
}
internal fun mediaOriginLabel(capture: MediaCaptureRecord): String = listOfNotNull(
    capture.pageUrl?.let(::hostFromUrl),
    hostFromUrl(capture.sourceUrl),
).distinct().joinToString(" • ").ifBlank { "Captured media" }
internal fun hostFromUrl(url: String): String = runCatching {
    url.substringAfter("://", url).substringBefore('/').substringBefore('?').ifBlank { url }
}.getOrDefault(url)
internal fun formatDurationSeconds(durationMs: Long): String {
    val totalSeconds = (durationMs / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"
}
internal fun variantDetails(variant: MediaVariant): String = listOfNotNull(
    variant.mimeType,
    variant.width?.let { width -> variant.height?.let { height -> "${width}×${height}" } },
    variant.bitrateBitsPerSecond?.takeIf { it > 0 }?.let { "${it / 1000} kbps" },
    variant.codecs,
    variant.language?.takeIf(String::isNotBlank)?.let { "Language: $it" },
).joinToString(" • ").ifBlank { variant.kind.name.lowercase().replaceFirstChar { it.titlecase() } }
internal fun recoveryProblemTitle(record: RecoveryRecord): String = when (record.classification) {
    RecoveryClassification.ReadyToResume -> "Interrupted download can resume"
    RecoveryClassification.NeedsRemoteValidation -> "Download needs remote validation"
    RecoveryClassification.NeedsRepair -> "Partial file needs repair"
    RecoveryClassification.MissingPartialFile -> "Partial file is missing"
    RecoveryClassification.RemoteFileChanged -> "Remote file changed"
    RecoveryClassification.CompletionRecovered -> "Completed file was recovered"
    RecoveryClassification.FinalizationInterrupted -> "Finishing was interrupted"
    RecoveryClassification.BackendTaskOrphaned -> "Backend task lost its owner"
    RecoveryClassification.OrphanedArtifact -> "Untracked partial file found"
}
internal fun recoveryRecommendedExplanation(record: RecoveryRecord): String = when (record.recommendedAction) {
    RecoveryAction.Resume -> "The partial data looks reusable. Resume keeps existing bytes and continues safely."
    RecoveryAction.Validate -> "Validate checks the partial data before XDM decides whether it can be reused."
    RecoveryAction.VerifyAndRepair -> "XDM should verify trusted blocks and repair only the damaged range when possible."
    RecoveryAction.RestartFromZero -> "Restart discards reuse assumptions and creates a fresh backend task."
    RecoveryAction.AdoptOrphan -> "Adopt links this artifact to a managed download only after validation."
    RecoveryAction.LocateFile -> "Locate the missing file before any resume or repair action."
    RecoveryAction.RemoveRecord -> "Remove only clears the recovery warning; it does not delete user files."
}
internal fun recoveryPrimaryActionLabel(record: RecoveryRecord): String = when (record.recommendedAction) {
    RecoveryAction.Resume -> "Resume download"
    RecoveryAction.Validate -> "Validate safely"
    RecoveryAction.VerifyAndRepair -> "Verify and repair"
    RecoveryAction.RestartFromZero -> "Restart download"
    RecoveryAction.AdoptOrphan -> "Validate and adopt"
    RecoveryAction.LocateFile -> "Locate file"
    RecoveryAction.RemoveRecord -> "Review record"
}
internal fun scheduleConstraintSummary(rawJson: String): List<String> {
    if (rawJson.isBlank() || rawJson.trim() == "{}") return listOf("No additional conditions")
    return runCatching {
        val json = JSONObject(rawJson)
        buildList {
            json.optString("days").takeIf { it.isNotBlank() }?.let { add("Days: ${humanizeValue(it)}") }
            json.optString("startTime").takeIf { it.isNotBlank() }?.let { start ->
                val end = json.optString("endTime").takeIf { it.isNotBlank() }
                add(if (end == null) "Starts at $start" else "Time: $start–$end")
            }
            when {
                json.optBoolean("wifiOnly", false) -> add("Network: Wi-Fi only")
                json.optBoolean("unmetered", false) || json.optBoolean("unmeteredOnly", false) -> add("Network: Unmetered only")
                json.optString("networkRequirement").isNotBlank() -> add("Network: ${humanizeValue(json.optString("networkRequirement"))}")
            }
            if (json.optBoolean("charging", false) || json.optBoolean("requiresCharging", false)) add("Power: Charging required")
            json.optInt("minimumBatteryPercent", -1).takeIf { it >= 0 }?.let { add("Battery: At least $it%") }
            json.optString("retryStrategy").takeIf { it.isNotBlank() }?.let { retry ->
                add("Retry: ${humanizeValue(retry)} • ${json.optInt("maxAutoRetries", 4)} max")
            }
            if (json.optBoolean("stopOnStoragePressure", false)) {
                add("Storage reserve: ${json.optInt("minimumFreeStorageMb", 512)} MB")
            }
            if (isEmpty()) {
                json.keys().asSequence().forEach { key ->
                    val value = json.opt(key)
                    if (value != null && value != JSONObject.NULL) add("${humanizeValue(key)}: ${humanizeJsonValue(value)}")
                }
            }
        }.ifEmpty { listOf("No additional conditions") }
    }.getOrElse { listOf("Schedule conditions are saved and will be applied automatically") }
}
internal fun humanizeJsonValue(value: Any): String = when (value) {
    is Boolean -> if (value) "Required" else "Not required"
    is JSONArray -> (0 until value.length()).joinToString(", ") { humanizeValue(value.optString(it)) }
    else -> humanizeValue(value.toString())
}
internal fun humanizeValue(value: String): String = value
    .replace('_', ' ')
    .replace(Regex("([a-z])([A-Z])"), "$1 $2")
    .trim()
    .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
@Composable
fun EmptyFeatureScreen(title: String, description: String) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        XdmSectionHeader(title)
        Spacer(Modifier.height(8.dp))
        Text(description, style = MaterialTheme.typography.bodyMedium)
    }
}
internal fun Download.fileManagementSummary(): String = buildString {
    appendLine("File: $fileName")
    appendLine("State: ${state.uiLabel()}")
    appendLine("Backend: ${backend.uiLabel()}")
    appendLine("Source: ${ExternalUrlPolicy.persistableUrl(sourceUrl) ?: "Sensitive URL redacted"}")
    appendLine("Saved location: ${destinationUiLabel(destinationUri)}")
    appendLine("Progress: ${bytesReceived.formatBytes()}${totalBytes?.let { " / ${it.formatBytes()}" } ?: ""}")
    mimeType?.takeIf { it.isNotBlank() }?.let { appendLine("MIME type: $it") }
    userLabel?.takeIf { it.isNotBlank() }?.let { appendLine("Label: $it") }
    errorMessage?.takeIf { it.isNotBlank() }?.let { appendLine("Last error: $it") }
}.trimEnd()
