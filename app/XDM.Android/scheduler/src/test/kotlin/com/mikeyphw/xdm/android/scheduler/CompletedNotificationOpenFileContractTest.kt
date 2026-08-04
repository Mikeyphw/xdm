package com.mikeyphw.xdm.android.scheduler

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompletedNotificationOpenFileContractTest {
    private val root = androidRoot()
    private val notifications = File(root, "scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferNotifications.kt").readText()
    private val activity = File(root, "scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/OpenDownloadedFileActivity.kt").readText()
    private val grantPolicy = File(root, "scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/CompletedFileGrantPolicy.kt").readText()
    private val manifest = File(root, "scheduler/src/main/AndroidManifest.xml").readText()
    private val runtime = File(root, "scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferExecutionRuntime.kt").readText()

    @Test fun completedNotificationTapUsesTrampolineNotRawFileUri() {
        assertTrue(notifications.contains("ACTION_OPEN_COMPLETED_DOWNLOAD"))
        assertTrue(notifications.contains("OpenDownloadedFileActivity::class.java"))
        assertTrue(notifications.contains("putExtra(EXTRA_DOWNLOAD_ID, downloadId)"))
        assertFalse(notifications.contains("setDataAndType(destinationUri"))
        assertFalse(notifications.contains("Uri.parse(destinationUri"))
    }

    @Test fun trampolineIsNonExportedAndUsesTemporaryReadGrant() {
        assertTrue(manifest.contains("android:name=\".OpenDownloadedFileActivity\""))
        assertTrue(manifest.contains("android:exported=\"false\""))
        assertTrue(manifest.contains("androidx.core.content.FileProvider"))
        assertTrue(manifest.contains("\${applicationId}.completed-downloads"))
        assertTrue(activity.contains("Intent(Intent.ACTION_VIEW)"))
        assertTrue(activity.contains("Intent.FLAG_GRANT_READ_URI_PERMISSION"))
        assertTrue(activity.contains("CompletedFileGrantPolicy.resolve"))
        assertTrue(grantPolicy.contains("FileProvider.getUriForFile"))
        assertTrue(grantPolicy.contains("context.filesDir, \"downloads\""))
        assertTrue(grantPolicy.contains("getExternalFilesDir(null)"))
        assertTrue(grantPolicy.contains("if (file.name != download.fileName) return null"))
    }

    @Test fun trampolineRevalidatesCompletedStateAndFallsBackToXdm() {
        assertTrue(activity.contains("findDownload(downloadId)"))
        assertTrue(activity.contains("download.state != DownloadState.Completed"))
        assertTrue(activity.contains("openXdmDetails"))
        assertTrue(activity.contains("completed-file-missing"))
        assertTrue(activity.contains("no-viewer"))
        assertTrue(activity.contains("uri-permission-lost"))
    }

    @Test fun runtimePersistsConcreteCompletedUriForLaterNotificationTap() {
        assertTrue(runtime.contains("destinationUri = if (verifiedSnapshot.state == DownloadState.Completed"))
        assertTrue(runtime.contains("verifiedSnapshot.completedUri"))
        assertTrue(runtime.contains("TransferTerminalEvent(download.id, download.fileName, finalState, finalMessage, storedDestination, storedMimeType)"))
    }
}

private fun androidRoot(): File = generateSequence(File(System.getProperty("user.dir") ?: ".")) { it.parentFile }
    .first { File(it, "settings.gradle.kts").isFile }
