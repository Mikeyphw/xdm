package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Rm02PersistenceDownloadsUxContractTest {
    private val root = androidRoot()

    @Test fun mediaCaptureLinksAreTransactionalAndDeletionSafe() {
        val repository = source("persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/DownloadRepository.kt")
        val mediaDao = source("persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/MediaCaptureDao.kt")
        val graphDao = source("persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/DownloadGraphTransactionDao.kt")
        val viewModel = source("app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt")

        assertTrue(repository.contains("createDownloadFromMediaCapture"))
        assertTrue(repository.substringAfter("suspend fun createDownloadFromMediaCapture").substringBefore("suspend fun").contains("database.withTransaction"))
        assertTrue(repository.contains("Cannot link media capture to a missing Download row"))
        assertTrue(repository.contains("repairMediaCaptureDownloadLinks"))
        assertTrue(mediaDao.contains("repairOrphanedDownloadLinks"))
        assertTrue(mediaDao.contains("hideOrphanedAppDownloadOutputs"))
        assertTrue(graphDao.contains("hideAppMediaOutputsForDownload(downloadId, mediaRevision)"))
        assertTrue(graphDao.contains("rebindMediaCapturesBeforeDownloadDeletion(downloadId, mediaRevision)"))
        assertTrue(viewModel.contains("repository.repairMediaCaptureDownloadLinks()"))
    }

    @Test fun downloadsCardUsesHumanIdentityAndOneExplicitQuickAction() {
        val row = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadRow.kt")
        val destination = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadDestinationUi.kt")
        val policy = source("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DownloadPresentationPolicy.kt")

        assertTrue(row.contains("DownloadPresentationPolicy.displayName(download)"))
        assertTrue(row.contains("quickActionsFor(download, actionContext).take(1)"))
        assertTrue(row.contains("TextButton("))
        assertTrue(row.contains("Text(quickAction.label)"))
        assertTrue(row.contains("destinationCardLabel(download)"))
        assertTrue(destination.contains("Saved to \$compactLabel"))
        assertTrue(destination.contains("Destination: \$compactLabel"))
        assertTrue(policy.contains("Content-Disposition").not()) // HTTP header parsing stays at intake; this policy consumes the resolved name.
        assertTrue(policy.contains("download-\$hostBase"))
        assertTrue(policy.contains("isOpaqueIdentifierName"))
        assertFalse(row.contains("122.4 MiBF"))
    }

    @Test fun finalizationFailureIsNotPresentedAsNetworkFailure() {
        val truth = source("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DownloadUiTruth.kt")
        val planner = source("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DownloadActionPlanner.kt")
        val notifications = source("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferNotifications.kt")
        val notificationActions = source("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TerminalNotificationActionPolicy.kt")

        assertTrue(truth.contains("Finalization failed"))
        assertTrue(truth.contains("Transfer complete; final save needs attention"))
        assertTrue(truth.contains("if (DownloadPresentationPolicy.isFinalizationFailure(download)) return null"))
        assertTrue(planner.contains("Retry finalization"))
        assertTrue(notifications.contains("Couldn't finish download"))
        assertTrue(notifications.contains("The file was transferred, but XDM couldn't finish saving it."))
        assertTrue(notificationActions.contains("Retry finalization"))
        assertTrue(notifications.contains("record.actions"))
    }

    @Test fun debugMediaTransactionChecksThePersistedCancelledProbeState() {
        val catalog = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/debug/DebugTestCatalog.kt")
        assertTrue(catalog.contains("state = DownloadState.Cancelled"))
        assertTrue(catalog.contains("check(linkedDownload.state == DownloadState.Cancelled)"))
        assertFalse(catalog.contains("check(linkedDownload.state == DownloadState.Queued)"))
    }

    private fun source(path: String): String = File(root, path).readText()

    private fun androidRoot(): File = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }
}
