package com.mikeyphw.xdm.android.scheduler

import android.content.Context
import com.mikeyphw.xdm.android.model.Download
import com.mikeyphw.xdm.android.model.DownloadState
import com.mikeyphw.xdm.android.model.DurableQueueCommandResult
import com.mikeyphw.xdm.android.model.QueueControlCommand
import com.mikeyphw.xdm.android.model.QueueControlOutcome
import com.mikeyphw.xdm.android.model.QueueDeletionDisposition
import com.mikeyphw.xdm.android.model.QueueDeletionPlan
import com.mikeyphw.xdm.android.model.QueueHoldReason
import com.mikeyphw.xdm.android.model.QueueIntelligenceSummary
import com.mikeyphw.xdm.android.model.QueueLaunchDecision
import com.mikeyphw.xdm.android.model.QueueLaunchDisposition
import com.mikeyphw.xdm.android.model.QueueStateMachinePlanner
import com.mikeyphw.xdm.android.persistence.DownloadRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

/**
 * XGO-75 compatibility facade for callers that have not yet shed the historical queue type.
 *
 * Go is the only scheduling/eligibility/retry authority. This class may request a Go command,
 * wake the engine, or perform metadata-only queue maintenance. It must never rank candidates,
 * claim execution slots, choose retry deadlines, or start Android transfer owners.
 */
data class QueueReconcileOutcome(
    val summary: QueueIntelligenceSummary,
    val eligibleDownloads: List<Download>,
)

internal enum class ClaimedExecutionAuthorization {
    Ready,
    TemporarilyHeld,
    Stale,
}

