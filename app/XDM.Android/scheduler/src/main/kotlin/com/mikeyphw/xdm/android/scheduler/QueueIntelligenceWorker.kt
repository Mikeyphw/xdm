package com.mikeyphw.xdm.android.scheduler

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import com.mikeyphw.xdm.android.model.SystemExecutionOwner
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

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
        return try {
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
                                    destinationUri = current?.destinationUri ?: download.destinationUri,
                                    mimeType = current?.mimeType ?: download.mimeType,
                                ),
                            )
                        }
                    }.awaitAll()
                }
            }
            Result.retry()
        } catch (_: Throwable) {
            Result.retry()
        } finally {
            if (isStopped) pauseAndRecordStop()
        }
    }

    private suspend fun pauseAndRecordStop() = withContext(NonCancellable + Dispatchers.IO) {
        val runtime = (applicationContext as? TransferRuntimeProvider)?.transferRuntime ?: return@withContext
        val queue = (applicationContext as? QueueIntelligenceProvider)?.queueIntelligenceCoordinator
        val phase4 = (applicationContext as? QueueSchedulingRecoveryProvider)?.queueSchedulingRecoveryCoordinator
        val stopReason = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) getStopReason() else null
        phase4?.recordSystemStop(
            downloadId = "queue-intelligence",
            attemptGeneration = System.currentTimeMillis(),
            owner = SystemExecutionOwner.WorkManager,
            stopReason = stopReason,
            nowEpochMs = System.currentTimeMillis(),
        )?.also(TransferExecutionStopReasonRecorder::record)
        queue?.pauseAllDurably()
        runtime.pauseAll()
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
            (context.applicationContext as? QueueSchedulingRecoveryProvider)
                ?.queueSchedulingRecoveryCoordinator
                ?.requestImmediateReevaluation("queue-intelligence-worker", IMMEDIATE_WORK, System.currentTimeMillis())
            val request = OneTimeWorkRequestBuilder<QueueIntelligenceWorker>().addTag(IMMEDIATE_WORK).build()
            // KEEP is deliberate: replacing a running foreground worker would cancel active automatic transfers.
            WorkManager.getInstance(context).enqueueUniqueWork(IMMEDIATE_WORK, ExistingWorkPolicy.KEEP, request)
        }
    }
}
