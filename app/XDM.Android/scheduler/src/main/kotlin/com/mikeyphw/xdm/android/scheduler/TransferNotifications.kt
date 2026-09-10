package com.mikeyphw.xdm.android.scheduler

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.mikeyphw.xdm.android.model.DownloadState
import com.mikeyphw.xdm.android.model.NotificationPermissionState
import com.mikeyphw.xdm.android.model.QueueControlCommand
import com.mikeyphw.xdm.android.model.QueueStateMachinePlanner
import com.mikeyphw.xdm.android.model.TerminalNotificationKey
import com.mikeyphw.xdm.android.model.TerminalNotificationRecord
import com.mikeyphw.xdm.android.util.sanitizeNotificationText
import java.util.Locale

class TransferNotifications(private val context: Context) {
    private val manager = requireNotNull(context.getSystemService<NotificationManager>())
    private val systemIds = TransferSystemIdRegistry(context)
    private val permissionStore = NotificationPermissionStore(context)
    private val phase4Coordinator: QueueSchedulingRecoveryCoordinator =
        (context.applicationContext as? QueueSchedulingRecoveryProvider)?.queueSchedulingRecoveryCoordinator
            ?: QueueSchedulingRecoveryCoordinator(FileBackedQueueSchedulingRecoveryStore(java.io.File(context.filesDir, "queue-scheduling-recovery")))

