package com.mikeyphw.xdm.android.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class TransferActionReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == TransferNotifications.ACTION_REVIEW_RECOVERY) {
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?: Intent(Intent.ACTION_MAIN).setPackage(context.packageName)
            launch.action = TransferNotifications.ACTION_REVIEW_RECOVERY
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.getStringExtra(TransferNotifications.EXTRA_DOWNLOAD_ID)?.let { launch.putExtra(TransferNotifications.EXTRA_DOWNLOAD_ID, it) }
            context.startActivity(launch)
            return
        }
        if (intent.action == TransferNotifications.ACTION_DISMISS || intent.action == TransferNotifications.ACTION_MUTE) {
            intent.getStringExtra(TransferNotifications.EXTRA_DOWNLOAD_ID)?.let { id ->
                context.getSystemService(android.app.NotificationManager::class.java).cancel(TransferSystemIdRegistry(context).idFor(id))
            }
            return
        }
        val action = when (intent.action) {
            TransferNotifications.ACTION_PAUSE_ALL -> "pause_all"
            TransferNotifications.ACTION_RESUME_ALL -> "resume_all"
            TransferNotifications.ACTION_PAUSE -> "pause"
            TransferNotifications.ACTION_CANCEL -> "cancel"
            TransferNotifications.ACTION_RESUME -> "resume"
            TransferNotifications.ACTION_RETRY -> "retry"
            else -> null
        } ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                AndroidGoDownloadCommands.submit(
                    context = context,
                    action = action,
                    downloadId = intent.getStringExtra(TransferNotifications.EXTRA_DOWNLOAD_ID),
                )
            } finally {
                pending.finish()
            }
        }
    }
}
