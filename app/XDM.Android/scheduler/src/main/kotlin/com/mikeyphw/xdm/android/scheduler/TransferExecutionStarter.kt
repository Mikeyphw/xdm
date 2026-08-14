package com.mikeyphw.xdm.android.scheduler

import android.annotation.SuppressLint
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.PersistableBundle
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat

class TransferExecutionStarter(private val context: Context) {
    private val systemIds = TransferSystemIdRegistry(context)

    @SuppressLint("NewApi")
    internal fun start(downloadId: String, estimatedBytes: Long? = null, userVisible: Boolean = true, queueClaimToken: Long = 0L): TransferLaunchResult {
        val preferred = TransferLaunchPolicy.select(Build.VERSION.SDK_INT, userVisible)
        return when (preferred) {
            TransferLaunchMode.UserInitiatedJob -> scheduleUserInitiatedJob(downloadId, estimatedBytes, queueClaimToken)
                .takeIf(TransferLaunchResult::accepted) ?: enqueueWorkManager(downloadId, queueClaimToken)
            TransferLaunchMode.ForegroundService -> startForegroundServiceSafely(downloadId, queueClaimToken)
            TransferLaunchMode.WorkManager -> enqueueWorkManager(downloadId, queueClaimToken)
        }
    }


    private fun startForegroundServiceSafely(downloadId: String, queueClaimToken: Long): TransferLaunchResult {
        val id = systemIds.idFor(downloadId)
        val intent = Intent(context, TransferForegroundService::class.java)
            .setAction(TransferForegroundService.ACTION_START)
            .putExtra(TransferNotifications.EXTRA_DOWNLOAD_ID, downloadId)
            .putExtra(EXTRA_QUEUE_CLAIM_TOKEN, queueClaimToken)
        return runCatching {
            ContextCompat.startForegroundService(context, intent)
            TransferLaunchResult(true, TransferLaunchMode.ForegroundService, id)
        }.getOrElse {
            // Android 12+ can reject background FGS starts. The already-durable queue claim is
            // handed to WorkManager instead of retrying an illegal service launch.
            enqueueWorkManager(downloadId, queueClaimToken)
        }
    }

    private fun enqueueWorkManager(downloadId: String, queueClaimToken: Long): TransferLaunchResult = runCatching {
        QueueIntelligenceWorker.enqueueClaimed(context, downloadId, queueClaimToken)
        TransferLaunchResult(true, TransferLaunchMode.WorkManager, systemIds.idFor(downloadId))
    }.getOrElse { TransferLaunchResult(false, TransferLaunchMode.WorkManager, systemIds.idFor(downloadId)) }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun scheduleUserInitiatedJob(downloadId: String, estimatedBytes: Long?, queueClaimToken: Long): TransferLaunchResult {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        val jobId = systemIds.idFor(downloadId)
        val extras = PersistableBundle().apply {
            putString(TransferNotifications.EXTRA_DOWNLOAD_ID, downloadId)
            putLong(EXTRA_QUEUE_CLAIM_TOKEN, queueClaimToken)
        }
        val network = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        val builder = JobInfo.Builder(jobId, ComponentName(context, UserInitiatedTransferJobService::class.java))
            .setUserInitiated(true)
            .setRequiredNetwork(network)
            .setRequiresStorageNotLow(true)
            .setExtras(extras)
        if (estimatedBytes != null && estimatedBytes >= 0) builder.setEstimatedNetworkBytes(estimatedBytes, 0L)
        val result = runCatching { scheduler.schedule(builder.build()) }.getOrDefault(JobScheduler.RESULT_FAILURE)
        return TransferLaunchResult(result == JobScheduler.RESULT_SUCCESS, TransferLaunchMode.UserInitiatedJob, jobId)
    }

    companion object {
        const val EXTRA_QUEUE_CLAIM_TOKEN = "queue_claim_token"
    }
}
