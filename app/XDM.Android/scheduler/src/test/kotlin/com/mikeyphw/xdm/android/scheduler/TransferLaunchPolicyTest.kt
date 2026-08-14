package com.mikeyphw.xdm.android.scheduler

import org.junit.Assert.assertEquals
import org.junit.Test

class TransferLaunchPolicyTest {
    @Test fun android14VisibleLaunchUsesUidt() {
        assertEquals(TransferLaunchMode.UserInitiatedJob, TransferLaunchPolicy.select(34, true))
    }

    @Test fun visibleLegacyLaunchUsesForegroundServiceButBackgroundUsesWorkManager() {
        assertEquals(TransferLaunchMode.ForegroundService, TransferLaunchPolicy.select(33, true))
        assertEquals(TransferLaunchMode.WorkManager, TransferLaunchPolicy.select(36, false))
        assertEquals(TransferLaunchMode.WorkManager, TransferLaunchPolicy.select(33, false))
    }
}
