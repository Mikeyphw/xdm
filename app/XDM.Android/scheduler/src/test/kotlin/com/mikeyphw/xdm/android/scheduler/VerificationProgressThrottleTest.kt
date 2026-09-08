package com.mikeyphw.xdm.android.scheduler

import com.mikeyphw.xdm.android.model.ChecksumAlgorithm
import com.mikeyphw.xdm.android.model.VerificationRecord
import com.mikeyphw.xdm.android.model.VerificationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VerificationProgressThrottleTest {
    @Test
    fun runningVerificationPersistsAtBoundedCadenceButTerminalIsImmediate() {
        val gate = VerificationProgressThrottle()
        assertTrue(gate.shouldPersist(record(bytes = 0, at = 0)))
        assertFalse(gate.shouldPersist(record(bytes = 1024, at = 100)))
        assertFalse(gate.shouldPersist(record(bytes = 5L * 1024 * 1024, at = 200)))
        assertTrue(gate.shouldPersist(record(bytes = 5L * 1024 * 1024, at = 400)))
        assertFalse(gate.shouldPersist(record(bytes = 6L * 1024 * 1024, at = 700)))
        assertTrue(gate.shouldPersist(record(bytes = 6L * 1024 * 1024, at = 1_500)))
        assertTrue(gate.shouldPersist(record(bytes = 6L * 1024 * 1024, at = 1_501, status = VerificationStatus.Passed)))
    }

    @Test
    fun oneGiBEightKiBProgressStreamHasTinyDurableWriteBudget() {
        val gate = VerificationProgressThrottle()
        var writes = 0
        var time = 0L
        val total = 1024L * 1024L * 1024L
        var bytes = 0L
        while (bytes <= total) {
            if (gate.shouldPersist(record(bytes, time))) writes++
            bytes += 8L * 1024L
            time += 1L
        }
        // 131k callbacks become hundreds of durable records, not hundreds of thousands.
        assertTrue("writes=$writes", writes in 200..3_500)
    }

    private fun record(bytes: Long, at: Long, status: VerificationStatus = VerificationStatus.Running) = VerificationRecord(
        id = "verification-d",
        downloadId = "d",
        status = status,
        algorithm = ChecksumAlgorithm.Sha256,
        bytesVerified = bytes,
        totalBytes = 1024L * 1024L * 1024L,
        message = "progress",
        createdAtEpochMs = 0,
        updatedAtEpochMs = at,
        attemptGeneration = 1,
    )
}
