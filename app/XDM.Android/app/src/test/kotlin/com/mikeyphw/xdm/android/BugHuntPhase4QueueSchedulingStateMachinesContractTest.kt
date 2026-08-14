package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BugHuntPhase4QueueSchedulingStateMachinesContractTest {
    private val root = androidRoot()

    @Test fun queueStateMachineCoversAtomicSlotsPauseAllSchedulesRetriesAndDeletion() {
        val model = source("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/QueueStateMachineModels.kt")
        listOf(
            "QueueConditionScope",
            "QueueDrainPolicy",
            "reserveSlotAtomically",
            "planPauseAll",
            "blocksNewStarts = true",
            "DownloadState.Finalizing",
            "DownloadState.Verifying",
            "DownloadState.Repairing",
            "validateScheduleWindow",
            "incomplete windows fail closed",
            "failureGeneration",
            "deleteQueue",
            "RejectDanglingReferences",
        ).forEach { expected -> assertTrue("Missing Phase 4 queue contract: $expected", model.contains(expected)) }
    }

    @Test fun schedulerRecordsSystemStopReasonsAndDurableReevaluation() {
        val coordinator = source("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/QueueSchedulingRecoveryCoordinator.kt")
        val worker = source("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/QueueIntelligenceWorker.kt")
        val job = source("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/UserInitiatedTransferJobService.kt")
        assertTrue(coordinator.contains("SystemStopReasonRecord"))
        assertTrue(coordinator.contains("workInfoStopReason"))
        assertTrue(coordinator.contains("jobParametersStopReason"))
        assertTrue(coordinator.contains("pendingJobReasons"))
        assertTrue(coordinator.contains("pendingJobReasons") || coordinator.contains("recordSystemStop"))
        assertTrue(coordinator.contains("saveImmediateReevaluation"))
        assertTrue(coordinator.contains("coalesceKey"))
        assertTrue(worker.contains("getStopReason()"))
        assertTrue(job.contains("params.stopReason"))
    }

    @Test fun recoveryAndNotificationStateMachinesAreTypedAndIdempotent() {
        val model = source("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/QueueStateMachineModels.kt")
        val coordinator = source("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/QueueSchedulingRecoveryCoordinator.kt")
        val notifications = source("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferNotifications.kt")
        val receiver = source("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferActionReceiver.kt")
        listOf(
            "RecoveryOperationOutcome",
            "NeedsUserConsent",
            "NeedsFileSelection",
            "RecoveryArtifactIdentity",
            "TerminalNotificationKey",
            "idempotencyKey",
            "NotificationPermissionState",
            "needsInAppControlWarning",
        ).forEach { expected -> assertTrue("Missing Phase 4 recovery/notification contract: $expected", model.contains(expected)) }
        assertTrue(coordinator.contains("putIfAbsent(record.idempotencyKey"))
        assertTrue(notifications.contains("Review recovery"))
        assertTrue(notifications.contains("Dismiss"))
        assertTrue(receiver.contains("ACTION_REVIEW_RECOVERY"))
        assertTrue(receiver.contains("ACTION_DISMISS"))
        assertFalse("RecoveryRequired notifications must not blindly retry.", notifications.contains("DownloadState.RecoveryRequired -> addAction(android.R.drawable.ic_popup_sync, \"Retry\""))
    }

    @Test fun phase4R2WiresContractsIntoProductionPaths() {
        val app = source("app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApplication.kt")
        val coordinator = source("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/QueueIntelligenceCoordinator.kt")
        val phase4 = source("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/QueueSchedulingRecoveryCoordinator.kt")
        val service = source("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferForegroundService.kt")
        val worker = source("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/QueueIntelligenceWorker.kt")
        val viewModel = source("app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt")
        val notifications = source("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferNotifications.kt")
        assertTrue("App must expose the durable Phase 4 provider", app.contains("QueueSchedulingRecoveryProvider"))
        assertTrue("Production must use file-backed Phase 4 evidence", app.contains("FileBackedQueueSchedulingRecoveryStore"))
        assertTrue("Queue admission must persist slot reservation audit evidence", coordinator.contains("recordQueueReservation(audit)"))
        assertTrue("Queue admission must be claimed atomically in Room", coordinator.contains("repository.claimQueueSlotAtomically("))
        assertTrue("Pause All must write the durable admission gate before runtime.pauseAll", service.contains("queueIntelligence.pauseAllDurably(); runtime.pauseAll()"))
        assertFalse("System Worker teardown must not impersonate a user Pause All command", worker.contains("pauseAllDurably()"))
        assertTrue("Worker teardown must pause only the exact durable claims owned by this worker", worker.contains("ownedClaims") && worker.contains("runtime.pauseOwned(downloadId, queueClaimToken)"))
        assertFalse("Worker teardown must not pause transfers owned by other Android execution owners", worker.contains("else runtime.pauseAll()"))
        assertTrue("ViewModel Pause All must use durable hold ordering", viewModel.contains("queueIntelligenceCoordinator.pauseAllDurably()"))
        assertTrue("Queue deletion must be planned instead of dangling raw delete", viewModel.contains("deleteQueueSafely(queue.id)"))
        assertTrue("Terminal notifications must be suppressed by persisted idempotency", notifications.contains("terminalIfFirst"))
        assertTrue("File-backed store must fsync Phase 4 evidence", phase4.contains("out.fd.sync()"))
    }

    private fun source(relative: String): String = File(root, relative).readText()

    private fun androidRoot(): File {
        var cursor = File(System.getProperty("user.dir") ?: ".").canonicalFile
        repeat(8) {
            if (File(cursor, "settings.gradle.kts").isFile && File(cursor, "app/src/main").isDirectory) return cursor
            cursor = cursor.parentFile ?: cursor
        }
        error("Android root not found")
    }
}
