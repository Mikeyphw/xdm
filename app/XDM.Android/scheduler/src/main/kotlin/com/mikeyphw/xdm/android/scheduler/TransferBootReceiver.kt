package com.mikeyphw.xdm.android.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

class TransferBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        val restore = OneTimeWorkRequestBuilder<TransferRestoreWorker>().addTag(RESTORE_WORK_NAME).build()
        val evaluate = OneTimeWorkRequestBuilder<QueueIntelligenceWorker>().addTag(RESTORE_QUEUE_WORK_NAME).build()
        WorkManager.getInstance(context)
            .beginUniqueWork(RESTORE_WORK_NAME, ExistingWorkPolicy.REPLACE, restore)
            .then(evaluate)
            .enqueue()
        QueueIntelligenceWorker.schedule(context)
    }

    companion object {
        const val RESTORE_WORK_NAME = "xdm-transfer-restore"
        const val RESTORE_QUEUE_WORK_NAME = "xdm-queue-intelligence-after-restore"
    }
}
