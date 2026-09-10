package com.mikeyphw.xdm.android.scheduler

/** Keeps transfer telemetry high-frequency in app while bounding NotificationManager binder churn. */
internal class NotificationUpdateThrottle(
    private val minimumIntervalMs: Long = 750L,
    private val clock: () -> Long = android.os.SystemClock::elapsedRealtime,
) {
    private var lastPublishedAtMs: Long = Long.MIN_VALUE

    fun shouldPublish(force: Boolean = false): Boolean {
        val now = clock()
        if (force || lastPublishedAtMs == Long.MIN_VALUE || now - lastPublishedAtMs >= minimumIntervalMs) {
            lastPublishedAtMs = now
            return true
        }
        return false
    }
}
