package com.mikeyphw.xdm.android.model

import java.time.DayOfWeek
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Phase 4: Queue, Scheduling, And State Machines
class QueueStateMachineModelsTest {
    @Test fun slotReservationIsAtomicWithConcurrencyBudget() {
        val budget = QueueBudget(maxConcurrent = 2, reservedSlots = 1)
        val accepted = QueueStateMachinePlanner.reserveSlotAtomically("default", "download-1", 7, activeCount = 0, budget = budget)
        assertTrue(accepted.accepted)
        assertEquals(2, accepted.budgetAfterClaim.reservedSlots)
        assertEquals("default:download-1:7", accepted.claimKey)

        val denied = QueueStateMachinePlanner.reserveSlotAtomically("default", "download-2", 8, activeCount = 1, budget = accepted.budgetAfterClaim)
        assertFalse(denied.accepted)
        assertEquals(QueueHoldReason.ConcurrencyLimit, denied.reason)
    }

    @Test fun pauseAllBlocksNewStartsAndCoversEveryActivePhase() {
        val downloads = listOf(
            download("a", DownloadState.Connecting),
            download("b", DownloadState.Downloading),
            download("c", DownloadState.Finalizing),
            download("d", DownloadState.Verifying),
            download("e", DownloadState.Repairing),
            download("f", DownloadState.Completed),
        )
        val result = QueueStateMachinePlanner.planPauseAll(downloads, generation = 42, nowEpochMs = 1000)
        assertEquals(QueueControlOutcome.Accepted, result.outcome)
        assertEquals(listOf("a", "b", "c", "d", "e"), result.affectedDownloadIds)
        assertTrue(result.durableHold?.blocksNewStarts == true)
        assertTrue(result.durableHold?.coveredStates?.contains(DownloadState.Verifying) == true)
        assertTrue(result.message.contains("one failure does not stop the rest"))
    }

    @Test fun incompleteScheduleWindowsFailClosed() {
        val invalid = QueueStateMachinePlanner.validateScheduleWindow(QueueScheduleWindow(days = setOf(DayOfWeek.MONDAY), start = LocalTime.of(9, 0)))
        assertFalse(invalid.valid)
        assertNotNull(invalid.error)

        val short = QueueStateMachinePlanner.validateScheduleWindow(
            QueueScheduleWindow(days = setOf(DayOfWeek.MONDAY), start = LocalTime.of(9, 0), end = LocalTime.of(9, 5)),
        )
        assertTrue(short.valid)
        assertTrue(short.preciseShortWindowRequired)
    }

    @Test fun retryDeadlinesAreTiedToFailureGeneration() {
        val deadline = QueueStateMachinePlanner.retryDeadline("download-1", failureGeneration = 11, attempt = 2, failedAtEpochMs = 60_000, strategy = QueueRetryStrategy.Balanced)
        assertEquals(11, deadline.failureGeneration)
        assertEquals(2, deadline.attempt)
        assertTrue(deadline.eligibleAtEpochMs > deadline.failedAtEpochMs)
        assertTrue(deadline.scheduled)
    }

    @Test fun recoveryDoesNotResurrectCancelledOrCompletedRows() {
        assertEquals(DownloadState.Cancelled, QueueStateMachinePlanner.recoverState(DownloadState.Cancelled, RecoveryClassification.ReadyToResume))
        assertEquals(DownloadState.Completed, QueueStateMachinePlanner.recoverState(DownloadState.Completed, RecoveryClassification.FinalizationInterrupted))
        assertEquals(DownloadState.Paused, QueueStateMachinePlanner.recoverState(DownloadState.Downloading, RecoveryClassification.ReadyToResume))
        assertEquals(DownloadState.RecoveryRequired, QueueStateMachinePlanner.recoverState(DownloadState.Downloading, RecoveryClassification.BackendTaskOrphaned))
    }

    @Test fun notificationActionsAreDerivedFromActualState() {
        val activeActions = QueueStateMachinePlanner.notificationActions(DownloadState.Downloading, setOf(DownloadState.Downloading), "download-1")
        assertTrue(activeActions.any { it.command == QueueControlCommand.PauseAll && it.label == "Pause all" })
        assertTrue(activeActions.any { it.command == QueueControlCommand.PauseOne && it.downloadId == "download-1" })

        val recoveryActions = QueueStateMachinePlanner.notificationActions(DownloadState.RecoveryRequired, setOf(DownloadState.RecoveryRequired), "download-2")
        assertTrue(recoveryActions.any { it.label == "Review recovery" })
        assertFalse(recoveryActions.any { it.label == "Pause all" })
    }

    private fun download(id: String, state: DownloadState): Download = Download(
        id = id,
        fileName = "$id.bin",
        sourceUrl = "https://example.com/$id.bin",
        destinationUri = "file:///tmp/$id.bin",
        state = state,
        backend = BackendType.Native,
        bytesReceived = 0,
        totalBytes = 100,
        speedBytesPerSecond = 0,
        queueId = "default",
        priority = 0,
        createdAtEpochMs = 1,
        updatedAtEpochMs = 1,
    )
}
