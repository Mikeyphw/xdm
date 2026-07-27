package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompletedNotificationPhase45ContractTest {
    private val root = androidRoot()
    private val notifications = File(root, "scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferNotifications.kt").readText()
    private val activity = File(root, "scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/OpenDownloadedFileActivity.kt").readText()
    private val manifest = File(root, "scheduler/src/main/AndroidManifest.xml").readText()

    @Test fun completedTapOpensFileWhileCompletedActionOpensXdm() {
        assertTrue(notifications.contains("if (state == DownloadState.Completed)"))
        assertTrue(notifications.contains("openCompletedPendingIntent(downloadId)"))
        assertTrue(notifications.contains("DownloadState.Completed -> addAction(android.R.drawable.ic_menu_view, \"Open XDM\", openAppPendingIntent(downloadId))"))
        assertTrue(notifications.contains("ACTION_OPEN_DOWNLOAD_DETAILS"))
        assertFalse(notifications.contains("ACTION_VIEW") && notifications.contains("setDataAndType"))
    }

    @Test fun completedOpenHandlerIsPrivateAndRevalidatesBeforeGrantingAccess() {
        assertTrue(manifest.contains("android:name=\".OpenDownloadedFileActivity\""))
        assertTrue(manifest.contains("android:exported=\"false\""))
        assertTrue(activity.contains("download.state != DownloadState.Completed"))
        assertTrue(activity.contains("setDataAndType(uri, download.mimeType?.takeIf { it.isNotBlank() } ?: \"*/*\")"))
        assertTrue(activity.contains("Intent.FLAG_GRANT_READ_URI_PERMISSION"))
        assertTrue(activity.contains("openXdmDetails(download.id, \"completed-file-missing\")"))
    }
}

private fun androidRoot(): File = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
    .first { File(it, "settings.gradle.kts").isFile }