class QueueIntelligenceCoordinator(
    context: Context,
    private val repository: DownloadRepository,
) {
    private val appContext = context.applicationContext
    private val _status = MutableStateFlow(
        QueueIntelligenceSummary(message = "Go owns Android queue eligibility and execution scheduling."),
    )
    val status: StateFlow<QueueIntelligenceSummary> = _status

    /** Compatibility entry point: submit the user intent to Go; do not evaluate policy here. */
    suspend fun requestStart(
        downloadId: String,
        userVisible: Boolean = true,
        manual: Boolean = true,
        policyOverride: Boolean = false,
    ): QueueLaunchDecision {
        val result = AndroidGoDownloadCommands.submit(
            appContext,
            action = if (manual) "resume" else "retry",
            downloadId = downloadId,
        )
        val decision = if (result.accepted) {
            QueueLaunchDecision(
                disposition = QueueLaunchDisposition.Start,
                title = "Submitted to Go",
                detail = result.detail,
                policyOverridden = policyOverride,
            )
        } else {
            QueueLaunchDecision(
                disposition = QueueLaunchDisposition.Hold,
                reason = QueueHoldReason.QueueDisabled,
                title = "Go command unavailable",
                detail = result.detail,
                policyOverridden = policyOverride,
            )
        }
        _status.value = _status.value.copy(
            evaluatedAtEpochMs = System.currentTimeMillis(),
            message = decision.detail,
        )
        return decision
    }

    /**
     * Retained only as a binary/source compatibility surface for pre-XGO75 callers.
     * Android claims are no longer sufficient authority to start an execution owner.
     */
    internal suspend fun authorizeClaimedExecution(
        downloadId: String,
        queueClaimToken: Long,
    ): ClaimedExecutionAuthorization = ClaimedExecutionAuthorization.Stale

    suspend fun resumeAllManual(): Int {
        val candidates = repository.findDownloadsByStates(
            setOf(DownloadState.Paused, DownloadState.WaitingForNetwork, DownloadState.WaitingForPower, DownloadState.Failed),
        )
        val result = AndroidGoDownloadCommands.submit(appContext, action = "resume_all")
        _status.value = _status.value.copy(
            evaluatedAtEpochMs = System.currentTimeMillis(),
            message = result.detail,
        )
        return if (result.accepted) candidates.size else 0
    }

    /** Compatibility method. Go performs reconciliation; Kotlin returns no claimed owners. */
    suspend fun evaluateAndClaim(): QueueReconcileOutcome = QueueReconcileOutcome(
        summary = reconcile(),
        eligibleDownloads = emptyList(),
    )

    /** Historical release hook: never mutates durable ownership after XGO-75. */
    suspend fun releaseFailedExecutionOwner(downloadId: String, queueClaimToken: Long, message: String) {
        recordImmediateReevaluation("legacy-owner-release", downloadId)
    }

    suspend fun reconcile(): QueueIntelligenceSummary {
        val now = System.currentTimeMillis()
        val result = AndroidSchedulerHost.wake(
            appContext,
            AndroidEngineWakeRequest(
                eventId = "reconcile:$now",
                reason = AndroidEngineWakeReason.MANUAL_RECONCILE,
            ),
        )
        val summary = _status.value.copy(
            evaluatedAtEpochMs = now,
            message = when (result.disposition) {
                AndroidEngineWakeDisposition.ACCEPTED -> "Go engine accepted the queue reconciliation wake and owns eligibility decisions."
                AndroidEngineWakeDisposition.DUPLICATE -> "A duplicate reconciliation wake was suppressed by the Go engine host boundary."
                AndroidEngineWakeDisposition.RETRYABLE -> "Go engine host is temporarily unavailable; Android will retry without evaluating the queue."
                AndroidEngineWakeDisposition.FAILED -> "Go engine rejected the reconciliation wake; Android did not evaluate or start queued transfers."
            },
        )
        _status.value = summary
        return summary
    }

    fun recordTerminalEvent(event: TransferTerminalEvent) {
        if (event.state == DownloadState.Completed || event.state == DownloadState.Cancelled) {
            QueueIntelligenceWorker.cancelRetry(appContext, event.downloadId)
        }
    }

    suspend fun pauseAllDurably(): DurableQueueCommandResult {
        val now = System.currentTimeMillis()
        val candidates = repository.findDownloadsByStates(QueueStateMachinePlanner.activePauseStates)
        val result = AndroidGoDownloadCommands.submit(appContext, action = "pause_all")
        val outcome = if (result.accepted) QueueControlOutcome.Accepted else QueueControlOutcome.Rejected
        _status.value = _status.value.copy(evaluatedAtEpochMs = now, message = result.detail)
        return DurableQueueCommandResult(
            command = QueueControlCommand.PauseAll,
            generation = now,
            outcome = outcome,
            affectedDownloadIds = if (result.accepted) candidates.map { it.id } else emptyList(),
            failedDownloadIds = if (result.accepted) emptyList() else candidates.map { it.id },
            durableHold = null,
            message = "Go owns the durable Pause All state: ${result.detail}",
        )
    }

    /** Deprecated no-op holds kept only for source compatibility with older recovery workers. */
    fun installStartupRecoveryHold() = Unit
    fun clearStartupRecoveryHold() = Unit

    suspend fun deleteQueueSafely(queueId: String, replacementQueueId: String? = null): QueueDeletionPlan {
        val referenced = repository.downloads.first().filter { it.queueId == queueId }.map { it.id }
        val plan = if (referenced.isEmpty()) {
            QueueDeletionPlan(queueId, QueueDeletionDisposition.Delete, emptyList(), null, "Queue has no referenced downloads and can be deleted safely.")
        } else if (replacementQueueId != null) {
            QueueDeletionPlan(queueId, QueueDeletionDisposition.ReassignThenDelete, referenced, replacementQueueId, "Queue deletion will first reassign referenced downloads to $replacementQueueId.")
        } else {
            QueueDeletionPlan(queueId, QueueDeletionDisposition.RejectDanglingReferences, referenced, null, "Queue deletion rejected because downloads still reference this queue.")
        }
        if (plan.disposition == QueueDeletionDisposition.Delete) repository.deleteQueue(queueId)
        else if (plan.disposition == QueueDeletionDisposition.ReassignThenDelete && replacementQueueId != null) repository.reassignQueueThenDelete(queueId, replacementQueueId)
        return plan
    }

    fun recordImmediateReevaluation(source: String, coalesceKey: String = "queue-intelligence") {
        QueueIntelligenceWorker.enqueueImmediate(appContext)
    }

    fun retireAndroidSystemId(downloadId: String) {
        TransferSystemIdRegistry(appContext).retire(downloadId)
    }

    fun clearDecisionHistory() {
        _status.value = _status.value.copy(
            recentDecisions = emptyList(),
            message = "Go owns queue decision history; the Android compatibility summary was cleared.",
        )
    }

    companion object {
        val ACTIVE_STATES = setOf(DownloadState.Connecting, DownloadState.Downloading, DownloadState.Verifying, DownloadState.Repairing, DownloadState.Finalizing)
        val CANDIDATE_STATES = setOf(DownloadState.Created, DownloadState.Queued, DownloadState.WaitingForNetwork, DownloadState.WaitingForPower, DownloadState.Failed)
        val TERMINAL_CLEAR_STATES = setOf(DownloadState.Completed, DownloadState.Cancelled)
    }
}

interface QueueIntelligenceProvider {
    val queueIntelligenceCoordinator: QueueIntelligenceCoordinator
}
