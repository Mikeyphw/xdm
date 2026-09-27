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
        assertTrue(coordinator.contains("saveImmediateReevaluation"))
        assertTrue(coordinator.contains("coalesceKey"))
        assertTrue(worker.contains("AndroidSchedulerHost.wake("))
        assertFalse(worker.contains("getStopReason()"))
        assertTrue(job.contains("AndroidSchedulerHost.wake("))
        assertTrue(job.contains("override fun onStopJob"))
        assertTrue(job.contains("No Kotlin transfer owner exists to pause"))
        assertFalse(job.contains("params.stopReason"))
    }

    @Test fun recoveryAndNotificationStateMachinesAreTypedAndIdempotent() {
        val model = source("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/QueueStateMachineModels.kt")
        val coordinator = source("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/QueueSchedulingRecoveryCoordinator.kt")
        val notifications = source("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferNotifications.kt")
        val terminalPolicy = source("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TerminalNotificationActionPolicy.kt")
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
        assertTrue(coordinator.contains("terminalNotificationsLocked().firstOrNull { it.idempotencyKey == record.idempotencyKey }"))
        assertTrue(coordinator.contains("\"terminal-dispatched\""))
        assertTrue((notifications + terminalPolicy).contains("Review recovery"))
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
        val executionBroker = source("app/src/main/kotlin/com/mikeyphw/xdm/android/engine/AndroidDownloadExecutionBroker.kt")
        val notifications = source("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferNotifications.kt")
        assertTrue("App must expose the durable Phase 4 evidence provider", app.contains("QueueSchedulingRecoveryProvider"))
        assertTrue("Production must keep file-backed Phase 4 evidence", app.contains("FileBackedQueueSchedulingRecoveryStore"))
        assertTrue("Compatibility coordinator must delegate user intent to Go", coordinator.contains("AndroidGoDownloadCommands.submit("))
        assertFalse("Compatibility coordinator must not reclaim Kotlin queue authority", coordinator.contains("claimQueueSlotAtomically(") || coordinator.contains("admissionGate") || coordinator.contains("AndroidExecutionClaimRegistry.install"))
        assertTrue("Foreground controls must cross the Go command boundary", service.contains("submitGoCommand(\"pause_all\")"))
        assertFalse("Foreground service must not directly pause Kotlin transfer owners", service.contains("queueIntelligence.pauseAllDurably()") || service.contains("runtime.pauseAll()"))
        assertTrue("WorkManager must host/wake Go instead of owning exact transfer claims", worker.contains("AndroidSchedulerHost.wake("))
        assertFalse("WorkManager must not execute or pause transfer owners after XGO-71", worker.contains("runtime.pauseOwned(") || worker.contains("coordinator.evaluateAndClaim()"))
        assertTrue("ViewModel Pause All must cross the Go command boundary", viewModel.contains("action = \"pause_all\""))
        assertTrue("Go-authorized platform broker may perform only the accepted pause side effects", executionBroker.contains("\"pause_all\" -> pauseAll()") && executionBroker.contains("transferRuntime.pauseAll()") && executionBroker.contains("nativeHls.pauseAll()"))
        assertFalse("Execution broker must not regain queue policy authority", executionBroker.contains("QueueIntelligenceCoordinator") || executionBroker.contains("QueueRetryLedger") || executionBroker.contains("claimQueueSlotAtomically"))
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
