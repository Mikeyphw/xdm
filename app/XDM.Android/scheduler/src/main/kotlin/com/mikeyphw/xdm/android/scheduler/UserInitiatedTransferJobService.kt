package com.mikeyphw.xdm.android.scheduler

import android.annotation.SuppressLint
import android.app.job.JobParameters
import android.app.job.JobService
import android.os.Build
import androidx.annotation.RequiresApi

/** XGO-71 UIDT adapter: the job keeps Android execution legal, while Go owns all scheduler policy. */
@SuppressLint("SpecifyJobSchedulerIdRange")
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class UserInitiatedTransferJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val downloadId = params.extras.getString(TransferNotifications.EXTRA_DOWNLOAD_ID) ?: return false
        val queueClaimToken = params.extras.getLong(TransferExecutionStarter.EXTRA_QUEUE_CLAIM_TOKEN, 0L)
        val notificationId = TransferSystemIdRegistry(this).idFor(downloadId)
        runCatching {
            setNotification(
                params,
                notificationId,
                TransferNotifications(this).active(
                    ActiveTransferSummary(activeCount = 1, primaryDownloadId = downloadId, primaryFileName = "Preparing download"),
                    downloadId,
                ),
                JOB_END_NOTIFICATION_POLICY_REMOVE,
            )
        }.onFailure {
            jobFinished(params, true)
            return false
        }

        val result = AndroidSchedulerHost.wake(
            this,
            AndroidEngineWakeRequest(
                eventId = "uidt:$downloadId:$queueClaimToken:${params.jobId}",
                downloadId = downloadId,
                reason = AndroidEngineWakeReason.USER_INITIATED_DATA_TRANSFER,
                userInitiated = true,
            ),
        )
        jobFinished(params, result.disposition == AndroidEngineWakeDisposition.RETRYABLE)
        return false
    }

    override fun onStopJob(params: JobParameters): Boolean {
        // Android may re-deliver the platform opportunity. No Kotlin transfer owner exists to pause.
        return true
    }
}
