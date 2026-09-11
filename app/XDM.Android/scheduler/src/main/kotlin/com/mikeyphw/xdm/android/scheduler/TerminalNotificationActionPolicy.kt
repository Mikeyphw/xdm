package com.mikeyphw.xdm.android.scheduler

import com.mikeyphw.xdm.android.model.DownloadState
import com.mikeyphw.xdm.android.model.NotificationActionModel
import com.mikeyphw.xdm.android.model.NotificationActionVisibility
import com.mikeyphw.xdm.android.model.QueueControlCommand

/** One authority for terminal notification buttons and their durable/debug representation. */
internal object TerminalNotificationActionPolicy {
    fun actionsFor(state: DownloadState, downloadId: String): List<NotificationActionModel> = buildList {
        when (state) {
            DownloadState.Completed -> {
                add(NotificationActionModel(QueueControlCommand.OpenOne, "Open file", NotificationActionVisibility.Show, downloadId))
                add(NotificationActionModel(QueueControlCommand.ShareOne, "Share", NotificationActionVisibility.Show, downloadId))
                add(NotificationActionModel(QueueControlCommand.StartOne, "Details", NotificationActionVisibility.Show, downloadId))
            }
            DownloadState.Paused -> add(NotificationActionModel(QueueControlCommand.ResumeOne, "Resume", NotificationActionVisibility.Show, downloadId))
            DownloadState.Failed -> add(NotificationActionModel(QueueControlCommand.RetryOne, "Retry", NotificationActionVisibility.Show, downloadId))
            DownloadState.RecoveryRequired -> add(NotificationActionModel(QueueControlCommand.ReviewRecovery, "Review recovery", NotificationActionVisibility.Show, downloadId))
            else -> Unit
        }
        add(NotificationActionModel(QueueControlCommand.DismissNotification, "Dismiss", NotificationActionVisibility.Show, downloadId))
    }
}
