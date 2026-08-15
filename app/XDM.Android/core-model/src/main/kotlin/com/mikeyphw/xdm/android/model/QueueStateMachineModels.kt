package com.mikeyphw.xdm.android.model

import java.time.LocalDateTime
import java.time.LocalTime

/** Phase 4: Queue, Scheduling, And State Machines. */
enum class QueueConditionScope { StartOnly, Ongoing, DrainOnly }

enum class QueueDrainPolicy { ContinueActive, DrainNaturally, PauseExcess }

enum class QueueControlCommand { PauseAll, ResumeAll, PauseOne, ResumeOne, CancelOne, RetryOne, StartOne, DisableQueue }

enum class QueueControlOutcome { Accepted, PartiallyApplied, Rejected, NeedsUserAction }

data class QueueConditionContract(
    val name: String,
    val scope: QueueConditionScope,
    val activeViolationPolicy: QueueDrainPolicy = QueueDrainPolicy.DrainNaturally,
    val userCopy: String,
)

data class QueueBudget(
    val maxConcurrent: Int,
    val bandwidthBytesPerSecond: Long? = null,
    val reservedSlots: Int = 0,
) {
    init {
        require(maxConcurrent >= 0) { "maxConcurrent must be zero or positive" }
        require(reservedSlots >= 0) { "reservedSlots must be zero or positive" }
        require(reservedSlots <= maxConcurrent) { "reservedSlots cannot exceed maxConcurrent" }
        require(bandwidthBytesPerSecond == null || bandwidthBytesPerSecond > 0) { "bandwidth budget must be positive when provided" }
    }

    val availableSlots: Int get() = (maxConcurrent - reservedSlots).coerceAtLeast(0)
}

data class QueueSlotReservation(
    val queueId: String,
    val downloadId: String,
    val attemptGeneration: Long,
    val accepted: Boolean,
    val activeCountBeforeClaim: Int,
    val budgetAfterClaim: QueueBudget,
    val reason: QueueHoldReason? = null,
    val message: String,
) {
    val claimKey: String get() = "$queueId:$downloadId:$attemptGeneration"
}

data class DurableQueueHold(
    val id: String,
    val queueId: String?,
    val command: QueueControlCommand,
    val generation: Long,
    val createdAtEpochMs: Long,
    val blocksNewStarts: Boolean,
    val coveredStates: Set<DownloadState>,
    val message: String,
)

data class DurableQueueCommandResult(
    val command: QueueControlCommand,
    val generation: Long,
    val outcome: QueueControlOutcome,
    val affectedDownloadIds: List<String>,
    val failedDownloadIds: List<String> = emptyList(),
    val durableHold: DurableQueueHold? = null,
    val message: String,
)

data class ScheduleWindowValidation(
    val valid: Boolean,
    val normalizedWindow: QueueScheduleWindow? = null,
    val error: String? = null,
    val preciseShortWindowRequired: Boolean = false,
    val overlappingPolicy: String = "most-specific-then-oldest-created",
)

data class RetryDeadline(
    val downloadId: String,
    val failureGeneration: Long,
    val attempt: Int,
    val failedAtEpochMs: Long,
    val eligibleAtEpochMs: Long,
    val scheduled: Boolean,
    val reason: QueueHoldReason = QueueHoldReason.RetryBackoff,
)

data class ImmediateReevaluationEvent(
    val id: String,
    val source: String,
    val coalesceKey: String,
    val createdAtEpochMs: Long,
    val durable: Boolean = true,
)

enum class QueueDeletionDisposition { Delete, RejectDanglingReferences, ReassignThenDelete }

data class QueueDeletionPlan(
    val queueId: String,
    val disposition: QueueDeletionDisposition,
    val referencedDownloadIds: List<String>,
    val replacementQueueId: String? = null,
    val message: String,
)

enum class SystemExecutionOwner { WorkManager, UserInitiatedJob, ForegroundService, StartupRecovery, NotificationAction, Unknown }

data class SystemStopReasonRecord(
    val downloadId: String,
    val attemptGeneration: Long,
    val owner: SystemExecutionOwner,
    val workInfoStopReason: Int? = null,
    val jobParametersStopReason: Int? = null,
    val pendingJobReasons: List<Int> = emptyList(),
    val pendingJobReasonHistory: List<List<Int>> = emptyList(),
    val occurredAtEpochMs: Long,
    val message: String,
) {
    val hasSpecificReason: Boolean get() = workInfoStopReason != null || jobParametersStopReason != null || pendingJobReasons.isNotEmpty()
}

