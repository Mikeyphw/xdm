package com.mikeyphw.xdm.android.scheduler

import android.annotation.SuppressLint
import android.app.job.JobParameters
import android.app.job.JobService
import android.os.Build
import androidx.annotation.RequiresApi
import com.mikeyphw.xdm.android.model.DownloadState
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
            setNotification(
                params,
                TransferNotifications.ACTIVE_NOTIFICATION_ID + params.jobId,
                notifications.terminal(downloadId, result?.fileName ?: "Download", state, result?.errorMessage),
                JOB_END_NOTIFICATION_POLICY_DETACH,
            )
            val reschedule = state in setOf(DownloadState.WaitingForNetwork, DownloadState.WaitingForPower)
            jobFinished(params, reschedule)
            jobs.remove(params.jobId)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        val downloadId = params.extras.getString(TransferNotifications.EXTRA_DOWNLOAD_ID)
        if (downloadId != null) scope.launch { (application as TransferRuntimeProvider).transferRuntime.pause(downloadId) }
        jobs.remove(params.jobId)?.cancel()
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
