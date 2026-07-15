package com.mikeyphw.xdm.android.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class TransferActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in setOf(TransferNotifications.ACTION_RESUME_ALL, TransferNotifications.ACTION_RESUME, TransferNotifications.ACTION_RETRY)) {
            val serviceIntent = Intent(context, TransferForegroundService::class.java).setAction(intent.action)
            intent.getStringExtra(TransferNotifications.EXTRA_DOWNLOAD_ID)?.let { serviceIntent.putExtra(TransferNotifications.EXTRA_DOWNLOAD_ID, it) }
            ContextCompat.startForegroundService(context, serviceIntent)
            return
        }
        if (intent.action == TransferNotifications.ACTION_MUTE) {
            intent.getStringExtra(TransferNotifications.EXTRA_DOWNLOAD_ID)?.let { id ->
                context.getSystemService(android.app.NotificationManager::class.java).cancel(5000 + id.stableSystemId())
            }
            return
        }
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val runtime = (context.applicationContext as TransferRuntimeProvider).transferRuntime
                when (intent.action) {
                    TransferNotifications.ACTION_PAUSE_ALL -> runtime.pauseAll()
                    TransferNotifications.ACTION_PAUSE -> intent.getStringExtra(TransferNotifications.EXTRA_DOWNLOAD_ID)?.let { runtime.pause(it) }
                    TransferNotifications.ACTION_CANCEL -> intent.getStringExtra(TransferNotifications.EXTRA_DOWNLOAD_ID)?.let { runtime.cancel(it) }
                }
            } finally {
                pending.finish()
            }
        }
    }
}
