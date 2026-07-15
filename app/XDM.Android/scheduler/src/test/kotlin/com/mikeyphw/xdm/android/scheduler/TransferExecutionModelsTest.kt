package com.mikeyphw.xdm.android.scheduler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferExecutionModelsTest {
    @Test fun aggregateProgressIsBounded() {
        assertEquals(50, ActiveTransferSummary(bytesReceived = 50, totalBytes = 100).progressPercent)
        assertEquals(100, ActiveTransferSummary(bytesReceived = 120, totalBytes = 100).progressPercent)
        assertNull(ActiveTransferSummary(bytesReceived = 20).progressPercent)
    }

    @Test fun systemIdsAreStableAndPositive() {
        val first = "download-1".stableSystemId()
        assertEquals(first, "download-1".stableSystemId())
        assertTrue(first > 0)
    }
}
