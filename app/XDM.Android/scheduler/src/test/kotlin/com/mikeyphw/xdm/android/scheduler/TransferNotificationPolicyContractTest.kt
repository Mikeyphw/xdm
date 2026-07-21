package com.mikeyphw.xdm.android.scheduler

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferNotificationPolicyContractTest {
    @Test fun userInitiatedJobsReportPausedAsPausedInsteadOfFailed() {
        val service = File(androidRoot(), "scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/UserInitiatedTransferJobService.kt").readText()
        assertTrue(service.contains("notifications.terminal(downloadId, result?.fileName ?: \"Download\", state, result?.errorMessage)"))
        assertFalse(service.contains("val completed = state == DownloadState.Completed"))
        assertTrue(service.contains("state in setOf(DownloadState.WaitingForNetwork, DownloadState.WaitingForPower)"))
    }

    @Test fun terminalNotificationsHavePausedAndSanitizedFailureCopy() {
        val notifications = File(androidRoot(), "scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferNotifications.kt").readText()
        assertTrue(notifications.contains("DownloadState.Paused -> NotificationProfile"))
        assertTrue(notifications.contains("Download paused"))
        assertTrue(notifications.contains("Partial download preserved. Tap Resume to continue."))
        assertTrue(notifications.contains("sanitizeNotificationText(message"))
        assertFalse(notifications.contains("message ?: fileName"))
    }

    private fun androidRoot(): File = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }
}
