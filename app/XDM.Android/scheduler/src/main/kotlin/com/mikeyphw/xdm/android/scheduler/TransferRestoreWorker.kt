package com.mikeyphw.xdm.android.scheduler

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/** Boot/package replacement restores the Go engine first; Go alone decides recovery/runnable work. */
class TransferRestoreWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = AndroidSchedulerHost.wake(
        applicationContext,
        AndroidEngineWakeRequest(
            eventId = "restore:$id",
            reason = AndroidEngineWakeReason.BOOT_OR_PACKAGE_RESTART,
            afterBootOrPackageRestart = true,
        ),
    ).let { result ->
        when (result.disposition) {
            AndroidEngineWakeDisposition.ACCEPTED,
            AndroidEngineWakeDisposition.DUPLICATE -> Result.success()
            AndroidEngineWakeDisposition.RETRYABLE -> Result.retry()
            AndroidEngineWakeDisposition.FAILED -> Result.failure()
        }
    }
}
