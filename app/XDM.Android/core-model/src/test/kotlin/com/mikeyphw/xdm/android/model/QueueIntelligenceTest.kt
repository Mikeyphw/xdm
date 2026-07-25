package com.mikeyphw.xdm.android.model

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueIntelligenceTest {
    private val ready = QueueRuntimeConditions(
        connected = true,
        validated = true,
        unmetered = true,
        wifi = true,
        charging = true,
        batteryPercent = 80,
        availableStorageBytes = 8L * 1024 * 1024 * 1024,
        nowEpochMs = 1_000_000L,
    )

    @Test fun wifi_policy_holds_mobile_network() {
        val decision = QueueIntelligencePlanner.decision(
            QueueExecutionPolicy(networkRequirement = QueueNetworkRequirement.Wifi),
            ready.copy(wifi = false),
        )
        assertEquals(QueueHoldReason.WifiRequired, decision.reason)
        assertFalse(decision.canStart)
    }

    @Test fun unvalidated_network_is_never_bypassed() {
        val decision = QueueIntelligencePlanner.decision(
            QueueExecutionPolicy(),
            ready.copy(validated = false),
            policyOverride = true,
        )
        assertEquals(QueueHoldReason.NetworkUnavailable, decision.reason)
        assertFalse(decision.canStart)
    }

    @Test fun explicit_override_bypasses_soft_queue_policy() {
        val decision = QueueIntelligencePlanner.decision(
            QueueExecutionPolicy(
                networkRequirement = QueueNetworkRequirement.Wifi,
                chargingRequired = true,
                minimumBatteryPercent = 90,
                minimumFreeStorageBytes = Long.MAX_VALUE,
                maxConcurrent = 1,
            ),
            ready.copy(wifi = false, charging = false, batteryPercent = 10),
            queueEnabled = false,
            scheduleActive = false,
            activeCount = 10,
            policyOverride = true,
        )
        assertTrue(decision.canStart)
        assertTrue(decision.policyOverridden)
    }

    @Test fun battery_and_storage_policies_are_explainable() {
        val battery = QueueIntelligencePlanner.decision(
            QueueExecutionPolicy(minimumBatteryPercent = 60),
            ready.copy(batteryPercent = 30),
        )
        assertEquals(QueueHoldReason.BatteryLow, battery.reason)
        val storage = QueueIntelligencePlanner.decision(
            QueueExecutionPolicy(minimumFreeStorageBytes = 1024),
            ready.copy(availableStorageBytes = 512),
        )
        assertEquals(QueueHoldReason.StoragePressure, storage.reason)
    }

    @Test fun concurrency_limit_is_enforced() {
        val decision = QueueIntelligencePlanner.decision(QueueExecutionPolicy(maxConcurrent = 2), ready, activeCount = 2)
        assertEquals(QueueHoldReason.ConcurrencyLimit, decision.reason)
    }

    @Test fun transient_failures_back_off_and_permanent_failures_require_review() {
        val record = QueueIntelligencePlanner.retryRecord(QueueRetryStrategy.Balanced, 0, ready.nowEpochMs)
        val cooling = QueueIntelligencePlanner.decision(
            QueueExecutionPolicy(retryStrategy = QueueRetryStrategy.Balanced),
            ready,
            retryRecord = record,
            failureMessage = "network timeout",
        )
        assertEquals(QueueLaunchDisposition.RetryLater, cooling.disposition)
        val auth = QueueIntelligencePlanner.decision(
            QueueExecutionPolicy(retryStrategy = QueueRetryStrategy.Aggressive),
            ready,
            failureMessage = "HTTP 403 authentication required",
        )
        assertEquals(QueueHoldReason.AuthenticationRequired, auth.reason)
        val missing = QueueIntelligencePlanner.decision(
            QueueExecutionPolicy(retryStrategy = QueueRetryStrategy.Aggressive),
            ready,
            failureMessage = "HTTP 404 not found",
        )
        assertEquals(QueueHoldReason.PermanentFailure, missing.reason)
    }

    @Test fun retry_backoff_grows_exponentially() {
        val first = QueueIntelligencePlanner.retryRecord(QueueRetryStrategy.Balanced, 0, 1_000L)
        val second = QueueIntelligencePlanner.retryRecord(QueueRetryStrategy.Balanced, 1, 1_000L)
        assertEquals(5L * 60_000L, first.nextRetryAtEpochMs - first.lastFailureAtEpochMs)
        assertEquals(10L * 60_000L, second.nextRetryAtEpochMs - second.lastFailureAtEpochMs)
    }

    @Test fun priority_ranking_balances_explicit_priority_and_age() {
        val now = 2_000_000L
        val low = download("low", priority = 0, created = 0L)
        val high = download("high", priority = 2, created = now)
        val ranked = QueueIntelligencePlanner.rank(listOf(low, high), now)
        assertEquals("high", ranked.first().download.id)
        assertTrue(ranked.first().explanation.contains("priority"))
    }

    @Test fun overnight_schedule_uses_start_day_across_midnight() {
        val zone = ZoneId.of("UTC")
        val fridayLate = ZonedDateTime.of(2026, 7, 24, 23, 0, 0, 0, zone).toInstant().toEpochMilli()
        val saturdayEarly = ZonedDateTime.of(2026, 7, 25, 2, 0, 0, 0, zone).toInstant().toEpochMilli()
        val saturdayLate = ZonedDateTime.of(2026, 7, 25, 23, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertTrue(QueueIntelligencePlanner.isWindowActive("Friday", "22:00", "06:00", fridayLate, zone))
        assertTrue(QueueIntelligencePlanner.isWindowActive("Friday", "22:00", "06:00", saturdayEarly, zone))
        assertFalse(QueueIntelligencePlanner.isWindowActive("Friday", "22:00", "06:00", saturdayLate, zone))
    }

    private fun download(id: String, priority: Int, created: Long) = Download(
        id = id,
        fileName = "$id.bin",
        sourceUrl = "https://example.test/$id.bin",
        destinationUri = "xdm://downloads",
        state = DownloadState.Queued,
        backend = BackendType.Native,
        bytesReceived = 0,
        totalBytes = 1000,
        speedBytesPerSecond = 0,
        queueId = "default",
        priority = priority,
        createdAtEpochMs = created,
        updatedAtEpochMs = created,
    )
}
