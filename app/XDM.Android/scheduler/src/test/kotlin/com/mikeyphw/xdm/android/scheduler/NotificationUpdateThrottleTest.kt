package com.mikeyphw.xdm.android.scheduler

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationUpdateThrottleTest {
    @Test fun boundsNotificationUpdatesButAllowsForcedTerminalRefresh() {
        var now = 1_000L
        val throttle = NotificationUpdateThrottle(minimumIntervalMs = 750L, clock = { now })
        assertTrue(throttle.shouldPublish())
        now += 100
        assertFalse(throttle.shouldPublish())
        now += 649
        assertFalse(throttle.shouldPublish())
        now += 1
        assertTrue(throttle.shouldPublish())
        now += 1
        assertTrue(throttle.shouldPublish(force = true))
    }
}
