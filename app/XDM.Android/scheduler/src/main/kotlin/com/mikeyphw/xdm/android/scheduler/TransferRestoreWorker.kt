package com.mikeyphw.xdm.android.scheduler

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/** Boot/package-replacement recovery uses the same ownership-first runtime pipeline as app startup. */
class TransferRestoreWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val runtime = (applicationContext as? TransferRuntimeProvider)?.transferRuntime ?: return Result.retry()
        val queue = (applicationContext as? QueueIntelligenceProvider)?.queueIntelligenceCoordinator ?: return Result.retry()
        // Boot/package restore may run long after normal app startup. Take the same durable gate
        // explicitly so no queue start races ownership reconciliation.
        queue.installStartupRecoveryHold()
        val recovery = runtime.recoverForStartup()
        TransferNotifications(applicationContext).notifyRestored(recovery.restoredCount)
        if (recovery.admissionSafe) {
            queue.clearStartupRecoveryHold()
            QueueIntelligenceWorker.enqueueImmediate(applicationContext)
            return Result.success()
        }
        return Result.retry()
    }
}
