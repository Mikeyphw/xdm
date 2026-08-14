package com.mikeyphw.xdm.android.scheduler

import android.annotation.SuppressLint
import android.app.job.JobParameters
import android.app.job.JobService
import android.os.Build
import androidx.annotation.RequiresApi
import com.mikeyphw.xdm.android.model.DownloadState
import com.mikeyphw.xdm.android.model.SystemExecutionOwner
import com.mikeyphw.xdm.android.model.SystemStopReasonRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@SuppressLint("SpecifyJobSchedulerIdRange")
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class UserInitiatedTransferJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = java.util.concurrent.ConcurrentHashMap<Int, Job>()

    override fun onStartJob(params: JobParameters): Boolean {
        val downloadId = params.extras.getString(TransferNotifications.EXTRA_DOWNLOAD_ID) ?: return false
        val queueClaimToken = params.extras.getLong(TransferExecutionStarter.EXTRA_QUEUE_CLAIM_TOKEN, 0L)
        val runtime = (application as TransferRuntimeProvider).transferRuntime
        val queue = (application as QueueIntelligenceProvider).queueIntelligenceCoordinator
        val notifications = TransferNotifications(this)
        val notificationId = TransferSystemIdRegistry(this).idFor(downloadId)
        setNotification(params, notificationId, notifications.active(ActiveTransferSummary(activeCount = 1, primaryDownloadId = downloadId)), JOB_END_NOTIFICATION_POLICY_DETACH)
        jobs[params.jobId] = scope.launch {
            when (queue.authorizeClaimedExecution(downloadId, queueClaimToken)) {
                ClaimedExecutionAuthorization.TemporarilyHeld -> {
                    jobFinished(params, true)
                    jobs.remove(params.jobId)
                    return@launch
                }
                ClaimedExecutionAuthorization.Stale -> {
                    jobFinished(params, false)
                    jobs.remove(params.jobId)
                    return@launch
                }
                ClaimedExecutionAuthorization.Ready -> Unit
            }
            val updater = launch {
                runtime.summary.collectLatest { summary ->
                    setNotification(params, notificationId, notifications.active(summary, downloadId), JOB_END_NOTIFICATION_POLICY_DETACH)
                }
            }
            val state = runtime.execute(downloadId, queueClaimToken)
            updater.cancel()
            val result = runtime.findDownload(downloadId)
            notifications.terminalIfFirst(
                downloadId = downloadId,
                fileName = result?.fileName ?: "Download",
                state = state,
                message = result?.errorMessage,
                destinationUri = result?.destinationUri,
                mimeType = result?.mimeType,
                attemptGeneration = result?.attemptGeneration ?: 0L,
            )?.let { setNotification(params, notificationId, it, JOB_END_NOTIFICATION_POLICY_DETACH) }
            AndroidExecutionClaimRegistry.release(downloadId, queueClaimToken)
            val reschedule = state in setOf(DownloadState.WaitingForNetwork, DownloadState.WaitingForPower)
            jobFinished(params, reschedule)
            jobs.remove(params.jobId)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        val downloadId = params.extras.getString(TransferNotifications.EXTRA_DOWNLOAD_ID)
        jobs.remove(params.jobId)?.cancel()
        if (downloadId != null) {
            val runtime = (application as TransferRuntimeProvider).transferRuntime
            val queueClaimToken = params.extras.getLong(TransferExecutionStarter.EXTRA_QUEUE_CLAIM_TOKEN, 0L)
            val jobStopReason = params.stopReason
            // onStopJob may arrive after a replacement owner has been claimed. Serialize teardown
            // against that queue-claim token so an old UIDT callback cannot pause newer work.
            val attemptGeneration = runtime.activeAttemptGenerationOwned(downloadId, queueClaimToken) ?: 0L
            val record = (application as? QueueSchedulingRecoveryProvider)?.queueSchedulingRecoveryCoordinator?.recordSystemStop(
                downloadId = downloadId,
                attemptGeneration = attemptGeneration,
                owner = SystemExecutionOwner.UserInitiatedJob,
                stopReason = jobStopReason,
                nowEpochMs = System.currentTimeMillis(),
            ) ?: SystemStopReasonRecord(
                downloadId = downloadId,
                attemptGeneration = attemptGeneration,
                owner = SystemExecutionOwner.UserInitiatedJob,
                jobParametersStopReason = jobStopReason,
                occurredAtEpochMs = System.currentTimeMillis(),
                message = "User-initiated job stopped; only its exact queue claim may pause backend ownership.",
            )
            TransferExecutionStopReasonRecorder.record(record)
            runtime.requestPauseOwnedAsync(downloadId, queueClaimToken)
        }
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
