package com.mikeyphw.xdm.android.scheduler

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.mikeyphw.xdm.android.model.TerminalNotificationRecord
import com.mikeyphw.xdm.android.model.TerminalNotificationKey
import com.mikeyphw.xdm.android.model.QueueControlCommand
import com.mikeyphw.xdm.android.model.NotificationPermissionState
import com.mikeyphw.xdm.android.model.NotificationActionVisibility
import com.mikeyphw.xdm.android.model.NotificationActionModel
import android.os.Build
import android.content.pm.PackageManager
import android.Manifest
import com.mikeyphw.xdm.android.model.DownloadState
import com.mikeyphw.xdm.android.util.sanitizeNotificationText
import java.util.Locale
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService

class TransferNotifications(private val context: Context) {
    private val manager = requireNotNull(context.getSystemService<NotificationManager>())
    private val systemIds = TransferSystemIdRegistry(context)
    private val phase4Coordinator: QueueSchedulingRecoveryCoordinator =
        (context.applicationContext as? QueueSchedulingRecoveryProvider)?.queueSchedulingRecoveryCoordinator
            ?: QueueSchedulingRecoveryCoordinator(FileBackedQueueSchedulingRecoveryStore(java.io.File(context.filesDir, "queue-scheduling-recovery")))

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
        val permissionWarning = notificationPermissionState().takeIf { it.needsInAppControlWarning }
        val displayText = if (permissionWarning != null) "$text • Notifications blocked: use in-app controls" else text
        val builder = NotificationCompat.Builder(context, CHANNEL_ACTIVE)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(displayText)
            .setSubText(permissionWarning?.let { "Notification permission denied" })
            .setOnlyAlertOnce(true)
            .setOngoing(summary.activeCount > 0)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openAppPendingIntent())
            .addAction(android.R.drawable.ic_media_pause, "Pause all", actionPendingIntent(ACTION_PAUSE_ALL, null, 11))
            .addAction(android.R.drawable.ic_media_play, "Resume all", actionPendingIntent(ACTION_RESUME_ALL, null, 12))
        if (downloadId != null) {
            val paused = summary.primaryState == DownloadState.Paused
            builder.addAction(
                if (paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
                if (paused) "Resume" else "Pause",
                actionPendingIntent(if (paused) ACTION_RESUME else ACTION_PAUSE, downloadId, systemIds.idFor(downloadId)),
            )
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", actionPendingIntent(ACTION_CANCEL, downloadId, systemIds.idFor(downloadId)))
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

    fun terminalIfFirst(
        downloadId: String,
        fileName: String,
        state: DownloadState,
        message: String?,
        destinationUri: String? = null,
        mimeType: String? = null,
        attemptGeneration: Long = 0L,
    ): Notification? {
        val profile = notificationProfile(state, fileName, message)
        val now = System.currentTimeMillis()
        val record = TerminalNotificationRecord(
            key = TerminalNotificationKey(downloadId, attemptGeneration, state),
            title = profile.title,
            text = profile.text,
            actions = terminalActionModels(state, downloadId),
            createdAtEpochMs = now,
            dispatchedAtEpochMs = now,
        )
        return if (phase4Coordinator.recordTerminalNotification(record)) {
            terminal(downloadId, fileName, state, message, destinationUri, mimeType)
        } else {
            null
        }
    }

    fun terminal(
        downloadId: String,
        fileName: String,
        state: DownloadState,
        message: String?,
        destinationUri: String? = null,
        mimeType: String? = null,
    ): Notification {
        ensureChannels()
        val profile = notificationProfile(state, fileName, message)
        val contentIntent = if (state == DownloadState.Completed) {
            openCompletedPendingIntent(downloadId)
        } else {
            openAppPendingIntent(downloadId)
        }
        return NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(profile.icon)
            .setContentTitle(profile.title)
            .setContentText(profile.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(profile.text))
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .apply {
                when (state) {
                    DownloadState.Completed -> addAction(android.R.drawable.ic_menu_view, "Open XDM", openAppPendingIntent(downloadId))
                    DownloadState.Paused -> addAction(android.R.drawable.ic_media_play, "Resume", actionPendingIntent(ACTION_RESUME, downloadId, systemIds.idFor(downloadId)))
                    DownloadState.Failed -> addAction(android.R.drawable.ic_popup_sync, "Retry", actionPendingIntent(ACTION_RETRY, downloadId, systemIds.idFor(downloadId)))
                    DownloadState.RecoveryRequired -> addAction(android.R.drawable.ic_menu_manage, "Review recovery", actionPendingIntent(ACTION_REVIEW_RECOVERY, downloadId, systemIds.idFor(downloadId)))
                    else -> Unit
                }
                addAction(android.R.drawable.ic_menu_close_clear_cancel, "Dismiss", actionPendingIntent(ACTION_DISMISS, downloadId, systemIds.idFor(downloadId)))
            }
            .build()
    }

    fun terminal(downloadId: String, fileName: String, completed: Boolean, message: String?): Notification =
        terminal(downloadId, fileName, if (completed) DownloadState.Completed else DownloadState.Failed, message)

    private fun terminalActionModels(state: DownloadState, downloadId: String): List<NotificationActionModel> = buildList {
        when (state) {
            DownloadState.Completed -> add(NotificationActionModel(QueueControlCommand.StartOne, "Open XDM", NotificationActionVisibility.Show, downloadId))
            DownloadState.Paused -> add(NotificationActionModel(QueueControlCommand.ResumeOne, "Resume", NotificationActionVisibility.Show, downloadId))
            DownloadState.Failed -> add(NotificationActionModel(QueueControlCommand.RetryOne, "Retry", NotificationActionVisibility.Show, downloadId))
            DownloadState.RecoveryRequired -> add(NotificationActionModel(QueueControlCommand.RetryOne, "Review recovery", NotificationActionVisibility.Show, downloadId))
            else -> Unit
        }
        add(NotificationActionModel(QueueControlCommand.DisableQueue, "Dismiss", NotificationActionVisibility.Show, downloadId))
    }

    fun notificationPermissionState(): NotificationPermissionState {
        val android13OrNewer = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        val granted = if (android13OrNewer) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        return NotificationPermissionState(
            android13OrNewer = android13OrNewer,
            drawerPermissionGranted = granted,
        )
    }

    private fun notificationProfile(state: DownloadState, fileName: String, message: String?): NotificationProfile = when (state) {
        DownloadState.Completed -> NotificationProfile(
            icon = android.R.drawable.stat_sys_download_done,
            title = "Download complete",
            text = fileName,
        )
        DownloadState.Paused -> NotificationProfile(
            icon = android.R.drawable.stat_sys_download,
            title = "Download paused",
            text = "Partial download preserved. Tap Resume to continue.",
        )
        DownloadState.Cancelled -> NotificationProfile(
            icon = android.R.drawable.stat_notify_error,
            title = "Download cancelled",
            text = fileName,
        )
        DownloadState.RecoveryRequired -> NotificationProfile(
            icon = android.R.drawable.stat_notify_error,
            title = "Download needs attention",
            text = sanitizeNotificationText(message, "Download needs recovery before it can resume. Open XDM for details."),
        )
        DownloadState.Failed -> NotificationProfile(
            icon = android.R.drawable.stat_notify_error,
            title = "Download failed",
            text = sanitizeNotificationText(message, "Download could not continue. Open XDM for details."),
        )
        else -> NotificationProfile(
            icon = android.R.drawable.stat_sys_download,
            title = "Download paused",
            text = "Partial download preserved. Tap Resume to continue.",
        )
    }

    private data class NotificationProfile(
        val icon: Int,
        val title: String,
        val text: String,
    )

    fun notifyRestored(count: Int) {
        if (count > 0) manager.notify(RESTORE_NOTIFICATION_ID, restored(count))
    }

    private fun openAppPendingIntent(downloadId: String? = null): PendingIntent {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent(Intent.ACTION_MAIN).setPackage(context.packageName)
        if (downloadId != null) {
            intent.action = ACTION_OPEN_DOWNLOAD_DETAILS
            intent.putExtra(EXTRA_DOWNLOAD_ID, downloadId)
        }
        return PendingIntent.getActivity(context, downloadId?.let(systemIds::idFor) ?: 1, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun openCompletedPendingIntent(downloadId: String): PendingIntent {
        val intent = Intent(context, OpenDownloadedFileActivity::class.java)
            .setAction(ACTION_OPEN_COMPLETED_DOWNLOAD)
            .putExtra(EXTRA_DOWNLOAD_ID, downloadId)
        return PendingIntent.getActivity(context, systemIds.idFor(downloadId), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
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
        const val ACTION_REVIEW_RECOVERY = "com.mikeyphw.xdm.android.action.REVIEW_RECOVERY"
        const val ACTION_DISMISS = "com.mikeyphw.xdm.android.action.DISMISS"
        @Deprecated("Phase 4 renamed Mute to Dismiss; keep constant only for old broadcast compatibility.")
        const val ACTION_MUTE = "com.mikeyphw.xdm.android.action.MUTE"
        const val ACTION_OPEN_COMPLETED_DOWNLOAD = "com.mikeyphw.xdm.android.action.OPEN_COMPLETED_DOWNLOAD"
        const val ACTION_OPEN_DOWNLOAD_DETAILS = "com.mikeyphw.xdm.android.action.OPEN_DOWNLOAD_DETAILS"
        const val EXTRA_DOWNLOAD_ID = "download_id"
        const val EXTRA_OPEN_FALLBACK_REASON = "open_fallback_reason"

        private fun formatSpeed(bytes: Long): String = when {
            bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MiB/s", bytes / (1024.0 * 1024.0))
            bytes >= 1024L -> String.format(Locale.US, "%.1f KiB/s", bytes / 1024.0)
            else -> "$bytes B/s"
        }
    }
}
