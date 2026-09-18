package com.mikeyphw.xdm.android.scheduler

import org.junit.Assert.assertEquals
import org.junit.Test

class TransferLaunchPolicyTest {
    @Test fun everyUserVisibleLaunchUsesNotificationOwningForegroundService() {
        assertEquals(TransferLaunchMode.ForegroundService, TransferLaunchPolicy.select(33, true))
        assertEquals(TransferLaunchMode.ForegroundService, TransferLaunchPolicy.select(34, true))
        assertEquals(TransferLaunchMode.ForegroundService, TransferLaunchPolicy.select(36, true))
    }

    @Test fun backgroundLaunchUsesWorkManager() {
        assertEquals(TransferLaunchMode.WorkManager, TransferLaunchPolicy.select(36, false))
        assertEquals(TransferLaunchMode.WorkManager, TransferLaunchPolicy.select(33, false))
    }
}
