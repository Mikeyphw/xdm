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
}
