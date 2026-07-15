package com.mikeyphw.xdm.android.scheduler

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class TransferRestoreWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val runtime = (applicationContext as TransferRuntimeProvider).transferRuntime
        val restored = runtime.restoreInterruptedTransfers()
        TransferNotifications(applicationContext).notifyRestored(restored)
        return Result.success()
    }
}
