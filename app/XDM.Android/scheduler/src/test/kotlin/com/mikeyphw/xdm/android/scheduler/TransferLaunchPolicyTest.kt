package com.mikeyphw.xdm.android.scheduler

import org.junit.Assert.assertEquals
import org.junit.Test

class TransferLaunchPolicyTest {
    @Test fun android14VisibleLaunchUsesUidt() {
        assertEquals(TransferLaunchMode.UserInitiatedJob, TransferLaunchPolicy.select(34, true))
    }

    @Test fun olderOrBackgroundLaunchUsesForegroundService() {
        assertEquals(TransferLaunchMode.ForegroundService, TransferLaunchPolicy.select(33, true))
        assertEquals(TransferLaunchMode.ForegroundService, TransferLaunchPolicy.select(36, false))
    }
}
