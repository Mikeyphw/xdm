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
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val runtime = (context.applicationContext as TransferRuntimeProvider).transferRuntime
                val queue = (context.applicationContext as? QueueIntelligenceProvider)?.queueIntelligenceCoordinator
                when (intent.action) {
                    TransferNotifications.ACTION_PAUSE_ALL -> if (queue != null) { queue.pauseAllDurably(); runtime.pauseAll() }
                    TransferNotifications.ACTION_RESUME_ALL -> queue?.resumeAllManual()
                    TransferNotifications.ACTION_PAUSE -> intent.getStringExtra(TransferNotifications.EXTRA_DOWNLOAD_ID)?.let { runtime.pause(it) }
                    TransferNotifications.ACTION_CANCEL -> intent.getStringExtra(TransferNotifications.EXTRA_DOWNLOAD_ID)?.let { runtime.cancel(it) }
                    TransferNotifications.ACTION_RESUME,
                    TransferNotifications.ACTION_RETRY -> intent.getStringExtra(TransferNotifications.EXTRA_DOWNLOAD_ID)?.let { id ->
                        // Queue policy chooses UIDT/FGS/WorkManager legally; the BroadcastReceiver never starts an FGS directly.
                        queue?.requestStart(id, userVisible = true, manual = true)
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }
}
