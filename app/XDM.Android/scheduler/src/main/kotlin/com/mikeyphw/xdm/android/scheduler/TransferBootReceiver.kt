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
        val request = OneTimeWorkRequestBuilder<TransferRestoreWorker>().addTag(RESTORE_WORK_NAME).build()
        WorkManager.getInstance(context).enqueueUniqueWork(RESTORE_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    companion object { const val RESTORE_WORK_NAME = "xdm-transfer-restore" }
}
