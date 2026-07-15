package com.mikeyphw.xdm.android.scheduler

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.util.Locale
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService

class TransferNotifications(private val context: Context) {
    private val manager = requireNotNull(context.getSystemService<NotificationManager>())

    fun ensureChannels() {
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ACTIVE,
                "Active downloads",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Progress and controls for active XDM downloads"
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_STATUS,
                "Download status",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "Completed, failed, and recovery notifications" },
        )
    }

    fun active(summary: ActiveTransferSummary, downloadId: String? = summary.primaryDownloadId): Notification {
        ensureChannels()
        val title = when (summary.activeCount) {
            0 -> "XDM is preparing downloads"
            1 -> summary.primaryFileName ?: "Downloading file"
            else -> "${summary.activeCount} active downloads"
        }
        val text = buildString {
            summary.progressPercent?.let { append("$it% • ") }
            append(formatSpeed(summary.speedBytesPerSecond))
            append(" • ").append(summary.bandwidthProfile)
        }
        val builder = NotificationCompat.Builder(context, CHANNEL_ACTIVE)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(summary.activeCount > 0)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openAppPendingIntent())
            .addAction(android.R.drawable.ic_media_pause, "Pause all", actionPendingIntent(ACTION_PAUSE_ALL, null, 11))
            .addAction(android.R.drawable.ic_media_play, "Resume all", actionPendingIntent(ACTION_RESUME_ALL, null, 12))
        if (downloadId != null) {
            val paused = summary.primaryState == com.mikeyphw.xdm.android.model.DownloadState.Paused
            builder.addAction(
                if (paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
                if (paused) "Resume" else "Pause",
                actionPendingIntent(if (paused) ACTION_RESUME else ACTION_PAUSE, downloadId, 13 + downloadId.hashCode()),
            )
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", actionPendingIntent(ACTION_CANCEL, downloadId, 14 + downloadId.hashCode()))
        }
        val total = summary.totalBytes
        if (total != null && total > 0) {
            val progress = summary.bytesReceived.coerceIn(0, total)
            builder.setProgress(100, ((progress * 100L) / total).toInt(), false)
        } else {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    fun restored(count: Int): Notification {
        ensureChannels()
        return NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle("Downloads restored")
            .setContentText("$count interrupted download${if (count == 1) " is" else "s are"} paused and ready to resume.")
            .setAutoCancel(true)
            .setContentIntent(openAppPendingIntent())
            .build()
    }

    fun terminal(downloadId: String, fileName: String, completed: Boolean, message: String?): Notification {
        ensureChannels()
        return NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(if (completed) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
            .setContentTitle(if (completed) "Download complete" else "Download failed")
            .setContentText(message ?: fileName)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message ?: fileName))
            .setAutoCancel(true)
            .setContentIntent(openAppPendingIntent())
            .apply {
                if (!completed) addAction(android.R.drawable.ic_popup_sync, "Retry", actionPendingIntent(ACTION_RETRY, downloadId, 20 + downloadId.hashCode()))
                addAction(android.R.drawable.ic_menu_close_clear_cancel, "Mute", actionPendingIntent(ACTION_MUTE, downloadId, 21 + downloadId.hashCode()))
            }
            .build()
    }

    fun notifyRestored(count: Int) {
        if (count > 0) manager.notify(RESTORE_NOTIFICATION_ID, restored(count))
    }

    private fun openAppPendingIntent(): PendingIntent {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent(Intent.ACTION_MAIN).setPackage(context.packageName)
        return PendingIntent.getActivity(context, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun actionPendingIntent(action: String, downloadId: String?, requestCode: Int): PendingIntent {
        val intent = Intent(context, TransferActionReceiver::class.java).setAction(action)
        if (downloadId != null) intent.putExtra(EXTRA_DOWNLOAD_ID, downloadId)
        return PendingIntent.getBroadcast(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        const val CHANNEL_ACTIVE = "xdm_active_downloads"
        const val CHANNEL_STATUS = "xdm_download_status"
        const val ACTIVE_NOTIFICATION_ID = 4100
        const val RESTORE_NOTIFICATION_ID = 4101
        const val ACTION_PAUSE_ALL = "com.mikeyphw.xdm.android.action.PAUSE_ALL"
        const val ACTION_RESUME_ALL = "com.mikeyphw.xdm.android.action.RESUME_ALL"
        const val ACTION_CANCEL = "com.mikeyphw.xdm.android.action.CANCEL"
        const val ACTION_PAUSE = "com.mikeyphw.xdm.android.action.PAUSE"
        const val ACTION_RESUME = "com.mikeyphw.xdm.android.action.RESUME"
        const val ACTION_RETRY = "com.mikeyphw.xdm.android.action.RETRY"
        const val ACTION_MUTE = "com.mikeyphw.xdm.android.action.MUTE"
        const val EXTRA_DOWNLOAD_ID = "download_id"

        private fun formatSpeed(bytes: Long): String = when {
            bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MiB/s", bytes / (1024.0 * 1024.0))
            bytes >= 1024L -> String.format(Locale.US, "%.1f KiB/s", bytes / 1024.0)
            else -> "$bytes B/s"
        }
    }
}
