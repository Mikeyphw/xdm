package com.mikeyphw.xdm.android.scheduler

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * XGO-71 WorkManager adapter.
 *
 * This worker does not evaluate the queue and never executes a transfer. It only
 * wakes the single Go engine. Retry deadlines accepted here are supplied by Go;
 * Kotlin performs no retry/backoff calculation.
 */
class QueueIntelligenceWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val downloadId = inputData.getString(INPUT_DOWNLOAD_ID)
        val retryDueAt = inputData.getLong(INPUT_ENGINE_RETRY_DUE_AT, Long.MIN_VALUE).takeIf { it > 0L }
        val reason = inputData.getString(INPUT_WAKE_REASON)
            ?.let { raw -> runCatching { AndroidEngineWakeReason.valueOf(raw) }.getOrNull() }
            ?: AndroidEngineWakeReason.PERIODIC_WORK
        val eventId = inputData.getString(INPUT_EVENT_ID)
            ?: "periodic:${id}:${System.currentTimeMillis() / PERIODIC_INTERVAL_MS}"
        return AndroidSchedulerHost.wake(
            applicationContext,
            AndroidEngineWakeRequest(
                eventId = eventId,
                downloadId = downloadId,
                reason = reason,
                engineRetryDueAtEpochMs = retryDueAt,
            ),
        ).toWorkResult()
    }

    companion object {
        private const val PERIODIC_WORK = "xdm-queue-intelligence-periodic"
        private const val IMMEDIATE_WORK = "xdm-queue-intelligence-now"
        private const val CLAIMED_PREFIX = "xdm-transfer-claimed-"
        private const val RETRY_PREFIX = "xdm-transfer-retry-"
        private const val PRECISION_WAKEUP_TAG = "xdm-scheduler-precision-wakeup"
        private const val INPUT_DOWNLOAD_ID = "download_id"
        private const val INPUT_EVENT_ID = "engine_event_id"
        private const val INPUT_WAKE_REASON = "engine_wake_reason"
        private const val INPUT_ENGINE_RETRY_DUE_AT = "engine_retry_due_at_epoch_ms"
        private const val PERIODIC_INTERVAL_MS = 15L * 60L * 1000L

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<QueueIntelligenceWorker>(15, TimeUnit.MINUTES)
                .setInputData(wakeData(reason = AndroidEngineWakeReason.PERIODIC_WORK))
                .addTag(PERIODIC_WORK)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        fun enqueueImmediate(context: Context) {
            (context.applicationContext as? QueueSchedulingRecoveryProvider)?.queueSchedulingRecoveryCoordinator
                ?.requestImmediateReevaluation("go-engine-host-wake", IMMEDIATE_WORK, System.currentTimeMillis())
            val eventId = "condition:${System.currentTimeMillis()}"
            val request = OneTimeWorkRequestBuilder<QueueIntelligenceWorker>()
                .setInputData(wakeData(eventId = eventId, reason = AndroidEngineWakeReason.CONDITION_CHANGED))
                .addTag(IMMEDIATE_WORK)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(IMMEDIATE_WORK, ExistingWorkPolicy.KEEP, request)
        }

        /** Compatibility entry point: the old durable claim token is now identity only, never authorization. */
        fun enqueueClaimed(context: Context, downloadId: String, queueClaimToken: Long) {
            require(queueClaimToken > 0L) { "execution opportunity requires a positive durable generation token" }
            val workName = claimedWorkName(downloadId, queueClaimToken)
            val request = OneTimeWorkRequestBuilder<QueueIntelligenceWorker>()
                .setInputData(
                    wakeData(
                        eventId = "claim:$downloadId:$queueClaimToken",
                        reason = AndroidEngineWakeReason.CLAIMED_EXECUTION_OPPORTUNITY,
                        downloadId = downloadId,
                    ),
                )
                .addTag(workName)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(workName, ExistingWorkPolicy.KEEP, request)
        }

        internal fun claimedWorkName(downloadId: String, queueClaimToken: Long): String =
            "$CLAIMED_PREFIX$downloadId-c$queueClaimToken"

        /** Schedule only the absolute deadline already supplied by Go. */
        fun scheduleRetry(context: Context, downloadId: String, retryAtEpochMs: Long) {
            val delay = (retryAtEpochMs - System.currentTimeMillis()).coerceAtLeast(0L)
            val request = OneTimeWorkRequestBuilder<QueueIntelligenceWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setInputData(
                    wakeData(
                        eventId = "retry:$downloadId:$retryAtEpochMs",
                        reason = AndroidEngineWakeReason.RETRY_DEADLINE,
                        downloadId = downloadId,
                        retryDueAtEpochMs = retryAtEpochMs,
                    ),
                )
                .addTag(RETRY_PREFIX + downloadId)
                .addTag(PRECISION_WAKEUP_TAG)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(RETRY_PREFIX + downloadId, ExistingWorkPolicy.REPLACE, request)
        }

        fun schedulePrecisionWakeup(context: Context, downloadId: String, wakeAtEpochMs: Long) {
            scheduleRetry(context, downloadId, wakeAtEpochMs)
        }

        fun cancelRetry(context: Context, downloadId: String) {
            WorkManager.getInstance(context).cancelUniqueWork(RETRY_PREFIX + downloadId)
        }

        private fun wakeData(
            eventId: String? = null,
            reason: AndroidEngineWakeReason,
            downloadId: String? = null,
            retryDueAtEpochMs: Long? = null,
        ): Data = Data.Builder().apply {
            eventId?.let { putString(INPUT_EVENT_ID, it) }
            putString(INPUT_WAKE_REASON, reason.name)
            downloadId?.let { putString(INPUT_DOWNLOAD_ID, it) }
            retryDueAtEpochMs?.let { putLong(INPUT_ENGINE_RETRY_DUE_AT, it) }
        }.build()
    }
}

private fun AndroidEngineWakeResult.toWorkResult(): androidx.work.ListenableWorker.Result = when (disposition) {
    AndroidEngineWakeDisposition.ACCEPTED,
    AndroidEngineWakeDisposition.DUPLICATE -> androidx.work.ListenableWorker.Result.success()
    AndroidEngineWakeDisposition.RETRYABLE -> androidx.work.ListenableWorker.Result.retry()
    AndroidEngineWakeDisposition.FAILED -> androidx.work.ListenableWorker.Result.failure()
}