enum class RecoveryOperation {
    SafeResume,
    RemoteValidation,
    SelectiveRepair,
    RestartFromZero,
    AdoptOrphan,
    LocateFile,
    StorageRecheck,
    ReconcileCompleted,
    ForgetWithTombstone,
}

enum class RecoveryOperationOutcome { Completed, NeedsUserConsent, NeedsFileSelection, Rejected, Failed, NoOp }

data class RecoveryArtifactIdentity(
    val stagingPath: String?,
    val checkpointPath: String?,
    val controlJournalPath: String?,
    val outputPath: String?,
    val ownershipClaimId: String?,
    val finalizationJournalId: String?,
)

data class RecoveryExecutionPlan(
    val downloadId: String,
    val attemptGeneration: Long,
    val operation: RecoveryOperation,
    val safeToExecute: Boolean,
    val artifacts: RecoveryArtifactIdentity,
    val outcomeIfBlocked: RecoveryOperationOutcome,
    val message: String,
)

data class TerminalNotificationKey(val downloadId: String, val attemptGeneration: Long, val state: DownloadState)

enum class NotificationActionVisibility { Show, Hide }

data class NotificationActionModel(
    val command: QueueControlCommand,
    val label: String,
    val visibility: NotificationActionVisibility,
    val downloadId: String? = null,
)

data class TerminalNotificationRecord(
    val key: TerminalNotificationKey,
    val title: String,
    val text: String,
    val actions: List<NotificationActionModel>,
    val createdAtEpochMs: Long,
    val dispatchedAtEpochMs: Long? = null,
) {
    val idempotencyKey: String get() = "${key.downloadId}:${key.attemptGeneration}:${key.state.name}"
}

data class NotificationPermissionState(
    val android13OrNewer: Boolean,
    val drawerPermissionGranted: Boolean?,
    val promptDismissed: Boolean = false,
    val upgradePreGranted: Boolean = false,
    val previouslyDeniedUpgrade: Boolean = false,
) {
    val needsInAppControlWarning: Boolean get() = android13OrNewer && drawerPermissionGranted == false
}

object QueueStateMachinePlanner {
    val activePauseStates: Set<DownloadState> = setOf(
        DownloadState.Connecting,
        DownloadState.Downloading,
        DownloadState.Finalizing,
        DownloadState.Verifying,
        DownloadState.Repairing,
    )

    fun reserveSlotAtomically(
        queueId: String,
        downloadId: String,
        attemptGeneration: Long,
        activeCount: Int,
        budget: QueueBudget,
    ): QueueSlotReservation {
        val accepted = activeCount < budget.availableSlots
        return QueueSlotReservation(
            queueId = queueId,
            downloadId = downloadId,
            attemptGeneration = attemptGeneration,
            accepted = accepted,
            activeCountBeforeClaim = activeCount,
            budgetAfterClaim = if (accepted) budget.copy(reservedSlots = budget.reservedSlots + 1) else budget,
            reason = if (accepted) null else QueueHoldReason.ConcurrencyLimit,
            message = if (accepted) "Queue slot reserved in the same transaction as the concurrency check." else "Queue concurrency budget is exhausted.",
        )
    }

    fun planPauseAll(downloads: List<Download>, generation: Long, nowEpochMs: Long): DurableQueueCommandResult {
        val affected = downloads.filter { it.state in activePauseStates }.map { it.id }
        val hold = DurableQueueHold(
            id = "global-pause-$generation",
            queueId = null,
            command = QueueControlCommand.PauseAll,
            generation = generation,
            createdAtEpochMs = nowEpochMs,
            blocksNewStarts = true,
            coveredStates = activePauseStates,
            message = "Pause All first installs a durable global hold, then pauses every active item independently.",
        )
        return DurableQueueCommandResult(
            command = QueueControlCommand.PauseAll,
            generation = generation,
            outcome = QueueControlOutcome.Accepted,
            affectedDownloadIds = affected,
            durableHold = hold,
            message = "Pause All is atomic at the queue gate and best-effort per active transfer; one failure does not stop the rest.",
        )
    }

