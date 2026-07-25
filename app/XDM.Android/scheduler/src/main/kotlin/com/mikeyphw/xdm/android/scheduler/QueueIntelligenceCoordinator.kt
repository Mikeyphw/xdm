package com.mikeyphw.xdm.android.scheduler

import android.content.Context
import com.mikeyphw.xdm.android.model.Download
import com.mikeyphw.xdm.android.model.DownloadState
import com.mikeyphw.xdm.android.model.QueueDefinition
import com.mikeyphw.xdm.android.model.QueueHoldReason
import com.mikeyphw.xdm.android.model.QueueIntelligencePlanner
import com.mikeyphw.xdm.android.model.QueueIntelligenceSummary
import com.mikeyphw.xdm.android.model.QueueLaunchDecision
import com.mikeyphw.xdm.android.model.QueueLaunchDisposition
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

class QueueIntelligenceCoordinator(
    context: Context,
    private val repository: DownloadRepository,
    private val executionStarter: TransferExecutionStarter,
    private val conditionsReader: AndroidQueueConditionsReader = AndroidQueueConditionsReader(context),
    private val retryLedger: QueueRetryLedger = QueueRetryLedger(context),
    private val decisionLedger: QueueDecisionLedger = QueueDecisionLedger(context),
) {
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
            val download = repository.findDownload(downloadId) ?: return QueueLaunchDecision(
                QueueLaunchDisposition.Hold,
                title = "Download unavailable",
                detail = "The queued record no longer exists.",
            )
            val queues = repository.queues.first()
            val schedules = repository.schedules.first()
            val queue = queues.firstOrNull { it.id == download.queueId } ?: fallbackQueue(download.queueId)
            val conditions = conditionsReader.snapshot()
            val resolved = QueuePolicyCodec.resolve(queue, schedules, conditions.nowEpochMs)
            val activeCount = repository.findDownloadsByStates(ACTIVE_STATES).count { (it.queueId ?: "default") == queue.id }
            val retryRecord = if (!manual && download.state == DownloadState.Failed) {
                retryLedger.observeFailure(download, resolved.policy.retryStrategy)
            } else {
                retryLedger.get(download.id)
            }
            val decision = QueueIntelligencePlanner.decision(
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
            if (decision.canStart) {
                claimForLaunch(download, decision, manual)
                executionStarter.start(download.id, download.totalBytes, userVisible)
            } else {
                applyHold(download, decision)
            }
            decisionLedger.record(download, decision, conditions.nowEpochMs)
            refreshStatusMessage(decision)
            return decision
        } finally {
            evaluationMutex.unlock()
        }
    }

    suspend fun resumeAllManual(): Int {
        val downloads = repository.findDownloadsByStates(
            setOf(DownloadState.Paused, DownloadState.WaitingForNetwork, DownloadState.WaitingForPower, DownloadState.Failed),
        )
        downloads.forEach { requestStart(it.id, userVisible = true, manual = true) }
        return downloads.size
    }

    /**
     * Evaluates and claims eligible records without launching Android execution components.
     * WorkManager uses this path so the worker itself can own the foreground lifetime.
     */
    suspend fun evaluateAndClaim(): QueueReconcileOutcome {
        evaluationMutex.lock()
        try {
            val conditions = conditionsReader.snapshot()
            val queues = repository.queues.first().associateBy(QueueDefinition::id)
            val schedules = repository.schedules.first()
            val downloads = repository.downloads.first()
            val activeCounts = downloads
                .filter { it.state in ACTIVE_STATES }
                .groupingBy { it.queueId ?: "default" }
                .eachCount()
                .toMutableMap()
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

            downloads.filter { it.state in TERMINAL_CLEAR_STATES }.forEach { retryLedger.clear(it.id) }

            val candidatesByQueue = downloads
                .filter { it.state in CANDIDATE_STATES || (it.state == DownloadState.Paused && it.errorMessage.orEmpty().startsWith(POLICY_PREFIX)) }
                .groupBy { it.queueId ?: "default" }

            candidatesByQueue.forEach { (queueId, candidates) ->
                val queue = queues[queueId] ?: fallbackQueue(queueId)
                val resolved = QueuePolicyCodec.resolve(queue, schedules, conditions.nowEpochMs)
                val ranked = QueueIntelligencePlanner.rank(candidates, conditions.nowEpochMs)
                var activeCount = activeCounts[queueId] ?: 0
                ranked.forEach { rankedDownload ->
                    val download = rankedDownload.download
                    val retryRecord = if (download.state == DownloadState.Failed) {
                        retryLedger.observeFailure(download, resolved.policy.retryStrategy)
                    } else {
                        retryLedger.get(download.id)
                    }
                    val decision = QueueIntelligencePlanner.decision(
                        policy = resolved.policy,
                        conditions = conditions,
                        queueEnabled = queue.isEnabled,
                        scheduleActive = !resolved.hasApplicableRules || resolved.activeRuleName != null,
                        scheduleSummary = resolved.nextWindowSummary,
                        activeCount = activeCount,
                        retryRecord = retryRecord,
                        failureMessage = download.errorMessage.takeIf { download.state == DownloadState.Failed },
                    )
                    when (decision.reason) {
                        QueueHoldReason.NetworkUnavailable,
                        QueueHoldReason.UnmeteredRequired,
                        QueueHoldReason.WifiRequired -> network++
                        QueueHoldReason.ChargingRequired,
                        QueueHoldReason.BatteryLow -> power++
                        QueueHoldReason.StoragePressure -> storage++
                        QueueHoldReason.ScheduleWindow,
                        QueueHoldReason.QueueDisabled -> schedule++
                        QueueHoldReason.ConcurrencyLimit -> concurrency++
                        QueueHoldReason.RetryBackoff -> retry++
                        QueueHoldReason.RetryLimit -> retryLimit++
                        QueueHoldReason.AuthenticationRequired,
                        QueueHoldReason.PermissionRequired,
                        QueueHoldReason.VerificationFailed,
                        QueueHoldReason.UnsupportedFailure,
                        QueueHoldReason.PermanentFailure,
                        QueueHoldReason.NonRetryableFailure -> manualReview++
                        null -> Unit
                    }
                    if (decision.canStart) {
                        claimForLaunch(download, decision, manual = false)
                        eligible += download
                        activeCount++
                        activeCounts[queueId] = activeCount
                        started++
                    } else {
                        applyHold(download, decision)
                    }
                    decisionLedger.record(download, decision, conditions.nowEpochMs)
                }
            }
            val waitingTotal = network + power + storage + schedule + concurrency + retry + retryLimit + manualReview
            val message = when {
                started > 0 -> "Claimed $started queued transfer${if (started == 1) "" else "s"} after evaluating current conditions."
                waitingTotal > 0 -> "Queue conditions evaluated; waiting downloads remain explainably held."
                else -> "Queue conditions evaluated; there are no eligible waiting downloads."
            }
            val summary = QueueIntelligenceSummary(
                evaluatedAtEpochMs = conditions.nowEpochMs,
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
                message = message,
            )
            _status.value = summary
            return QueueReconcileOutcome(summary, eligible)
        } finally {
            evaluationMutex.unlock()
        }
    }

    /** Foreground/app-visible evaluation path. Background workers should call evaluateAndClaim(). */
    suspend fun reconcile(): QueueIntelligenceSummary {
        val outcome = evaluateAndClaim()
        outcome.eligibleDownloads.forEach { download ->
            executionStarter.start(download.id, download.totalBytes, userVisible = false)
        }
        return outcome.summary
    }

    fun recordTerminalEvent(event: TransferTerminalEvent) {
        if (event.state == DownloadState.Completed || event.state == DownloadState.Cancelled) {
            retryLedger.clear(event.downloadId)
        }
    }

    private suspend fun claimForLaunch(download: Download, decision: QueueLaunchDecision, manual: Boolean) {
        if (manual) retryLedger.clear(download.id)
        val current = repository.findDownload(download.id) ?: download
        repository.save(
            current.copy(
                state = DownloadState.Queued,
                errorMessage = if (decision.policyOverridden) "Queue policy override: starting by explicit user request." else null,
                updatedAtEpochMs = System.currentTimeMillis(),
            ),
        )
    }

    private suspend fun applyHold(download: Download, decision: QueueLaunchDecision) {
        if (download.state == DownloadState.Failed) return
        val state = when (decision.reason) {
            QueueHoldReason.NetworkUnavailable,
            QueueHoldReason.UnmeteredRequired,
            QueueHoldReason.WifiRequired -> DownloadState.WaitingForNetwork
            QueueHoldReason.ChargingRequired,
            QueueHoldReason.BatteryLow -> DownloadState.WaitingForPower
            QueueHoldReason.StoragePressure -> DownloadState.Paused
            else -> DownloadState.Queued
        }
        val message = POLICY_PREFIX + decision.detail
        if (download.state != state || download.errorMessage != message) {
            repository.save(
                download.copy(
                    state = state,
                    errorMessage = message,
                    speedBytesPerSecond = 0,
                    updatedAtEpochMs = System.currentTimeMillis(),
                ),
            )
        }
    }

    private fun refreshStatusMessage(decision: QueueLaunchDecision) {
        _status.value = _status.value.copy(
            evaluatedAtEpochMs = System.currentTimeMillis(),
            recentDecisions = decisionLedger.recent(),
            message = decision.detail,
        )
    }

    private fun fallbackQueue(queueId: String?) = QueueDefinition(
        id = queueId ?: "default",
        name = if (queueId.isNullOrBlank() || queueId == "default") "Default" else queueId,
        isEnabled = true,
        maxConcurrent = 3,
        createdAtEpochMs = 0L,
    )

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
