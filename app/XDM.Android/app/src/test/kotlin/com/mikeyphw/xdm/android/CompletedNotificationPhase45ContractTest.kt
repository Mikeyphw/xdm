package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompletedNotificationPhase45ContractTest {
    private val root = androidRoot()
    private val notifications = File(root, "scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferNotifications.kt").readText()
    private val activity = File(root, "scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/OpenDownloadedFileActivity.kt").readText()
    private val terminalPolicy = File(root, "scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TerminalNotificationActionPolicy.kt").readText()
    private val manifest = File(root, "scheduler/src/main/AndroidManifest.xml").readText()

    @Test fun completedTapAndButtonsOpenFileWithDetailsFallback() {
        assertTrue(notifications.contains("if (state == DownloadState.Completed)"))
        assertTrue(notifications.contains("openCompletedPendingIntent(downloadId)"))
        assertTrue(terminalPolicy.contains("NotificationActionModel(QueueControlCommand.OpenOne, \"Open file\""))
        assertTrue(terminalPolicy.contains("NotificationActionModel(QueueControlCommand.ShareOne, \"Share\""))
        assertTrue(terminalPolicy.contains("NotificationActionModel(QueueControlCommand.StartOne, \"Details\""))
        assertTrue(notifications.contains("QueueControlCommand.OpenOne -> addAction(android.R.drawable.ic_menu_view, action.label, openCompletedPendingIntent(downloadId))"))
        assertTrue(notifications.contains("QueueControlCommand.ShareOne -> addAction(android.R.drawable.ic_menu_share, action.label, shareCompletedPendingIntent(downloadId))"))
        assertTrue(notifications.contains("QueueControlCommand.StartOne -> addAction(android.R.drawable.ic_menu_info_details, action.label, openAppPendingIntent(downloadId))"))
        assertTrue(notifications.contains("ACTION_OPEN_DOWNLOAD_DETAILS"))
        assertFalse(notifications.contains("ACTION_VIEW") && notifications.contains("setDataAndType"))
    }

    @Test fun completedOpenHandlerIsPrivateAndRevalidatesBeforeGrantingAccess() {
        assertTrue(manifest.contains("android:name=\".OpenDownloadedFileActivity\""))
        assertTrue(manifest.contains("android:exported=\"false\""))
        assertTrue(activity.contains("download.state != DownloadState.Completed"))
        assertTrue(activity.contains("setDataAndType(uri, download.mimeType?.takeIf { it.isNotBlank() } ?: \"*/*\")"))
        assertTrue(activity.contains("Intent.ACTION_SEND"))
        assertTrue(activity.contains("Intent.EXTRA_STREAM"))
        assertTrue(activity.contains("Intent.FLAG_GRANT_READ_URI_PERMISSION"))
    }
}

private fun androidRoot(): File = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
    .first { File(it, "settings.gradle.kts").isFile }