    fun validateScheduleWindow(window: QueueScheduleWindow): ScheduleWindowValidation {
        val start = window.start
        val end = window.end
        if ((start == null) != (end == null)) {
            return ScheduleWindowValidation(valid = false, error = "Schedule windows must include both start and end times; incomplete windows fail closed.")
        }
        val precise = start != null && end != null && minutesBetween(start, end) < 15
        return ScheduleWindowValidation(valid = true, normalizedWindow = window, preciseShortWindowRequired = precise)
    }

    fun retryDeadline(downloadId: String, failureGeneration: Long, attempt: Int, failedAtEpochMs: Long, strategy: QueueRetryStrategy): RetryDeadline {
        val record = QueueIntelligencePlanner.retryRecord(strategy, attempt - 1, failedAtEpochMs)
        return RetryDeadline(
            downloadId = downloadId,
            failureGeneration = failureGeneration,
            attempt = record.attempt,
            failedAtEpochMs = failedAtEpochMs,
            eligibleAtEpochMs = record.nextRetryAtEpochMs,
            scheduled = strategy != QueueRetryStrategy.Manual,
        )
    }

    fun deleteQueue(queueId: String, referencedDownloadIds: List<String>, replacementQueueId: String? = null): QueueDeletionPlan = when {
        referencedDownloadIds.isEmpty() -> QueueDeletionPlan(queueId, QueueDeletionDisposition.Delete, emptyList(), message = "Queue has no download references and can be deleted transactionally.")
        replacementQueueId != null -> QueueDeletionPlan(queueId, QueueDeletionDisposition.ReassignThenDelete, referencedDownloadIds, replacementQueueId, "Downloads must be reassigned in the same transaction before queue deletion.")
        else -> QueueDeletionPlan(queueId, QueueDeletionDisposition.RejectDanglingReferences, referencedDownloadIds, message = "Queue deletion is blocked because downloads still reference it.")
    }

    fun recoverState(previous: DownloadState, classification: RecoveryClassification): DownloadState = when {
        previous == DownloadState.Cancelled -> DownloadState.Cancelled
        previous == DownloadState.Completed -> DownloadState.Completed
        classification == RecoveryClassification.CompletionRecovered -> DownloadState.Completed
        classification in setOf(RecoveryClassification.ReadyToResume, RecoveryClassification.NeedsRemoteValidation) -> DownloadState.Paused
        else -> DownloadState.RecoveryRequired
    }

    fun notificationActions(state: DownloadState, aggregateStates: Set<DownloadState>, downloadId: String): List<NotificationActionModel> {
        val perItem = when (state) {
            DownloadState.Paused -> listOf(NotificationActionModel(QueueControlCommand.ResumeOne, "Resume", NotificationActionVisibility.Show, downloadId))
            DownloadState.Downloading, DownloadState.Connecting, DownloadState.Verifying, DownloadState.Repairing, DownloadState.Finalizing -> listOf(
                NotificationActionModel(QueueControlCommand.PauseOne, "Pause", NotificationActionVisibility.Show, downloadId),
                NotificationActionModel(QueueControlCommand.CancelOne, "Cancel", NotificationActionVisibility.Show, downloadId),
            )
            DownloadState.RecoveryRequired -> listOf(NotificationActionModel(QueueControlCommand.RetryOne, "Review recovery", NotificationActionVisibility.Show, downloadId))
            DownloadState.Failed -> listOf(NotificationActionModel(QueueControlCommand.RetryOne, "Retry", NotificationActionVisibility.Show, downloadId))
            else -> emptyList()
        }
        val aggregate = if (aggregateStates.any { it in activePauseStates }) {
            listOf(NotificationActionModel(QueueControlCommand.PauseAll, "Pause all", NotificationActionVisibility.Show))
        } else if (aggregateStates.contains(DownloadState.Paused)) {
            listOf(NotificationActionModel(QueueControlCommand.ResumeAll, "Resume all", NotificationActionVisibility.Show))
        } else emptyList()
        return aggregate + perItem
    }

    private fun minutesBetween(start: LocalTime, end: LocalTime): Long {
        val base = start.toSecondOfDay()
        val target = end.toSecondOfDay()
        val seconds = if (target >= base) target - base else 24 * 60 * 60 - base + target
        return seconds / 60L
    }

    fun isInsideWindow(window: QueueScheduleWindow, now: LocalDateTime): Boolean = validateScheduleWindow(window).valid && window.contains(now)
}
