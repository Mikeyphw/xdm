package com.mikeyphw.xdm.android.scheduler

import android.content.Context
import com.mikeyphw.xdm.android.model.Download
import com.mikeyphw.xdm.android.model.DownloadState
import com.mikeyphw.xdm.android.model.DurableQueueCommandResult
import com.mikeyphw.xdm.android.model.QueueBudget
import com.mikeyphw.xdm.android.model.QueueDefinition
import com.mikeyphw.xdm.android.model.QueueDeletionDisposition
import com.mikeyphw.xdm.android.model.QueueDeletionPlan
import com.mikeyphw.xdm.android.model.QueueHoldReason
import com.mikeyphw.xdm.android.model.QueueIntelligencePlanner
import com.mikeyphw.xdm.android.model.QueueIntelligenceSummary
import com.mikeyphw.xdm.android.model.QueueLaunchDecision
import com.mikeyphw.xdm.android.model.QueueLaunchDisposition
import com.mikeyphw.xdm.android.model.QueueStateMachinePlanner
import com.mikeyphw.xdm.android.persistence.DownloadRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex

/** Eligible downloads claimed during one policy pass. The caller owns execution. */
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
    private val executionStarter: TransferExecutionStarter,
    private val conditionsReader: AndroidQueueConditionsReader = AndroidQueueConditionsReader(context),
    private val retryLedger: QueueRetryLedger = QueueRetryLedger(context),
    private val decisionLedger: QueueDecisionLedger = QueueDecisionLedger(context),
    private val admissionGate: DurableQueueAdmissionGate = DurableQueueAdmissionGate(context),
    private val phase4Coordinator: QueueSchedulingRecoveryCoordinator = QueueSchedulingRecoveryCoordinator(
        FileBackedQueueSchedulingRecoveryStore(java.io.File(context.filesDir, "queue-scheduling-recovery")),
    ),
) {
    private val appContext = context.applicationContext
    private val evaluationMutex = Mutex()
    private val _status = MutableStateFlow(QueueIntelligenceSummary(recentDecisions = decisionLedger.recent()))
    val status: StateFlow<QueueIntelligenceSummary> = _status

    suspend fun requestStart(
        downloadId: String,
        userVisible: Boolean = true,
        manual: Boolean = true,
        policyOverride: Boolean = false,
    ): QueueLaunchDecision {
        evaluationMutex.lock()
        try {
            val download = repository.findDownload(downloadId) ?: return hold("Download unavailable", "The queued record no longer exists.")
            admissionGate.currentHold()?.let { durable ->
                val decision = hold(
                    if (durable.reason == DurableQueueAdmissionGate.REASON_PAUSE_ALL) "Downloads paused" else "Recovery in progress",
                    if (durable.reason == DurableQueueAdmissionGate.REASON_PAUSE_ALL) "Pause All is durably active. Use Resume All before starting another transfer." else "XDM is reconciling durable backend ownership before queue admission is enabled.",
                )
                decisionLedger.record(download, decision, System.currentTimeMillis())
                refreshStatusMessage(decision)
                return decision
            }
            val queues = repository.queues.first()
            val schedules = repository.schedules.first()
            val queue = queues.firstOrNull { it.id == download.queueId } ?: fallbackQueue(download.queueId)
            val conditions = conditionsReader.snapshot(destinationUri = download.destinationUri)
            val resolved = QueuePolicyCodec.resolve(queue, schedules, conditions.nowEpochMs)
            val activeCount = repository.findDownloadsByStates(ACTIVE_STATES).count { (it.queueId ?: "default") == queue.id }
            val retryRecord = if (!manual && download.state == DownloadState.Failed) {
                retryLedger.observeFailure(
                    download,
                    resolved.policy.retryStrategy,
                    secureContextPresent = MediaRequestHandoffStore.forDownload(download.id) != null,
                )
            } else retryLedger.get(download.id)
            val decision = if (!manual && download.state == DownloadState.Failed && retryLedger.requiresSecureContext(download.id) && MediaRequestHandoffStore.forDownload(download.id) == null) {
                QueueLaunchDecision(
                    QueueLaunchDisposition.Hold,
                    QueueHoldReason.AuthenticationRequired,
                    "Secure request context required",
                    "The failed attempt depended on encrypted request material that is no longer available. Share or approve the request again before retrying.",
                )
            } else {
                QueueIntelligencePlanner.decision(
                    policy = resolved.policy,
                    conditions = conditions,
                    queueEnabled = queue.isEnabled,
                    scheduleActive = !resolved.hasApplicableRules || resolved.activeRuleName != null,
                    scheduleSummary = resolved.nextWindowSummary,
                    activeCount = activeCount,
                    retryRecord = retryRecord,
                    failureMessage = if (manual) null else download.errorMessage,
                    policyOverride = policyOverride,
                )
            }
            val nextEligibleAtEpochMs = decision.nextEligibleAtEpochMs
            if (nextEligibleAtEpochMs != null) QueueIntelligenceWorker.scheduleRetry(appContext, download.id, nextEligibleAtEpochMs)
            if (decision.canStart) {
                val claimed = claimForLaunch(download, queue, decision, manual, activeCount)
                if (claimed) {
                    val current = repository.findDownload(download.id) ?: download
                    AndroidExecutionClaimRegistry.install(current.id, current.updatedAtEpochMs)
                    val launch = executionStarter.start(current.id, current.totalBytes, userVisible, current.updatedAtEpochMs)
                    if (!launch.accepted) {
                        AndroidExecutionClaimRegistry.release(current.id, current.updatedAtEpochMs)
                        repository.releaseQueueLaunchClaim(current.id, current.attemptGeneration, current.updatedAtEpochMs, "Queue policy: Android execution owner could not be scheduled.")
                        val rejected = hold("Execution unavailable", "Android could not accept a legal execution owner; the durable queue claim was released for retry.")
                        decisionLedger.record(download, rejected, conditions.nowEpochMs)
                        refreshStatusMessage(rejected)
                        return rejected
                    }
                } else {
                    val rejected = QueueLaunchDecision(QueueLaunchDisposition.Hold, QueueHoldReason.ConcurrencyLimit, "Queue limit reached", "Another process or coroutine claimed the last durable queue slot first.")
                    applyHold(download, rejected)
                    decisionLedger.record(download, rejected, conditions.nowEpochMs)
                    refreshStatusMessage(rejected)
                    return rejected
                }
            } else applyHold(download, decision)
            decisionLedger.record(download, decision, conditions.nowEpochMs)
            refreshStatusMessage(decision)
            return decision
        } finally {
            evaluationMutex.unlock()
        }
    }

    /**
     * Android may deliver a previously scheduled service/job/worker after queue policy changed.
     * Every execution owner must therefore prove that the durable Connecting claim still belongs
     * to this attempt and that no process-independent admission hold is active.
     */
    internal suspend fun authorizeClaimedExecution(downloadId: String, queueClaimToken: Long): ClaimedExecutionAuthorization {
        if (admissionGate.currentHold() != null) return ClaimedExecutionAuthorization.TemporarilyHeld
        val current = repository.findDownload(downloadId) ?: return ClaimedExecutionAuthorization.Stale
        // The Android execution owner is scheduled before backend ownership exists, so its durable
        // authority is the exact Connecting-row write token, not backend attemptGeneration.
        if (queueClaimToken <= 0L || current.state != DownloadState.Connecting || current.updatedAtEpochMs != queueClaimToken) {
            return ClaimedExecutionAuthorization.Stale
        }
        // Reinstall the process-local stale-callback guard after process recreation. The durable
        // Connecting-row token above remains the source of truth for authorization.
        AndroidExecutionClaimRegistry.install(downloadId, queueClaimToken)
        return ClaimedExecutionAuthorization.Ready
    }

    suspend fun resumeAllManual(): Int {
        admissionGate.clearPauseAll()
        val downloads = repository.findDownloadsByStates(setOf(DownloadState.Paused, DownloadState.WaitingForNetwork, DownloadState.WaitingForPower, DownloadState.Failed))
        downloads.forEach { requestStart(it.id, userVisible = true, manual = true) }
        return downloads.size
    }

    /** Evaluates and atomically claims eligible records without launching Android components. */
    suspend fun evaluateAndClaim(): QueueReconcileOutcome {
        evaluationMutex.lock()
        try {
            admissionGate.currentHold()?.let { hold ->
                val summary = QueueIntelligenceSummary(
                    evaluatedAtEpochMs = System.currentTimeMillis(),
                    heldForSchedule = 1,
                    recentDecisions = decisionLedger.recent(),
                    message = if (hold.reason == DurableQueueAdmissionGate.REASON_PAUSE_ALL) "Pause All is durably active; queue starts are blocked." else "Startup ownership recovery is incomplete; queue starts are blocked.",
                )
                _status.value = summary
                return QueueReconcileOutcome(summary, emptyList())
            }

            val now = System.currentTimeMillis()
            val queues = repository.queues.first().associateBy(QueueDefinition::id)
            val schedules = repository.schedules.first()
            val downloads = repository.downloads.first()
            var started = 0
            var network = 0
            var power = 0
            var storage = 0
            var schedule = 0
            var concurrency = 0
            var retry = 0
            var retryLimit = 0
            var manualReview = 0
            val eligible = mutableListOf<Download>()

            downloads.filter { it.state in TERMINAL_CLEAR_STATES }.forEach {
                retryLedger.clear(it.id)
                QueueIntelligenceWorker.cancelRetry(appContext, it.id)
            }

            val candidatesByQueue = downloads
                .filter { it.state in CANDIDATE_STATES || (it.state == DownloadState.Paused && it.errorMessage.orEmpty().startsWith(POLICY_PREFIX)) }
                .groupBy { it.queueId ?: "default" }

            candidatesByQueue.forEach { (queueId, candidates) ->
                val queue = queues[queueId] ?: fallbackQueue(queueId)
                val resolved = QueuePolicyCodec.resolve(queue, schedules, now)
                val ranked = QueueIntelligencePlanner.rank(candidates, now)
                ranked.forEach { rankedDownload ->
                    val download = rankedDownload.download
                    val conditions = conditionsReader.snapshot(now, download.destinationUri)
                    val activeCount = repository.findDownloadsByStates(ACTIVE_STATES).count { (it.queueId ?: "default") == queue.id }
                    val retryRecord = if (download.state == DownloadState.Failed) {
                        retryLedger.observeFailure(download, resolved.policy.retryStrategy, MediaRequestHandoffStore.forDownload(download.id) != null)
                    } else retryLedger.get(download.id)
                    val decision = if (download.state == DownloadState.Failed && retryLedger.requiresSecureContext(download.id) && MediaRequestHandoffStore.forDownload(download.id) == null) {
                        QueueLaunchDecision(QueueLaunchDisposition.Hold, QueueHoldReason.AuthenticationRequired, "Secure request context required", "The encrypted request material for this retry is unavailable; manual review is required.")
                    } else {
                        QueueIntelligencePlanner.decision(
                            policy = resolved.policy,
                            conditions = conditions,
                            queueEnabled = queue.isEnabled,
                            scheduleActive = !resolved.hasApplicableRules || resolved.activeRuleName != null,
                            scheduleSummary = resolved.nextWindowSummary,
                            activeCount = activeCount,
                            retryRecord = retryRecord,
                            failureMessage = download.errorMessage.takeIf { download.state == DownloadState.Failed },
                        )
                    }
                    when (decision.reason) {
                        QueueHoldReason.NetworkUnavailable, QueueHoldReason.UnmeteredRequired, QueueHoldReason.WifiRequired -> network++
                        QueueHoldReason.ChargingRequired, QueueHoldReason.BatteryLow -> power++
                        QueueHoldReason.StoragePressure -> storage++
                        QueueHoldReason.ScheduleWindow, QueueHoldReason.QueueDisabled -> schedule++
                        QueueHoldReason.ConcurrencyLimit -> concurrency++
                        QueueHoldReason.RetryBackoff -> retry++
                        QueueHoldReason.RetryLimit -> retryLimit++
                        QueueHoldReason.AuthenticationRequired, QueueHoldReason.PermissionRequired, QueueHoldReason.VerificationFailed,
                        QueueHoldReason.UnsupportedFailure, QueueHoldReason.PermanentFailure, QueueHoldReason.NonRetryableFailure -> manualReview++
                        null -> Unit
                    }
                    decision.nextEligibleAtEpochMs?.let { QueueIntelligenceWorker.scheduleRetry(appContext, download.id, it) }
                    if (decision.canStart) {
                        if (claimForLaunch(download, queue, decision, manual = false, activeCount = activeCount)) {
                            val claimed = repository.findDownload(download.id) ?: download.copy(state = DownloadState.Connecting)
                            AndroidExecutionClaimRegistry.install(claimed.id, claimed.updatedAtEpochMs)
                            eligible += claimed
                            started++
                        } else {
                            concurrency++
                            applyHold(download, QueueLaunchDecision(QueueLaunchDisposition.Hold, QueueHoldReason.ConcurrencyLimit, "Queue limit reached", "A durable slot was no longer available when the claim transaction executed."))
                        }
                    } else applyHold(download, decision)
                    decisionLedger.record(download, decision, conditions.nowEpochMs)
                }
            }

            val waitingTotal = network + power + storage + schedule + concurrency + retry + retryLimit + manualReview
            val summary = QueueIntelligenceSummary(
                evaluatedAtEpochMs = now,
                started = started,
                heldForNetwork = network,
                heldForPower = power,
                heldForStorage = storage,
                heldForSchedule = schedule,
                heldForConcurrency = concurrency,
                waitingForRetry = retry,
                retryLimitReached = retryLimit,
                manualReviewRequired = manualReview,
                recentDecisions = decisionLedger.recent(),
                message = when {
                    started > 0 -> "Durably claimed $started transfer${if (started == 1) "" else "s"} after evaluating current conditions."
                    waitingTotal > 0 -> "Queue conditions evaluated; waiting downloads remain explainably held."
                    else -> "Queue conditions evaluated; there are no eligible waiting downloads."
                },
            )
            _status.value = summary
            return QueueReconcileOutcome(summary, eligible)
        } finally {
            evaluationMutex.unlock()
        }
    }

    suspend fun reconcile(): QueueIntelligenceSummary {
        val outcome = evaluateAndClaim()
        outcome.eligibleDownloads.forEach { download ->
            val launch = executionStarter.start(download.id, download.totalBytes, userVisible = false, queueClaimToken = download.updatedAtEpochMs)
            if (!launch.accepted) {
                AndroidExecutionClaimRegistry.release(download.id, download.updatedAtEpochMs)
                repository.releaseQueueLaunchClaim(download.id, download.attemptGeneration, download.updatedAtEpochMs, "Queue policy: Android execution owner could not be scheduled.")
            }
        }
        return outcome.summary
    }

    fun recordTerminalEvent(event: TransferTerminalEvent) {
        if (event.state == DownloadState.Completed || event.state == DownloadState.Cancelled) {
            retryLedger.clear(event.downloadId)
            QueueIntelligenceWorker.cancelRetry(appContext, event.downloadId)
        }
    }

    suspend fun pauseAllDurably(): DurableQueueCommandResult {
        val now = System.currentTimeMillis()
        admissionGate.installPauseAll(now, now)
        val downloads = repository.findDownloadsByStates(QueueStateMachinePlanner.activePauseStates)
        val result = QueueStateMachinePlanner.planPauseAll(downloads, generation = now, nowEpochMs = now)
        phase4Coordinator.recordPauseAll(result)
        return result
    }

    fun installStartupRecoveryHold() = admissionGate.installStartupRecovery()
    fun clearStartupRecoveryHold() = admissionGate.clearStartupRecovery()

    suspend fun deleteQueueSafely(queueId: String, replacementQueueId: String? = null): QueueDeletionPlan {
        val referenced = repository.downloads.first().filter { it.queueId == queueId }.map { it.id }
        val plan = if (referenced.isEmpty()) {
            QueueDeletionPlan(queueId, QueueDeletionDisposition.Delete, emptyList(), null, "Queue has no referenced downloads and can be deleted safely.")
        } else if (replacementQueueId != null) {
            QueueDeletionPlan(queueId, QueueDeletionDisposition.ReassignThenDelete, referenced, replacementQueueId, "Queue deletion will first reassign referenced downloads to $replacementQueueId.")
        } else {
            QueueDeletionPlan(queueId, QueueDeletionDisposition.RejectDanglingReferences, referenced, null, "Queue deletion rejected because downloads still reference this queue.")
        }
        phase4Coordinator.recordQueueDeletion(plan)
        if (plan.disposition == QueueDeletionDisposition.Delete) repository.deleteQueue(queueId)
        else if (plan.disposition == QueueDeletionDisposition.ReassignThenDelete && replacementQueueId != null) repository.reassignQueueThenDelete(queueId, replacementQueueId)
        return plan
    }

    fun recordImmediateReevaluation(source: String, coalesceKey: String = "queue-intelligence") {
        phase4Coordinator.requestImmediateReevaluation(source, coalesceKey, System.currentTimeMillis())
    }

    fun clearDecisionHistory() {
        decisionLedger.clear()
        _status.value = _status.value.copy(recentDecisions = emptyList(), message = "Queue decision history cleared; transfer records were not removed.")
    }

    private suspend fun claimForLaunch(download: Download, queue: QueueDefinition, decision: QueueLaunchDecision, manual: Boolean, activeCount: Int): Boolean {
        if (manual) {
            retryLedger.clear(download.id)
            QueueIntelligenceWorker.cancelRetry(appContext, download.id)
        }
        val accepted = repository.claimQueueSlotAtomically(
            downloadId = download.id,
            queueId = download.queueId,
            maxConcurrent = queue.maxConcurrent.coerceIn(1, 16),
            activeStates = ACTIVE_STATES,
            candidateStates = CANDIDATE_STATES + setOf(DownloadState.Paused),
        )
        val audit = QueueStateMachinePlanner.reserveSlotAtomically(
            queueId = queue.id,
            downloadId = download.id,
            attemptGeneration = download.attemptGeneration,
            activeCount = if (accepted) activeCount else queue.maxConcurrent.coerceAtLeast(1),
            budget = QueueBudget(maxConcurrent = queue.maxConcurrent.coerceIn(1, 16)),
        )
        phase4Coordinator.recordQueueReservation(audit)
        return accepted
    }

    private suspend fun applyHold(download: Download, decision: QueueLaunchDecision) {
        if (download.state == DownloadState.Failed) {
            retryLedger.recordHold(download.id, decision.reason?.name ?: "Unknown", decision.detail)
            return
        }
        val state = when (decision.reason) {
            QueueHoldReason.NetworkUnavailable, QueueHoldReason.UnmeteredRequired, QueueHoldReason.WifiRequired -> DownloadState.WaitingForNetwork
            QueueHoldReason.ChargingRequired, QueueHoldReason.BatteryLow -> DownloadState.WaitingForPower
            QueueHoldReason.StoragePressure -> DownloadState.Paused
            else -> DownloadState.Queued
        }
        val message = POLICY_PREFIX + decision.detail
        if (download.state != state || download.errorMessage != message) {
            check(repository.save(download.copy(state = state, errorMessage = message, speedBytesPerSecond = 0, updatedAtEpochMs = maxOf(System.currentTimeMillis(), download.updatedAtEpochMs + 1L)))) {
                "Queue hold persistence was rejected because a newer durable download state exists."
            }
        }
    }

    private fun refreshStatusMessage(decision: QueueLaunchDecision) {
        _status.value = _status.value.copy(evaluatedAtEpochMs = System.currentTimeMillis(), recentDecisions = decisionLedger.recent(), message = decision.detail)
    }

    private fun fallbackQueue(queueId: String?) = QueueDefinition(
        id = queueId ?: "default",
        name = if (queueId.isNullOrBlank() || queueId == "default") "Default" else queueId,
        isEnabled = true,
        maxConcurrent = 3,
        createdAtEpochMs = 0L,
    )

    private fun hold(title: String, detail: String) = QueueLaunchDecision(QueueLaunchDisposition.Hold, QueueHoldReason.QueueDisabled, title, detail)

    companion object {
        val ACTIVE_STATES = setOf(DownloadState.Connecting, DownloadState.Downloading, DownloadState.Verifying, DownloadState.Repairing, DownloadState.Finalizing)
        val CANDIDATE_STATES = setOf(DownloadState.Created, DownloadState.Queued, DownloadState.WaitingForNetwork, DownloadState.WaitingForPower, DownloadState.Failed)
        val TERMINAL_CLEAR_STATES = setOf(DownloadState.Completed, DownloadState.Cancelled)
        private const val POLICY_PREFIX = "Queue policy: "
    }
}

interface QueueIntelligenceProvider {
    val queueIntelligenceCoordinator: QueueIntelligenceCoordinator
}
