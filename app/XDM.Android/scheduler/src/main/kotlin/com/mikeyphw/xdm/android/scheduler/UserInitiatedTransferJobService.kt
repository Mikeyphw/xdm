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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

@SuppressLint("SpecifyJobSchedulerIdRange")
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class UserInitiatedTransferJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = java.util.concurrent.ConcurrentHashMap<Int, Job>()

    override fun onStartJob(params: JobParameters): Boolean {
        val downloadId = params.extras.getString(TransferNotifications.EXTRA_DOWNLOAD_ID) ?: return false
        val runtime = (application as TransferRuntimeProvider).transferRuntime
        val notifications = TransferNotifications(this)
        setNotification(
            params,
            TransferNotifications.ACTIVE_NOTIFICATION_ID + params.jobId,
            notifications.active(ActiveTransferSummary(activeCount = 1, primaryDownloadId = downloadId)),
            JOB_END_NOTIFICATION_POLICY_DETACH,
        )
        jobs[params.jobId] = scope.launch {
            val updater = launch {
                runtime.summary.collectLatest { summary ->
                    setNotification(
                        params,
                        TransferNotifications.ACTIVE_NOTIFICATION_ID + params.jobId,
                        notifications.active(summary, downloadId),
                        JOB_END_NOTIFICATION_POLICY_DETACH,
                    )
                }
            }
            val state = runtime.execute(downloadId)
            updater.cancel()
            val result = runtime.findDownload(downloadId)
            // State-preserving terminal copy mirrors: notifications.terminal(downloadId, result?.fileName ?: "Download", state, result?.errorMessage, result?.destinationUri, result?.mimeType)
            notifications.terminalIfFirst(
                downloadId = downloadId,
                fileName = result?.fileName ?: "Download",
                state = state,
                message = result?.errorMessage,
                destinationUri = result?.destinationUri,
                mimeType = result?.mimeType,
                attemptGeneration = params.jobId.toLong(),
            )?.let { terminalNotification ->
                setNotification(
                    params,
                    TransferNotifications.ACTIVE_NOTIFICATION_ID + params.jobId,
                    terminalNotification,
                    JOB_END_NOTIFICATION_POLICY_DETACH,
                )
            }
            val reschedule = state in setOf(DownloadState.WaitingForNetwork, DownloadState.WaitingForPower)
            jobFinished(params, reschedule)
            jobs.remove(params.jobId)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        val downloadId = params.extras.getString(TransferNotifications.EXTRA_DOWNLOAD_ID)
        if (downloadId != null) {
            val jobStopReason = params.stopReason
            val record = (application as? QueueSchedulingRecoveryProvider)?.queueSchedulingRecoveryCoordinator?.recordSystemStop(
                downloadId = downloadId,
                attemptGeneration = params.jobId.toLong(),
                owner = SystemExecutionOwner.UserInitiatedJob,
                stopReason = jobStopReason,
                nowEpochMs = System.currentTimeMillis(),
            ) ?: SystemStopReasonRecord(
                downloadId = downloadId,
                attemptGeneration = params.jobId.toLong(),
                owner = SystemExecutionOwner.UserInitiatedJob,
                jobParametersStopReason = jobStopReason,
                occurredAtEpochMs = System.currentTimeMillis(),
                message = "User-initiated job stopped; JobParameters.getStopReason() captured before pausing the transfer.",
            )
            TransferExecutionStopReasonRecorder.record(record)
            runBlocking { withTimeoutOrNull(5_000) { (application as TransferRuntimeProvider).transferRuntime.pause(downloadId) } }
        }
        jobs.remove(params.jobId)?.cancel()
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
