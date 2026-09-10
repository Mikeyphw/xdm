package com.mikeyphw.xdm.android.scheduler

import com.mikeyphw.xdm.android.model.DownloadState
import com.mikeyphw.xdm.android.model.NotificationActionModel
import com.mikeyphw.xdm.android.model.NotificationActionVisibility
import com.mikeyphw.xdm.android.model.QueueControlCommand
import com.mikeyphw.xdm.android.model.QueueStateMachinePlanner
import com.mikeyphw.xdm.android.model.RecoveryArtifactIdentity
import com.mikeyphw.xdm.android.model.RecoveryOperation
import com.mikeyphw.xdm.android.model.RecoveryOperationOutcome
import com.mikeyphw.xdm.android.model.SystemExecutionOwner
import com.mikeyphw.xdm.android.model.TerminalNotificationKey
import com.mikeyphw.xdm.android.model.TerminalNotificationRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.io.path.createTempDirectory

// Phase 4: Queue, Scheduling, And State Machines
class QueueSchedulingRecoveryCoordinatorTest {
    @Test fun pauseAllRequiresDurableHoldBeforeControls() {
        val store = InMemoryQueueSchedulingRecoveryStore()
        val coordinator = QueueSchedulingRecoveryCoordinator(store)
        val result = QueueStateMachinePlanner.planPauseAll(emptyList(), generation = 9, nowEpochMs = 100)
        coordinator.recordPauseAll(result)
        assertEquals(1, store.queueCommands().size)
        assertTrue(store.queueCommands().first().durableHold?.blocksNewStarts == true)
    }

    @Test fun systemStopReasonsAreStoredByExecutionOwner() {
        val store = InMemoryQueueSchedulingRecoveryStore()
        val coordinator = QueueSchedulingRecoveryCoordinator(store)
        val record = coordinator.recordSystemStop("download-1", 3, SystemExecutionOwner.UserInitiatedJob, stopReason = 11, nowEpochMs = 500)
        assertEquals(11, record.jobParametersStopReason)
        assertEquals(null, record.workInfoStopReason)
        assertTrue(record.hasSpecificReason)
        assertEquals(record, store.systemStopReasons("download-1").single())
    }

    @Test fun immediateReevaluationIsCoalescedDurably() {
        val store = InMemoryQueueSchedulingRecoveryStore()
        val coordinator = QueueSchedulingRecoveryCoordinator(store)
        coordinator.requestImmediateReevaluation("network", "queue-default", 1)
        coordinator.requestImmediateReevaluation("battery", "queue-default", 2)
        assertEquals(1, store.pendingImmediateReevaluations().size)
        assertEquals("battery", store.pendingImmediateReevaluations().single().source)
    }

    @Test fun recoveryPlanReturnsTypedBlockedOutcomesInsteadOfSilentFailure() {
        val store = InMemoryQueueSchedulingRecoveryStore()
        val coordinator = QueueSchedulingRecoveryCoordinator(store)
        val blocked = coordinator.planRecovery(
            downloadId = "download-1",
            attemptGeneration = 4,
            state = DownloadState.RecoveryRequired,
            operation = RecoveryOperation.LocateFile,
            artifacts = RecoveryArtifactIdentity(null, null, null, null, "ownership-1", "journal-1"),
        )
        assertFalse(blocked.safeToExecute)
        assertEquals(RecoveryOperationOutcome.NeedsFileSelection, blocked.outcomeIfBlocked)
    }

    @Test fun terminalNotificationDispatchIsIdempotentPerAttemptGeneration() {
        val store = InMemoryQueueSchedulingRecoveryStore()
        val coordinator = QueueSchedulingRecoveryCoordinator(store)
        val record = TerminalNotificationRecord(
            key = TerminalNotificationKey("download-1", attemptGeneration = 5, state = DownloadState.Completed),
            title = "Download complete",
            text = "file.bin",
            actions = listOf(NotificationActionModel(QueueControlCommand.StartOne, "Open XDM", NotificationActionVisibility.Show, "download-1")),
            createdAtEpochMs = 10,
        )
        assertTrue(coordinator.recordTerminalNotification(record))
        assertFalse(coordinator.recordTerminalNotification(record))
        assertEquals(1, store.terminalNotifications().size)
    }

