package com.mikeyphw.xdm.android.scheduler

import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mikeyphw.xdm.android.model.DownloadState
import com.mikeyphw.xdm.android.model.SystemExecutionOwner
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/** WorkManager-owned foreground execution for automatic work and legal FGS fallback. */
class QueueIntelligenceWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    private val ownedClaims = java.util.concurrent.ConcurrentHashMap<String, Long>()
    override suspend fun doWork(): Result {
        val queueProvider = applicationContext as? QueueIntelligenceProvider ?: return Result.failure()
        val runtimeProvider = applicationContext as? TransferRuntimeProvider ?: return Result.failure()
        val coordinator = queueProvider.queueIntelligenceCoordinator
        val runtime = runtimeProvider.transferRuntime
        val claimedDownloadId = inputData.getString(INPUT_CLAIMED_DOWNLOAD_ID)
        val queueClaimToken = inputData.getLong(INPUT_QUEUE_CLAIM_TOKEN, Long.MIN_VALUE)
        return try {
            if (!claimedDownloadId.isNullOrBlank()) {
                when (coordinator.authorizeClaimedExecution(claimedDownloadId, queueClaimToken)) {
                    ClaimedExecutionAuthorization.TemporarilyHeld -> return Result.retry()
                    ClaimedExecutionAuthorization.Stale -> return Result.success()
                    ClaimedExecutionAuthorization.Ready -> Unit
                }
                val download = runtime.findDownload(claimedDownloadId) ?: return Result.success()
                setForeground(createForegroundInfo(1, download.id))
                executeAndNotify(download.id, download.fileName, queueClaimToken, coordinator, runtime)
                return Result.success()
            }
            repeat(MAX_DRAIN_ROUNDS) {
                val outcome = coordinator.evaluateAndClaim()
                if (outcome.eligibleDownloads.isEmpty()) return Result.success()
                setForeground(createForegroundInfo(outcome.eligibleDownloads.size, outcome.eligibleDownloads.first().id))
                coroutineScope {
                    outcome.eligibleDownloads.map { download ->
                        async {
                            when (coordinator.authorizeClaimedExecution(download.id, download.updatedAtEpochMs)) {
                                ClaimedExecutionAuthorization.Ready ->
                                    executeAndNotify(download.id, download.fileName, download.updatedAtEpochMs, coordinator, runtime)
                                ClaimedExecutionAuthorization.TemporarilyHeld,
                                ClaimedExecutionAuthorization.Stale -> Unit
                            }
                        }
                    }.awaitAll()
                }
            }
            Result.retry()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            Result.retry()
        } finally {
            if (isStopped) pauseAndRecordStop()
        }
    }

    private suspend fun executeAndNotify(
        downloadId: String,
        fallbackName: String,
        queueClaimToken: Long,
        coordinator: QueueIntelligenceCoordinator,
        runtime: TransferExecutionRuntime,
    ) {
        ownedClaims[downloadId] = queueClaimToken
        val state = try {
            runtime.execute(downloadId, queueClaimToken)
        } finally {
            if (!isStopped) {
                ownedClaims.remove(downloadId, queueClaimToken)
                AndroidExecutionClaimRegistry.release(downloadId, queueClaimToken)
            }
        }
        val current = runtime.findDownload(downloadId)
        val event = TransferTerminalEvent(
            downloadId = downloadId,
            fileName = current?.fileName ?: fallbackName,
            state = state,
            message = current?.errorMessage,
            destinationUri = current?.let { download ->
                if (state == DownloadState.Completed && download.completedArtifactGeneration == download.attemptGeneration) {
                    download.completedArtifactUri
                } else {
                    download.destinationUri
                }
            },
            mimeType = current?.mimeType,
            attemptGeneration = current?.attemptGeneration ?: 0L,
        )
        coordinator.recordTerminalEvent(event)
        TransferNotifications(applicationContext).terminalIfFirst(
            downloadId = event.downloadId,
            fileName = event.fileName,
            state = event.state,
            message = event.message,
            destinationUri = event.destinationUri,
            mimeType = event.mimeType,
            attemptGeneration = event.attemptGeneration,
        )?.let { notification ->
            applicationContext.getSystemService(NotificationManager::class.java)
                .notify(TransferSystemIdRegistry(applicationContext).idFor(downloadId), notification)
        }
    }

    private suspend fun pauseAndRecordStop() = withContext(NonCancellable + Dispatchers.IO) {
        val runtime = (applicationContext as? TransferRuntimeProvider)?.transferRuntime ?: return@withContext
        val phase4 = (applicationContext as? QueueSchedulingRecoveryProvider)?.queueSchedulingRecoveryCoordinator
        val stopReason = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) getStopReason() else null
        val claimed = inputData.getString(INPUT_CLAIMED_DOWNLOAD_ID)
        val claimedToken = inputData.getLong(INPUT_QUEUE_CLAIM_TOKEN, Long.MIN_VALUE)
        val owned = if (claimed != null && claimedToken > 0L) mapOf(claimed to claimedToken) else ownedClaims.toMap()
        owned.forEach { (downloadId, queueClaimToken) ->
            val durableGeneration = runtime.activeAttemptGenerationOwned(downloadId, queueClaimToken) ?: 0L
            phase4?.recordSystemStop(
                downloadId = downloadId,
                attemptGeneration = durableGeneration,
                owner = SystemExecutionOwner.WorkManager,
                stopReason = stopReason,
                nowEpochMs = System.currentTimeMillis(),
            )?.also(TransferExecutionStopReasonRecorder::record)
            runtime.pauseOwned(downloadId, queueClaimToken)
            AndroidExecutionClaimRegistry.release(downloadId, queueClaimToken)
        }
        owned.keys.forEach(ownedClaims::remove)
    }

    private fun createForegroundInfo(activeCount: Int, primaryDownloadId: String): ForegroundInfo {
        val notification = TransferNotifications(applicationContext).active(
            ActiveTransferSummary(activeCount = activeCount, primaryDownloadId = primaryDownloadId),
            primaryDownloadId,
        )
        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        return ForegroundInfo(FOREGROUND_NOTIFICATION_ID, notification, serviceType)
    }

    companion object {
        private const val PERIODIC_WORK = "xdm-queue-intelligence-periodic"
        private const val IMMEDIATE_WORK = "xdm-queue-intelligence-now"
        private const val CLAIMED_PREFIX = "xdm-transfer-claimed-"
        private const val RETRY_PREFIX = "xdm-transfer-retry-"
        private const val INPUT_CLAIMED_DOWNLOAD_ID = "claimed_download_id"
        private const val INPUT_QUEUE_CLAIM_TOKEN = "queue_claim_token"
        private const val FOREGROUND_NOTIFICATION_ID = 4608
        private const val MAX_DRAIN_ROUNDS = 64

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<QueueIntelligenceWorker>(15, TimeUnit.MINUTES).addTag(PERIODIC_WORK).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        fun enqueueImmediate(context: Context) {
            (context.applicationContext as? QueueSchedulingRecoveryProvider)?.queueSchedulingRecoveryCoordinator
                ?.requestImmediateReevaluation("queue-intelligence-worker", IMMEDIATE_WORK, System.currentTimeMillis())
            val request = OneTimeWorkRequestBuilder<QueueIntelligenceWorker>().addTag(IMMEDIATE_WORK).build()
            WorkManager.getInstance(context).enqueueUniqueWork(IMMEDIATE_WORK, ExistingWorkPolicy.KEEP, request)
        }

        fun enqueueClaimed(context: Context, downloadId: String, queueClaimToken: Long) {
            require(queueClaimToken > 0L) { "Claimed WorkManager execution requires a durable queue claim token" }
            val workName = claimedWorkName(downloadId, queueClaimToken)
            val request = OneTimeWorkRequestBuilder<QueueIntelligenceWorker>()
                .setInputData(
                    Data.Builder()
                        .putString(INPUT_CLAIMED_DOWNLOAD_ID, downloadId)
                        .putLong(INPUT_QUEUE_CLAIM_TOKEN, queueClaimToken)
                        .build(),
                )
                .addTag(workName)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(workName, ExistingWorkPolicy.KEEP, request)
        }

        internal fun claimedWorkName(downloadId: String, queueClaimToken: Long): String =
            "$CLAIMED_PREFIX$downloadId-c$queueClaimToken"

        fun scheduleRetry(context: Context, downloadId: String, retryAtEpochMs: Long) {
            val delay = (retryAtEpochMs - System.currentTimeMillis()).coerceAtLeast(0L)
            val request = OneTimeWorkRequestBuilder<QueueIntelligenceWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .addTag(RETRY_PREFIX + downloadId)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(RETRY_PREFIX + downloadId, ExistingWorkPolicy.REPLACE, request)
        }

        fun cancelRetry(context: Context, downloadId: String) {
            WorkManager.getInstance(context).cancelUniqueWork(RETRY_PREFIX + downloadId)
        }
    }
}
