package com.mikeyphw.xdm.android.model

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Browser-free operational categories surfaced by the Activity workspace. */
enum class OperationalActivityCategory(val label: String) {
    Transfer("Transfer"),
    Policy("Queue decision"),
    Handoff("External handoff"),
    Recovery("Recovery"),
    Verification("Verification"),
    Storage("Storage"),
    Network("Network"),
    Engine("Engine"),
    Media("Media"),
    System("System"),
}

enum class OperationalActivitySeverity(val label: String) {
    Info("Info"),
    Success("Success"),
    Warning("Warning"),
    Error("Error"),
}

enum class OperationalActivityTimeRange(val label: String, val durationMs: Long?) {
    Today("Today", 24L * 60L * 60L * 1_000L),
    SevenDays("7 days", 7L * 24L * 60L * 60L * 1_000L),
    All("All", null),
}

data class OperationalActivityEvent(
    val id: String,
    val downloadId: String? = null,
    val fileName: String? = null,
    val category: OperationalActivityCategory,
    val severity: OperationalActivitySeverity,
    val title: String,
    val detail: String,
    val engine: String? = null,
    val actionLabel: String? = null,
    val createdAtEpochMs: Long,
    val unresolved: Boolean = false,
    val source: String = "runtime",
    val nextEligibleAtEpochMs: Long? = null,
)

data class OperationalActivityFilter(
    val query: String = "",
    val category: OperationalActivityCategory? = null,
    val severity: OperationalActivitySeverity? = null,
    val timeRange: OperationalActivityTimeRange = OperationalActivityTimeRange.SevenDays,
    val attentionOnly: Boolean = false,
)

data class OperationalActivitySummary(
    val total: Int = 0,
    val unresolved: Int = 0,
    val policyHolds: Int = 0,
    val networkHolds: Int = 0,
    val storageHolds: Int = 0,
    val recentFailures: Int = 0,
    val completed: Int = 0,
    val latestAtEpochMs: Long? = null,
)

data class OperationalDiagnosticsContext(
    val appVersion: String,
    val versionCode: Int,
    val androidVersion: String,
    val schemaVersion: Int,
    val enabledEngines: List<String>,
    val generatedAtEpochMs: Long,
)

