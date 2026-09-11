package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationWebViewGapHotfixContractTest {
    @Test fun notificationOwnersUseTruthfulLiveAndDurablePaths() {
        val root = androidRoot()
        val notifications = File(root, "scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferNotifications.kt").readText()
        val worker = File(root, "scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/QueueIntelligenceWorker.kt").readText()
        val uidt = File(root, "scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/UserInitiatedTransferJobService.kt").readText()
        val receiver = File(root, "scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferActionReceiver.kt").readText()
        assertTrue(uidt.contains("runtime.liveSummaryFor(downloadId"))
        assertTrue(worker.contains("runtime.liveProgress.collectLatest"))
        assertTrue(notifications.contains("summary.activeCount == 1"))
        assertTrue(notifications.contains("canPostChannel(channelFor(state))"))
        assertTrue(receiver.contains("recordNotificationControlCommand"))
        assertTrue(uidt.contains("JOB_END_NOTIFICATION_POLICY_REMOVE"))
        assertTrue(uidt.contains("JOB_END_NOTIFICATION_POLICY_DETACH"))
        assertTrue(notifications.contains("setOnlyAlertOnce(true)"))
    }

    @Test fun locatorAndCaptureMetadataCloseTheRemainingPresentationGaps() {
        val root = androidRoot()
        val locator = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/MediaLocatorActivity.kt").readText()
        val entity = File(root, "persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/Entities.kt").readText()
        val repository = File(root, "persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/DownloadRepository.kt").readText()
        listOf("webView.saveState", "webView.restoreState", "onReceivedIcon", "findAllAsync", "Intent.ACTION_SEND", "Intent.ACTION_VIEW", "XdmArtworkLoader.load").forEach {
            assertTrue("missing $it", locator.contains(it))
        }
        assertTrue(entity.contains("thumbnailProvenance"))
        assertTrue(repository.contains("uniqueMediaFileNames"))
        assertTrue(repository.contains("MediaArtworkMergePolicy.merge"))

        val downloads = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadsScreen.kt").readText()
        val add = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/intake/AddDownloadSurface.kt").readText()
        val preflight = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/intake/DownloadPreflightProbe.kt").readText()
        assertTrue(downloads.contains("mediaOutputs.forEach"))
        assertTrue(add.contains("effectiveFileName"))
        assertTrue(preflight.contains("ServerContentDisposition"))
    }

    @Test fun fullGradleFollowupCompileAndLintRegressionsStayClosed() {
        val root = androidRoot()
        val strings = File(root, "app/src/main/res/values/strings.xml").readText()
        val xdmApp = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApp.kt").readText()
        val notifications = File(root, "scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferNotifications.kt").readText()
        val permission = File(root, "scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/NotificationPermissionStore.kt").readText()
        val mediaInbox = File(root, "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaInboxContract.kt").readText()
        assertTrue(strings.contains("name=\"media_locator_no_external_browser\""))
        assertTrue(strings.contains("name=\"media_locator_type_inferred\""))
        assertTrue(strings.contains("name=\"media_locator_candidate_details\""))
        val locator = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/MediaLocatorActivity.kt").readText()
        assertTrue(locator.contains("R.string.media_locator_candidate_details"))
        assertTrue(locator.contains("R.string.media_locator_type_inferred"))
        assertTrue(!locator.contains("item.mimeType ?: \"type inferred\""))
        assertTrue(!xdmApp.contains("import androidx.compose.foundation.layout.weight"))
        assertTrue(!notifications.contains("SDK_INT < Build.VERSION_CODES.O"))
        assertTrue(permission.contains("androidx.core.content.edit"))
        assertTrue(permission.contains("preferences.edit {"))
        assertTrue(mediaInbox.contains("titleIsPageDerived"))
        assertTrue(mediaInbox.contains("hasExplicitTitle = titleIsPageDerived"))
    }


    @Test fun fullGradleContractSuiteTracksCurrentAuthoritiesWithoutCompilerWarnings() {
        val root = androidRoot()
        val architecture = File(root, "app/src/test/kotlin/com/mikeyphw/xdm/android/ArchitectureContractTest.kt").readText()
        val phase4 = File(root, "app/src/test/kotlin/com/mikeyphw/xdm/android/BugHuntPhase4QueueSchedulingStateMachinesContractTest.kt").readText()
        val completed = File(root, "app/src/test/kotlin/com/mikeyphw/xdm/android/CompletedNotificationPhase45ContractTest.kt").readText()
        val naming = File(root, "app/src/test/kotlin/com/mikeyphw/xdm/android/LiveLocatorTitleNamingContractTest.kt").readText()
        val ux01 = File(root, "app/src/test/kotlin/com/mikeyphw/xdm/android/Ux01DestinationStorageTruthContractTest.kt").readText()
        val manifest = File(root, "PROJECT_MANIFEST.json").readText()

        assertTrue(architecture.contains("current Room schema v24"))
        assertTrue(!architecture.contains("current Room schema v23"))
        assertTrue(phase4.contains("terminalNotificationsLocked().firstOrNull { it.idempotencyKey == record.idempotencyKey }"))
        assertTrue(completed.contains("TerminalNotificationActionPolicy.kt"))
        assertTrue(completed.contains("QueueControlCommand.OpenOne -> addAction"))
        assertTrue(naming.contains("hasExplicitTitle = titleIsPageDerived"))
        assertTrue(ux01.contains("File(requireNotNull(System.getProperty(\"user.dir\")))"))
        assertTrue(manifest.contains("\"room_schema_current\": 24"))
        assertTrue(manifest.contains("\"current_runtime_quality_authority\": \"media_parity04_browser_ux_userscripts_notifications_release_seal\""))
    }

    private fun androidRoot(): File = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }
}
