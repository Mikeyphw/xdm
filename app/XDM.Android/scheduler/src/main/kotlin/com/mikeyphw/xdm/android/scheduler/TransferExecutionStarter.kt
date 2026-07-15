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
    @SuppressLint("NewApi")
    fun start(downloadId: String, estimatedBytes: Long? = null, userVisible: Boolean = true): TransferLaunchResult {
        return when (TransferLaunchPolicy.select(Build.VERSION.SDK_INT, userVisible)) {
            TransferLaunchMode.UserInitiatedJob -> scheduleUserInitiatedJob(downloadId, estimatedBytes).let { result ->
                if (result.accepted) result else startForegroundService(downloadId)
            }
            TransferLaunchMode.ForegroundService -> startForegroundService(downloadId)
        }
    }

    fun startFromNotification(downloadId: String): TransferLaunchResult = startForegroundService(downloadId)

    private fun startForegroundService(downloadId: String): TransferLaunchResult {
        val intent = Intent(context, TransferForegroundService::class.java)
            .setAction(TransferForegroundService.ACTION_START)
            .putExtra(TransferNotifications.EXTRA_DOWNLOAD_ID, downloadId)
        ContextCompat.startForegroundService(context, intent)
        return TransferLaunchResult(true, TransferLaunchMode.ForegroundService, downloadId.stableSystemId())
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun scheduleUserInitiatedJob(downloadId: String, estimatedBytes: Long?): TransferLaunchResult {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        val jobId = downloadId.stableSystemId()
        val extras = PersistableBundle().apply { putString(TransferNotifications.EXTRA_DOWNLOAD_ID, downloadId) }
        val network = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        val builder = JobInfo.Builder(jobId, ComponentName(context, UserInitiatedTransferJobService::class.java))
            .setUserInitiated(true)
            .setRequiredNetwork(network)
            .setRequiresStorageNotLow(true)
            .setExtras(extras)
        if (estimatedBytes != null && estimatedBytes >= 0) builder.setEstimatedNetworkBytes(estimatedBytes, 0L)
        val result = scheduler.schedule(builder.build())
        return TransferLaunchResult(result == JobScheduler.RESULT_SUCCESS, TransferLaunchMode.UserInitiatedJob, jobId)
    }
}