/** Pure projection, filtering, and privacy-safe export for the Activity destination. */
object OperationalActivityPlanner {
    private val urlPattern = Regex("""(?:https?|ftp)://[^\s<>()\[\]{}\"']+""", RegexOption.IGNORE_CASE)
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US)
        .withZone(ZoneId.systemDefault())

    fun timeline(
        storedEvents: List<OperationalActivityEvent>,
        queueDecisions: List<QueueDecisionEvent>,
        downloads: List<Download>,
        recoveryRecords: List<RecoveryRecord>,
        verificationRecords: List<VerificationRecord>,
        finalizationJournals: List<FinalizationJournal>,
        automationCommands: List<AutomationCommandRecord>,
        dismissedEventIds: Set<String> = emptySet(),
        nowEpochMs: Long = System.currentTimeMillis(),
    ): List<OperationalActivityEvent> {
        val downloadsById = downloads.associateBy(Download::id)
        val projected = buildList {
            addAll(storedEvents)
            queueDecisions.mapTo(this) { decisionEvent(it) }
            recoveryRecords.mapTo(this) { recoveryEvent(it, downloadsById[it.downloadId]) }
            verificationRecords.mapTo(this) { verificationEvent(it, downloadsById[it.downloadId]) }
            finalizationJournals.mapTo(this) { finalizationEvent(it, downloadsById[it.downloadId]) }
            automationCommands.mapTo(this) { handoffEvent(it) }
            downloads.mapNotNullTo(this) { currentAttentionEvent(it) }
        }
        return projected
            .asSequence()
            .filterNot { it.id in dismissedEventIds }
            .map(::sanitize)
            .distinctBy(OperationalActivityEvent::id)
            .filter { it.createdAtEpochMs <= nowEpochMs + FUTURE_TOLERANCE_MS }
            .sortedWith(compareByDescending<OperationalActivityEvent> { it.createdAtEpochMs }.thenBy { it.id })
            .take(MAX_VISIBLE_EVENTS)
            .toList()
    }

    fun summarize(events: List<OperationalActivityEvent>): OperationalActivitySummary = OperationalActivitySummary(
        total = events.size,
        unresolved = events.count(OperationalActivityEvent::unresolved),
        policyHolds = events.count { it.source == "queue-policy" && it.unresolved },
        networkHolds = events.count { it.category == OperationalActivityCategory.Network && it.unresolved },
        storageHolds = events.count { it.category == OperationalActivityCategory.Storage && it.unresolved },
        recentFailures = events.count { it.severity == OperationalActivitySeverity.Error },
        completed = events.count { it.severity == OperationalActivitySeverity.Success && it.title.contains("complete", ignoreCase = true) },
        latestAtEpochMs = events.maxOfOrNull(OperationalActivityEvent::createdAtEpochMs),
    )

    fun filter(
        events: List<OperationalActivityEvent>,
        filter: OperationalActivityFilter,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): List<OperationalActivityEvent> {
        val normalizedQuery = filter.query.trim().lowercase(Locale.US)
        val cutoff = filter.timeRange.durationMs?.let { nowEpochMs - it }
        return events.filter { event ->
            (cutoff == null || event.createdAtEpochMs >= cutoff) &&
                (filter.category == null || event.category == filter.category) &&
                (filter.severity == null || event.severity == filter.severity) &&
                (!filter.attentionOnly || event.unresolved) &&
                (normalizedQuery.isBlank() || listOfNotNull(
                    event.fileName,
                    event.title,
                    event.detail,
                    event.engine,
                    event.category.label,
                    event.severity.label,
                ).any { normalizedQuery in it.lowercase(Locale.US) })
        }
    }

    fun diagnosticsExport(
        context: OperationalDiagnosticsContext,
        events: List<OperationalActivityEvent>,
        summary: OperationalActivitySummary = summarize(events),
        maxEvents: Int = 120,
    ): String = buildString {
        appendLine("XDM Android operational diagnostics v1")
        appendLine("Generated: ${formatTime(context.generatedAtEpochMs)}")
        appendLine("App: ${safeText(context.appVersion)} (${context.versionCode})")
        appendLine("Android: ${safeText(context.androidVersion)}")
        appendLine("Room schema: ${context.schemaVersion}")
        appendLine("Methods: ${context.enabledEngines.map(::engineLabel).joinToString().ifBlank { "none reported" }}")
        appendLine("Product: downloader-only; external browser handoff enabled; built-in browser absent")
        appendLine("Summary: ${summary.total} events, ${summary.unresolved} unresolved, ${summary.policyHolds} policy holds, ${summary.recentFailures} errors")
        appendLine("Secrets: cookies, authorization values, tokens, signatures, and credential-bearing query values are <redacted>")
        appendLine("Events:")
        events.take(maxEvents.coerceIn(0, MAX_VISIBLE_EVENTS)).forEach { event ->
            append(formatTime(event.createdAtEpochMs))
            append(" | ").append(event.severity.label)
            append(" | ").append(event.category.label)
            event.fileName?.takeIf(String::isNotBlank)?.let { append(" | ").append(safeText(it)) }
            event.engine?.takeIf(String::isNotBlank)?.let { append(" | Method: ").append(engineLabel(it)) }
            append(" | ").append(safeText(event.title))
            append(" | ").append(safeText(event.detail))
            if (event.unresolved) append(" | unresolved")
            appendLine()
        }
    }.take(MAX_EXPORT_CHARS)

    private fun decisionEvent(event: QueueDecisionEvent): OperationalActivityEvent {
        val category = when (event.reason) {
            QueueHoldReason.NetworkUnavailable,
            QueueHoldReason.UnmeteredRequired,
            QueueHoldReason.WifiRequired -> OperationalActivityCategory.Network
            QueueHoldReason.StoragePressure -> OperationalActivityCategory.Storage
            else -> OperationalActivityCategory.Policy
        }
        val unresolved = event.disposition != QueueLaunchDisposition.Start
        val severity = when {
            event.disposition == QueueLaunchDisposition.Start -> OperationalActivitySeverity.Success
            event.reason in setOf(
                QueueHoldReason.AuthenticationRequired,
                QueueHoldReason.PermissionRequired,
                QueueHoldReason.VerificationFailed,
                QueueHoldReason.UnsupportedFailure,
                QueueHoldReason.PermanentFailure,
                QueueHoldReason.RetryLimit,
            ) -> OperationalActivitySeverity.Error
            else -> OperationalActivitySeverity.Warning
        }
        return OperationalActivityEvent(
            id = "policy:${event.id}",
            downloadId = event.downloadId,
            fileName = event.fileName,
            category = category,
            severity = severity,
            title = event.title,
            detail = event.detail,
            actionLabel = actionForReason(event.reason),
            createdAtEpochMs = event.createdAtEpochMs,
            unresolved = unresolved,
            source = "queue-policy",
            nextEligibleAtEpochMs = event.nextEligibleAtEpochMs,
        )
    }

    private fun recoveryEvent(record: RecoveryRecord, download: Download?): OperationalActivityEvent = OperationalActivityEvent(
        id = "recovery:${record.id}",
        downloadId = record.downloadId,
        fileName = download?.fileName,
        category = OperationalActivityCategory.Recovery,
        severity = OperationalActivitySeverity.Error,
        title = "Recovery required",
        detail = "${recoveryClassificationLabel(record.classification)}: ${record.reason}",
        engine = download?.backend?.name?.let(::engineLabel),
        actionLabel = recoveryActionLabel(record.recommendedAction),
        createdAtEpochMs = record.createdAtEpochMs,
        unresolved = download?.state == DownloadState.RecoveryRequired || download == null,
        source = "recovery",
    )

    private fun verificationEvent(record: VerificationRecord, download: Download?): OperationalActivityEvent {
        val severity = when (record.status) {
            VerificationStatus.Passed -> OperationalActivitySeverity.Success
            VerificationStatus.Failed,
            VerificationStatus.MissingFile -> OperationalActivitySeverity.Error
            VerificationStatus.Pending,
            VerificationStatus.Running -> OperationalActivitySeverity.Info
            VerificationStatus.NoExpectation -> OperationalActivitySeverity.Warning
        }
        return OperationalActivityEvent(
            id = "verification:${record.id}:${record.status.name}",
            downloadId = record.downloadId,
            fileName = download?.fileName,
            category = OperationalActivityCategory.Verification,
            severity = severity,
            title = when (record.status) {
                VerificationStatus.Passed -> "Verification passed"
                VerificationStatus.Failed -> "Verification failed"
                VerificationStatus.MissingFile -> "Verification file missing"
                VerificationStatus.Running -> "Verification running"
                VerificationStatus.Pending -> "Verification pending"
                VerificationStatus.NoExpectation -> "No checksum expectation"
            },
            detail = record.message,
            engine = download?.backend?.name?.let(::engineLabel),
            actionLabel = if (record.status == VerificationStatus.Failed) "Verify or redownload" else null,
            createdAtEpochMs = record.updatedAtEpochMs,
            unresolved = record.status == VerificationStatus.Failed || record.status == VerificationStatus.MissingFile,
            source = "verification",
        )
    }

    private fun finalizationEvent(journal: FinalizationJournal, download: Download?): OperationalActivityEvent = OperationalActivityEvent(
        id = "finalization:${journal.id}:${journal.stage.name}",
        downloadId = journal.downloadId,
        fileName = download?.fileName,
        category = if (journal.needsRecovery) OperationalActivityCategory.Recovery else OperationalActivityCategory.Transfer,
        severity = if (journal.needsRecovery) OperationalActivitySeverity.Warning else OperationalActivitySeverity.Success,
        title = if (journal.needsRecovery) "Finalization needs recovery" else "Finalization complete",
        detail = journal.message,
        engine = download?.backend?.name?.let(::engineLabel),
        actionLabel = if (journal.needsRecovery) "Open recovery" else null,
        createdAtEpochMs = journal.updatedAtEpochMs,
        unresolved = journal.needsRecovery,
        source = "finalization",
    )

    private fun handoffEvent(record: AutomationCommandRecord): OperationalActivityEvent {
        val severity = when (record.status) {
            AutomationCommandStatus.Accepted,
            AutomationCommandStatus.Executed -> OperationalActivitySeverity.Success
            AutomationCommandStatus.Duplicate -> OperationalActivitySeverity.Info
            AutomationCommandStatus.Rejected -> OperationalActivitySeverity.Warning
            AutomationCommandStatus.Failed -> OperationalActivitySeverity.Error
        }
        return OperationalActivityEvent(
            id = "handoff:${record.id}:${record.status.name}",
            downloadId = record.downloadId,
            fileName = record.fileName,
            category = OperationalActivityCategory.Handoff,
            severity = severity,
            title = "${handoffSourceLabel(record.source)} ${handoffStatusLabel(record.status)}",
            detail = listOfNotNull(record.originHost, record.resultMessage).joinToString(" • "),
            actionLabel = if (record.status == AutomationCommandStatus.Rejected || record.status == AutomationCommandStatus.Failed) "Review intake" else null,
            createdAtEpochMs = record.updatedAtEpochMs,
            unresolved = record.status == AutomationCommandStatus.Failed,
            source = "external-handoff",
        )
    }

    private fun currentAttentionEvent(download: Download): OperationalActivityEvent? {
        val attention = DownloadDashboardPlanner.attentionSignal(download) ?: return null
        val category = when (attention.kind) {
            DownloadAttentionKind.Authentication -> OperationalActivityCategory.Handoff
            DownloadAttentionKind.Storage,
            DownloadAttentionKind.Permission -> OperationalActivityCategory.Storage
            DownloadAttentionKind.Verification -> OperationalActivityCategory.Verification
            DownloadAttentionKind.Network -> OperationalActivityCategory.Network
            DownloadAttentionKind.Recovery -> OperationalActivityCategory.Recovery
            DownloadAttentionKind.Retry -> OperationalActivityCategory.Transfer
        }
        return OperationalActivityEvent(
            id = "current-attention:${download.id}:${download.state.name}",
            downloadId = download.id,
            fileName = download.fileName,
            category = category,
            severity = OperationalActivitySeverity.Error,
            title = attention.label,
            detail = attention.guidance,
            engine = engineLabel(download.backend.name),
            actionLabel = when (attention.kind) {
                DownloadAttentionKind.Authentication -> "Review request context"
                DownloadAttentionKind.Storage,
                DownloadAttentionKind.Permission -> "Change destination"
                DownloadAttentionKind.Verification -> "Verify or redownload"
                DownloadAttentionKind.Network -> "Retry now"
                DownloadAttentionKind.Recovery -> "Open recovery"
                DownloadAttentionKind.Retry -> "Review transfer"
            },
            createdAtEpochMs = download.updatedAtEpochMs,
            unresolved = true,
            source = "current-state",
        )
    }

    private fun actionForReason(reason: QueueHoldReason?): String? = when (reason) {
        QueueHoldReason.NetworkUnavailable,
        QueueHoldReason.UnmeteredRequired,
        QueueHoldReason.WifiRequired,
        QueueHoldReason.ChargingRequired,
        QueueHoldReason.BatteryLow,
        QueueHoldReason.ScheduleWindow,
        QueueHoldReason.QueueDisabled,
        QueueHoldReason.ConcurrencyLimit,
        QueueHoldReason.RetryBackoff -> "Start anyway"
        QueueHoldReason.StoragePressure -> "Change destination"
        QueueHoldReason.AuthenticationRequired -> "Review request context"
        QueueHoldReason.PermissionRequired -> "Repair permission"
        QueueHoldReason.VerificationFailed -> "Verify or redownload"
        QueueHoldReason.UnsupportedFailure -> "Open resolver diagnostics"
        QueueHoldReason.PermanentFailure,
        QueueHoldReason.NonRetryableFailure,
        QueueHoldReason.RetryLimit -> "Review transfer"
        null -> null
    }

    private fun recoveryActionLabel(action: RecoveryAction): String = when (action) {
        RecoveryAction.Resume -> "Resume"
        RecoveryAction.Validate -> "Validate"
        RecoveryAction.VerifyAndRepair -> "Verify and repair"
        RecoveryAction.RestartFromZero -> "Restart"
        RecoveryAction.AdoptOrphan -> "Adopt file"
        RecoveryAction.LocateFile -> "Locate file"
        RecoveryAction.RemoveRecord -> "Remove record"
    }

    private fun recoveryClassificationLabel(classification: RecoveryClassification): String = when (classification) {
        RecoveryClassification.ReadyToResume -> "Ready to resume"
        RecoveryClassification.NeedsRemoteValidation -> "Needs remote validation"
        RecoveryClassification.NeedsRepair -> "Needs repair"
        RecoveryClassification.MissingPartialFile -> "Partial file missing"
        RecoveryClassification.RemoteFileChanged -> "Remote file changed"
        RecoveryClassification.CompletionRecovered -> "Completed file recovered"
        RecoveryClassification.FinalizationInterrupted -> "Finalization interrupted"
        RecoveryClassification.BackendTaskOrphaned -> "Backend task orphaned"
        RecoveryClassification.OrphanedArtifact -> "Untracked artifact"
    }

    private fun handoffSourceLabel(source: AutomationCommandSource): String = when (source) {
        AutomationCommandSource.ShareSheet -> "Android share"
        AutomationCommandSource.ViewIntent -> "Android view intent"
        AutomationCommandSource.Tasker -> "Tasker"
        AutomationCommandSource.BrowserExtension -> "Browser extension"
        AutomationCommandSource.DeepLink -> "XDM link"
        AutomationCommandSource.Internal -> "XDM"
    }

    private fun handoffStatusLabel(status: AutomationCommandStatus): String = when (status) {
        AutomationCommandStatus.Accepted -> "accepted"
        AutomationCommandStatus.Executed -> "executed"
        AutomationCommandStatus.Duplicate -> "already added"
        AutomationCommandStatus.Rejected -> "needs review"
        AutomationCommandStatus.Failed -> "failed"
    }

    private fun engineLabel(value: String): String = when (value.trim().lowercase(Locale.US)) {
        "native", "xdm native" -> "XDM Native"
        "aria2" -> "aria2"
        "termux" -> "Termux"
        "yt-dlp", "ytdlp" -> "yt-dlp"
        "automatic", "auto" -> "Automatic"
        else -> humanizeValue(value)
    }

    private fun humanizeValue(value: String): String = value
        .replace('_', ' ')
        .replace('-', ' ')
        .replace(Regex("([a-z])([A-Z])"), "$1 $2")
        .trim()
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }

    private fun sanitize(event: OperationalActivityEvent): OperationalActivityEvent = event.copy(
        fileName = event.fileName?.let(::safeText),
        title = safeText(event.title),
        detail = safeText(event.detail),
        engine = event.engine?.let(::safeText),
        actionLabel = event.actionLabel?.let(::safeText),
    )

    private fun safeText(raw: String): String {
        val withRedactedUrls = urlPattern.replace(raw) { match ->
            PrivacyDiagnosticsRedactor.redactUrl(match.value) ?: "<redacted>"
        }
        return PrivacyDiagnosticsRedactor.redactText(withRedactedUrls)
            ?.replace(Regex("(?i)(cookie|authorization|token|password|secret)\\s*[:=]\\s*(?!<redacted>)[^\\s•,;]+")) { match ->
                "${match.groupValues[1].lowercase(Locale.US)}=<redacted>"
            }
            ?.take(MAX_TEXT_CHARS)
            .orEmpty()
    }

    private fun formatTime(epochMs: Long): String = dateFormatter.format(Instant.ofEpochMilli(epochMs))

    private const val MAX_VISIBLE_EVENTS = 500
    private const val MAX_TEXT_CHARS = 512
    private const val MAX_EXPORT_CHARS = 96_000
    private const val FUTURE_TOLERANCE_MS = 5L * 60L * 1_000L
}