    fun ensureChannels() {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ACTIVE, "Active downloads", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Live progress and truthful controls for active XDM downloads"
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, "Completed downloads", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Completed download results"
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ATTENTION, "Download problems", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Failed downloads and recovery-required results that need attention"
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ROUTINE, "Download state changes", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Paused, cancelled, restored, and waiting download state changes"
                setShowBadge(false)
            },
        )
    }

    /**
     * Active notifications are either a truthful single-transfer card or an aggregate card.
     * Aggregate cards never expose a per-file action for a file the title does not identify.
     */
    fun active(summary: ActiveTransferSummary, downloadId: String? = summary.primaryDownloadId): Notification {
        ensureChannels()
        val isSingle = summary.activeCount == 1 && !downloadId.isNullOrBlank()
        val identifiedDownloadId = downloadId.takeIf { isSingle }
        val title = when (summary.activeCount) {
            0 -> "XDM is preparing downloads"
            1 -> summary.primaryFileName ?: "Downloading file"
            else -> "${summary.activeCount} active downloads"
        }
        val text = buildList {
            summary.progressPercent?.let { add("$it%") }
            add(formatSpeed(summary.speedBytesPerSecond))
            summary.bandwidthProfile.trim().takeIf(String::isNotBlank)?.let(::add)
        }.joinToString(" • ")

        val builder = NotificationCompat.Builder(context, CHANNEL_ACTIVE)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(summary.activeCount > 0)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openAppPendingIntent(identifiedDownloadId))

        if (isSingle) {
            when (summary.primaryState) {
                DownloadState.Paused -> builder.addAction(
                    android.R.drawable.ic_media_play,
                    "Resume",
                    actionPendingIntent(ACTION_RESUME, identifiedDownloadId, systemIds.idFor(requireNotNull(identifiedDownloadId))),
                )
                DownloadState.Connecting,
                DownloadState.Downloading,
                DownloadState.Finalizing,
                DownloadState.Verifying,
                DownloadState.Repairing -> {
                    builder.addAction(
                        android.R.drawable.ic_media_pause,
                        "Pause",
                        actionPendingIntent(ACTION_PAUSE, identifiedDownloadId, systemIds.idFor(requireNotNull(identifiedDownloadId))),
                    )
                    builder.addAction(
                        android.R.drawable.ic_menu_close_clear_cancel,
                        "Cancel",
                        actionPendingIntent(ACTION_CANCEL, identifiedDownloadId, systemIds.idFor(requireNotNull(identifiedDownloadId)) + 1),
                    )
                }
                else -> Unit
            }
        } else if (summary.activeCount > 1) {
            val aggregateStates = summary.aggregateStates
            when {
                aggregateStates.any { it in QueueStateMachinePlanner.activePauseStates } -> builder.addAction(
                    android.R.drawable.ic_media_pause,
                    "Pause all",
                    actionPendingIntent(ACTION_PAUSE_ALL, null, 11),
                )
                DownloadState.Paused in aggregateStates -> builder.addAction(
                    android.R.drawable.ic_media_play,
                    "Resume all",
                    actionPendingIntent(ACTION_RESUME_ALL, null, 12),
                )
            }
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
        return NotificationCompat.Builder(context, CHANNEL_ROUTINE)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle("Downloads restored")
            .setContentText("$count interrupted download${if (count == 1) " is" else "s are"} paused and ready to resume.")
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setGroup(GROUP_TERMINAL)
            .setContentIntent(openAppPendingIntent())
            .build()
    }

    /**
     * Reserves one attempt-generation terminal notification as Pending. The caller must invoke
     * [markTerminalDispatched] only after NotificationManager.notify()/JobService.setNotification()
     * returns. A process death in between is reconciled on startup using the same system ID.
     */
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
        val record = TerminalNotificationRecord(
            key = TerminalNotificationKey(downloadId, attemptGeneration, state),
            title = profile.title,
            text = profile.text,
            actions = TerminalNotificationActionPolicy.actionsFor(state, downloadId),
            createdAtEpochMs = System.currentTimeMillis(),
            dispatchedAtEpochMs = null,
        )
        if (!phase4Coordinator.recordTerminalNotification(record)) return null
        // Reservation is durable even when delivery is currently blocked. Keep it Pending so a
        // later channel/app re-enable can reconcile it instead of falsely marking it delivered.
        if (!canPostChannel(channelFor(state))) return null
        return terminal(downloadId, fileName, state, message, destinationUri, mimeType)
    }

    fun markTerminalDispatched(downloadId: String, attemptGeneration: Long, state: DownloadState) {
        phase4Coordinator.markTerminalNotificationDispatched(
            TerminalNotificationRecord(
                key = TerminalNotificationKey(downloadId, attemptGeneration, state),
                title = "",
                text = "",
                actions = emptyList(),
                createdAtEpochMs = 0L,
            ).idempotencyKey,
        )
    }

    /** Re-posts Pending terminal rows with their stable per-download notification ID. */
    fun reconcilePendingTerminalNotifications(): Int {
        var posted = 0
        phase4Coordinator.pendingTerminalNotifications().forEach { record ->
            if (!canPostChannel(channelFor(record.key.state))) return@forEach
            runCatching {
                manager.notify(systemIds.idFor(record.key.downloadId), terminalFromRecord(record))
                phase4Coordinator.markTerminalNotificationDispatched(record.idempotencyKey)
                posted++
            }
        }
        return posted
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
        return buildTerminalNotification(downloadId, state, profile.title, profile.text)
    }

    fun terminal(downloadId: String, fileName: String, completed: Boolean, message: String?): Notification =
        terminal(downloadId, fileName, if (completed) DownloadState.Completed else DownloadState.Failed, message)

    private fun terminalFromRecord(record: TerminalNotificationRecord): Notification =
        buildTerminalNotification(record.key.downloadId, record.key.state, record.title, record.text)

    private fun buildTerminalNotification(downloadId: String, state: DownloadState, title: String, text: String): Notification {
        ensureChannels()
        val contentIntent = if (state == DownloadState.Completed) openCompletedPendingIntent(downloadId) else openAppPendingIntent(downloadId)
        return NotificationCompat.Builder(context, channelFor(state))
            .setSmallIcon(notificationProfile(state, "", null).icon)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setGroup(GROUP_TERMINAL)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_ALL)
            .setContentIntent(contentIntent)
            .apply {
                TerminalNotificationActionPolicy.actionsFor(state, downloadId).forEach { action ->
                    when (action.command) {
                        QueueControlCommand.OpenOne -> addAction(android.R.drawable.ic_menu_view, action.label, openAppPendingIntent(downloadId))
                        QueueControlCommand.ResumeOne -> addAction(android.R.drawable.ic_media_play, action.label, actionPendingIntent(ACTION_RESUME, downloadId, systemIds.idFor(downloadId)))
                        QueueControlCommand.RetryOne -> addAction(android.R.drawable.ic_popup_sync, action.label, actionPendingIntent(ACTION_RETRY, downloadId, systemIds.idFor(downloadId)))
                        QueueControlCommand.ReviewRecovery -> addAction(android.R.drawable.ic_menu_manage, action.label, actionPendingIntent(ACTION_REVIEW_RECOVERY, downloadId, systemIds.idFor(downloadId)))
                        QueueControlCommand.DismissNotification -> addAction(android.R.drawable.ic_menu_close_clear_cancel, action.label, actionPendingIntent(ACTION_DISMISS, downloadId, systemIds.idFor(downloadId) + 2))
                        else -> Unit
                    }
                }
            }
            .build()
    }

    fun notificationPermissionState(): NotificationPermissionState {
        ensureChannels()
        val android13OrNewer = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        val runtimeGranted = if (android13OrNewer) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true
        val appEnabled = manager.areNotificationsEnabled()
        val requested = permissionStore.promptRequested
        val lastGranted = permissionStore.lastPromptGranted
        return NotificationPermissionState(
            android13OrNewer = android13OrNewer,
            drawerPermissionGranted = runtimeGranted,
            appNotificationsEnabled = appEnabled,
            activeChannelEnabled = channelEnabled(CHANNEL_ACTIVE),
            statusChannelEnabled = channelEnabled(CHANNEL_STATUS),
            attentionChannelEnabled = channelEnabled(CHANNEL_ATTENTION),
            problemsChannelEnabled = channelEnabled(PROBLEMS_CHANNEL_ID),
            promptDismissed = requested && lastGranted == false && !runtimeGranted,
            upgradePreGranted = android13OrNewer && runtimeGranted && !requested,
            previouslyDeniedUpgrade = permissionStore.deniedOnce && !runtimeGranted,
        )
    }

    private fun canPostChannel(channelId: String): Boolean {
        val state = notificationPermissionState()
        if (state.drawerPermissionGranted == false || !state.appNotificationsEnabled) return false
        return when (channelId) {
            CHANNEL_ACTIVE -> state.activeChannelEnabled
            CHANNEL_STATUS -> state.statusChannelEnabled
            CHANNEL_ATTENTION -> state.attentionChannelEnabled
            CHANNEL_ROUTINE -> channelEnabled(CHANNEL_ROUTINE)
            PROBLEMS_CHANNEL_ID -> state.problemsChannelEnabled
            else -> channelEnabled(channelId)
        }
    }

    private fun channelEnabled(channelId: String): Boolean =
        manager.getNotificationChannel(channelId)?.importance != NotificationManager.IMPORTANCE_NONE

    private fun channelFor(state: DownloadState): String = when (state) {
        DownloadState.Completed -> CHANNEL_STATUS
        DownloadState.Failed, DownloadState.RecoveryRequired -> CHANNEL_ATTENTION
        else -> CHANNEL_ROUTINE
    }

    private fun notificationProfile(state: DownloadState, fileName: String, message: String?): NotificationProfile = when (state) {
        DownloadState.Completed -> NotificationProfile(android.R.drawable.stat_sys_download_done, "Download complete", fileName.ifBlank { "Completed download" })
        DownloadState.Paused -> NotificationProfile(android.R.drawable.stat_sys_download, "Download paused", "Partial download preserved. Tap Resume to continue.")
        DownloadState.Cancelled -> NotificationProfile(android.R.drawable.stat_notify_error, "Download cancelled", fileName.ifBlank { "The download was cancelled." })
        DownloadState.WaitingForNetwork -> NotificationProfile(android.R.drawable.stat_notify_sync_noanim, "Waiting for network", "The download will continue when its network requirement is available.")
        DownloadState.WaitingForPower -> NotificationProfile(android.R.drawable.stat_notify_sync_noanim, "Waiting for power", "The download will continue when its power requirement is satisfied.")
        DownloadState.Queued, DownloadState.Created -> NotificationProfile(android.R.drawable.stat_notify_sync_noanim, "Download queued", fileName.ifBlank { "Waiting for an execution slot." })
        DownloadState.RecoveryRequired -> NotificationProfile(
            android.R.drawable.stat_notify_error,
            "Download needs action",
            sanitizeNotificationText(message, "Download needs recovery before it can resume. Open XDM for details."),
        )
        DownloadState.Failed -> NotificationProfile(
            android.R.drawable.stat_notify_error,
            "Download failed",
            sanitizeNotificationText(message, "Download could not continue. Open XDM for details."),
        )
        DownloadState.Connecting -> NotificationProfile(android.R.drawable.stat_sys_download, "Connecting", fileName.ifBlank { "Connecting to the download source." })
        DownloadState.Downloading -> NotificationProfile(android.R.drawable.stat_sys_download, "Downloading", fileName.ifBlank { "Download in progress." })
        DownloadState.Verifying -> NotificationProfile(android.R.drawable.stat_notify_sync_noanim, "Verifying download", fileName.ifBlank { "Checking downloaded data." })
        DownloadState.Repairing -> NotificationProfile(android.R.drawable.stat_notify_sync_noanim, "Repairing download", fileName.ifBlank { "Repairing downloaded data." })
        DownloadState.Finalizing -> NotificationProfile(android.R.drawable.stat_notify_sync_noanim, "Finishing download", fileName.ifBlank { "Finalizing the downloaded file." })
    }

    private data class NotificationProfile(val icon: Int, val title: String, val text: String)

    fun notifyRestored(count: Int) {
        if (count > 0 && canPostChannel(CHANNEL_ROUTINE)) {
            runCatching { manager.notify(RESTORE_NOTIFICATION_ID, restored(count)) }
        }
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
        const val CHANNEL_ATTENTION = "xdm_download_attention"
        const val CHANNEL_ROUTINE = "xdm_download_routine"
        private const val PROBLEMS_CHANNEL_ID = "xdm_runtime_problems"
        const val GROUP_TERMINAL = "xdm_terminal_downloads"
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