    @Test fun fileBackedStorePersistsTerminalIdempotencyAcrossInstances() {
        val root = createTempDirectory(prefix = "xdm-phase4-store").toFile()
        try {
            val first = QueueSchedulingRecoveryCoordinator(FileBackedQueueSchedulingRecoveryStore(root))
            val second = QueueSchedulingRecoveryCoordinator(FileBackedQueueSchedulingRecoveryStore(root))
            val record = TerminalNotificationRecord(
                key = TerminalNotificationKey("download-1", attemptGeneration = 7, state = DownloadState.Failed),
                title = "Download failed",
                text = "network",
                actions = listOf(NotificationActionModel(QueueControlCommand.RetryOne, "Retry", NotificationActionVisibility.Show, "download-1")),
                createdAtEpochMs = 20,
            )
            assertTrue(first.recordTerminalNotification(record))
            assertFalse(second.recordTerminalNotification(record))
            assertEquals(1, second.snapshot().terminalNotifications.size)
        } finally {
            root.deleteRecursively()
        }
    }
    @Test fun pendingTerminalReservationSurvivesRestartAndDispatchMark() {
        val root = createTempDirectory(prefix = "xdm-terminal-pending").toFile()
        try {
            val first = QueueSchedulingRecoveryCoordinator(FileBackedQueueSchedulingRecoveryStore(root))
            val record = TerminalNotificationRecord(
                key = TerminalNotificationKey("download-pending", 9, DownloadState.Completed),
                title = "Download complete",
                text = "file.bin",
                actions = emptyList(),
                createdAtEpochMs = 30,
            )
            assertTrue(first.recordTerminalNotification(record))
            assertFalse(first.recordTerminalNotification(record))
            val restarted = QueueSchedulingRecoveryCoordinator(FileBackedQueueSchedulingRecoveryStore(root))
            assertEquals(listOf(record.idempotencyKey), restarted.pendingTerminalNotifications().map { it.idempotencyKey })
            restarted.markTerminalNotificationDispatched(record.idempotencyKey, 40)
            assertTrue(restarted.pendingTerminalNotifications().isEmpty())
            assertFalse(restarted.recordTerminalNotification(record))
        } finally { root.deleteRecursively() }
    }

    @Test fun fileBackedTerminalRecordRehydratesTruthfulActions() {
        val root = createTempDirectory(prefix = "xdm-terminal-actions").toFile()
        try {
            val first = QueueSchedulingRecoveryCoordinator(FileBackedQueueSchedulingRecoveryStore(root))
            val record = TerminalNotificationRecord(
                key = TerminalNotificationKey("download-recovery", 12, DownloadState.RecoveryRequired),
                title = "Download needs action",
                text = "Review recovery",
                actions = listOf(
                    NotificationActionModel(QueueControlCommand.ReviewRecovery, "Review recovery", NotificationActionVisibility.Show, "download-recovery"),
                    NotificationActionModel(QueueControlCommand.DismissNotification, "Dismiss", NotificationActionVisibility.Show, "download-recovery"),
                ),
                createdAtEpochMs = 50,
            )
            assertTrue(first.recordTerminalNotification(record))
            val reloaded = QueueSchedulingRecoveryCoordinator(FileBackedQueueSchedulingRecoveryStore(root))
                .snapshot().terminalNotifications.single()
            assertTrue(reloaded.actions.any { it.command == QueueControlCommand.ReviewRecovery && it.downloadId == "download-recovery" })
            assertTrue(reloaded.actions.any { it.command == QueueControlCommand.DismissNotification })
            assertFalse(reloaded.actions.any { it.command == QueueControlCommand.RetryOne })
        } finally { root.deleteRecursively() }
    }

}
