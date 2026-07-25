package com.mikeyphw.xdm.android.scheduler

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Evaluates queued work and owns the foreground lifetime of automatic transfers.
 * This avoids background foreground-service launches while preserving long downloads.
 */
class QueueIntelligenceWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val queueProvider = applicationContext as? QueueIntelligenceProvider ?: return Result.failure()
        val runtimeProvider = applicationContext as? TransferRuntimeProvider ?: return Result.failure()
        val coordinator = queueProvider.queueIntelligenceCoordinator
        val runtime = runtimeProvider.transferRuntime
        return runCatching {
            repeat(MAX_DRAIN_ROUNDS) {
                val outcome = coordinator.evaluateAndClaim()
                if (outcome.eligibleDownloads.isEmpty()) return Result.success()
                setForeground(createForegroundInfo(outcome.eligibleDownloads.size, outcome.eligibleDownloads.first().id))
                coroutineScope {
                    outcome.eligibleDownloads.map { download ->
                        async {
                            val state = runtime.execute(download.id)
                            val current = runtime.findDownload(download.id)
                            coordinator.recordTerminalEvent(
                                TransferTerminalEvent(
                                    downloadId = download.id,
                                    fileName = current?.fileName ?: download.fileName,
                                    state = state,
                                    message = current?.errorMessage,
                                ),
                            )
                        }
                    }.awaitAll()
                }
            }
            Result.retry()
        }.getOrElse { Result.retry() }
    }

    private fun createForegroundInfo(activeCount: Int, primaryDownloadId: String): ForegroundInfo {
        val notification = TransferNotifications(applicationContext).active(
            ActiveTransferSummary(activeCount = activeCount, primaryDownloadId = primaryDownloadId),
            primaryDownloadId,
        )
        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        return ForegroundInfo(FOREGROUND_NOTIFICATION_ID, notification, serviceType)
    }

    companion object {
        private const val PERIODIC_WORK = "xdm-queue-intelligence-periodic"
        private const val IMMEDIATE_WORK = "xdm-queue-intelligence-now"
        private const val FOREGROUND_NOTIFICATION_ID = 4608
        private const val MAX_DRAIN_ROUNDS = 64

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<QueueIntelligenceWorker>(15, TimeUnit.MINUTES)
                .addTag(PERIODIC_WORK)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        fun enqueueImmediate(context: Context) {
            val request = OneTimeWorkRequestBuilder<QueueIntelligenceWorker>().addTag(IMMEDIATE_WORK).build()
            // KEEP is deliberate: replacing a running foreground worker would cancel active automatic transfers.
            WorkManager.getInstance(context).enqueueUniqueWork(IMMEDIATE_WORK, ExistingWorkPolicy.KEEP, request)
        }
    }
}
