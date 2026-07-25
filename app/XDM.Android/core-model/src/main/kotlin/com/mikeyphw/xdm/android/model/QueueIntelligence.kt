package com.mikeyphw.xdm.android.model

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** Network policy applied before a queued transfer may start. */
enum class QueueNetworkRequirement(val label: String) {
    Any("Any network"),
    Unmetered("Unmetered network"),
    Wifi("Wi-Fi only"),
}

/** Automatic retry posture. Manual never retries without an explicit user action. */
enum class QueueRetryStrategy(val label: String) {
    Manual("Manual"),
    Conservative("Conservative"),
    Balanced("Balanced"),
    Aggressive("Aggressive"),
}

data class QueueExecutionPolicy(
    val networkRequirement: QueueNetworkRequirement = QueueNetworkRequirement.Any,
    val chargingRequired: Boolean = false,
    val minimumBatteryPercent: Int? = null,
    val stopOnStoragePressure: Boolean = true,
    val minimumFreeStorageBytes: Long = DEFAULT_STORAGE_RESERVE_BYTES,
    val retryStrategy: QueueRetryStrategy = QueueRetryStrategy.Balanced,
    val maxAutoRetries: Int = 4,
    val maxConcurrent: Int = 3,
) {
    companion object {
        const val DEFAULT_STORAGE_RESERVE_BYTES: Long = 512L * 1024L * 1024L
    }
}

data class QueueRuntimeConditions(
    val connected: Boolean,
    val validated: Boolean = connected,
    val unmetered: Boolean,
    val wifi: Boolean,
    val charging: Boolean,
    val batteryPercent: Int?,
    val availableStorageBytes: Long?,
    val nowEpochMs: Long,
)

enum class QueueHoldReason {
    QueueDisabled,
    ScheduleWindow,
    NetworkUnavailable,
    UnmeteredRequired,
    WifiRequired,
    ChargingRequired,
    BatteryLow,
    StoragePressure,
    ConcurrencyLimit,
    RetryBackoff,
    RetryLimit,
    AuthenticationRequired,
    PermissionRequired,
    VerificationFailed,
    UnsupportedFailure,
    PermanentFailure,
    NonRetryableFailure,
}

enum class QueueLaunchDisposition { Start, Hold, RetryLater }

data class QueueLaunchDecision(
    val disposition: QueueLaunchDisposition,
    val reason: QueueHoldReason? = null,
    val title: String,
    val detail: String,
    val nextEligibleAtEpochMs: Long? = null,
    val policyOverridden: Boolean = false,
) {
    val canStart: Boolean get() = disposition == QueueLaunchDisposition.Start
}

data class QueueRetryRecord(
    val attempt: Int,
    val lastFailureAtEpochMs: Long,
    val nextRetryAtEpochMs: Long,
)

data class QueueRankedDownload(
    val download: Download,
    val score: Long,
    val explanation: String,
)

enum class QueueFailureClass {
    Transient,
    Authentication,
    Permission,
    Verification,
    Unsupported,
    PermanentHttp,
    Unknown,
}

data class QueueFailureAssessment(
    val classification: QueueFailureClass,
    val retryable: Boolean,
    val holdReason: QueueHoldReason?,
    val title: String,
    val detail: String,
)

data class QueueScheduleWindow(
    val days: Set<DayOfWeek> = emptySet(),
    val start: LocalTime? = null,
    val end: LocalTime? = null,
) {
    fun contains(now: LocalDateTime): Boolean {
        val windowStart = start ?: return days.isEmpty() || now.dayOfWeek in days
        val windowEnd = end ?: return days.isEmpty() || now.dayOfWeek in days
        val time = now.toLocalTime()
        if (windowStart <= windowEnd) {
            return (days.isEmpty() || now.dayOfWeek in days) && time >= windowStart && time <= windowEnd
        }
        // Overnight windows belong to the day on which they start. For example,
        // Friday 22:00–06:00 includes Saturday at 02:00.
        return when {
            time >= windowStart -> days.isEmpty() || now.dayOfWeek in days
            time <= windowEnd -> days.isEmpty() || now.dayOfWeek.minusOne() in days
            else -> false
        }
    }

    private fun DayOfWeek.minusOne(): DayOfWeek = DayOfWeek.of(if (value == 1) 7 else value - 1)
}

data class QueueResolvedSchedule(
    val policy: QueueExecutionPolicy,
    val hasApplicableRules: Boolean,
    val activeRuleName: String? = null,
    val nextWindowSummary: String? = null,
)

data class QueueDecisionEvent(
    val id: String,
    val downloadId: String,
    val fileName: String,
    val disposition: QueueLaunchDisposition,
    val reason: QueueHoldReason?,
    val title: String,
    val detail: String,
    val createdAtEpochMs: Long,
    val nextEligibleAtEpochMs: Long? = null,
    val policyOverridden: Boolean = false,
)

data class QueueIntelligenceSummary(
    val evaluatedAtEpochMs: Long = 0L,
    val started: Int = 0,
    val heldForNetwork: Int = 0,
    val heldForPower: Int = 0,
    val heldForStorage: Int = 0,
    val heldForSchedule: Int = 0,
    val heldForConcurrency: Int = 0,
    val waitingForRetry: Int = 0,
    val retryLimitReached: Int = 0,
    val manualReviewRequired: Int = 0,
    val recentDecisions: List<QueueDecisionEvent> = emptyList(),
    val message: String = "Queue intelligence has not evaluated the current conditions yet.",
)

/** Pure scheduling, hold, retry, and priority decisions for queue execution. */
object QueueIntelligencePlanner {
    fun decision(
        policy: QueueExecutionPolicy,
        conditions: QueueRuntimeConditions,
        queueEnabled: Boolean = true,
        scheduleActive: Boolean = true,
        scheduleSummary: String? = null,
        activeCount: Int = 0,
        retryRecord: QueueRetryRecord? = null,
        failureMessage: String? = null,
        policyOverride: Boolean = false,
    ): QueueLaunchDecision {
        if (!conditions.connected || !conditions.validated) {
            return hold(QueueHoldReason.NetworkUnavailable, "Waiting for network", "No validated internet connection is available.")
        }
        if (policyOverride) {
            return QueueLaunchDecision(
                disposition = QueueLaunchDisposition.Start,
                title = "Starting with override",
                detail = "You chose to bypass queue timing, network-type, power, storage, concurrency, and retry policy for this start.",
                policyOverridden = true,
            )
        }
        if (!queueEnabled) return hold(QueueHoldReason.QueueDisabled, "Queue disabled", "Enable the queue or use Start anyway for this transfer.")
        if (!scheduleActive) {
            val suffix = scheduleSummary?.takeIf(String::isNotBlank)?.let { " Next window: $it." }.orEmpty()
            return hold(QueueHoldReason.ScheduleWindow, "Outside schedule", "This queue is waiting for an enabled schedule window.$suffix")
        }
        if (activeCount >= policy.maxConcurrent) {
            return hold(
                QueueHoldReason.ConcurrencyLimit,
                "Queue limit reached",
                "${policy.maxConcurrent} transfer${if (policy.maxConcurrent == 1) " is" else "s are"} already active in this queue.",
            )
        }
        if (policy.networkRequirement == QueueNetworkRequirement.Unmetered && !conditions.unmetered) {
            return hold(QueueHoldReason.UnmeteredRequired, "Waiting for unmetered network", "The active network is metered.")
        }
        if (policy.networkRequirement == QueueNetworkRequirement.Wifi && !conditions.wifi) {
            return hold(QueueHoldReason.WifiRequired, "Waiting for Wi-Fi", "This queue is configured to run only on Wi-Fi.")
        }
        if (policy.chargingRequired && !conditions.charging) {
            return hold(QueueHoldReason.ChargingRequired, "Waiting for power", "Connect a charger to start this queue.")
        }
        val minimumBattery = policy.minimumBatteryPercent
        val battery = conditions.batteryPercent
        if (minimumBattery != null && battery != null && battery < minimumBattery) {
            return hold(QueueHoldReason.BatteryLow, "Battery below policy", "Battery is $battery%; this queue requires at least $minimumBattery%.")
        }
        val availableStorage = conditions.availableStorageBytes
        if (policy.stopOnStoragePressure && availableStorage != null && availableStorage < policy.minimumFreeStorageBytes) {
            return hold(QueueHoldReason.StoragePressure, "Storage pressure", "Free app storage is below the queue reserve. Free space or choose another destination.")
        }
        if (failureMessage != null) {
            val assessment = assessFailure(failureMessage)
            if (!assessment.retryable) {
                return hold(assessment.holdReason ?: QueueHoldReason.NonRetryableFailure, assessment.title, assessment.detail)
            }
            if (policy.retryStrategy == QueueRetryStrategy.Manual) {
                return hold(QueueHoldReason.NonRetryableFailure, "Manual retry", "Automatic retries are disabled for this queue.")
            }
            if (policy.maxAutoRetries <= 0 || (retryRecord != null && retryRecord.attempt >= policy.maxAutoRetries)) {
                val attempts = retryRecord?.attempt ?: 0
                return hold(QueueHoldReason.RetryLimit, "Retry limit reached", "Automatic retry stopped after $attempts attempt${if (attempts == 1) "" else "s"}.")
            }
            if (retryRecord != null && conditions.nowEpochMs < retryRecord.nextRetryAtEpochMs) {
                return QueueLaunchDecision(
                    disposition = QueueLaunchDisposition.RetryLater,
                    reason = QueueHoldReason.RetryBackoff,
                    title = "Retry cooling down",
                    detail = "The next automatic retry is waiting for the configured exponential backoff.",
                    nextEligibleAtEpochMs = retryRecord.nextRetryAtEpochMs,
                )
            }
        }
        return QueueLaunchDecision(QueueLaunchDisposition.Start, title = "Ready", detail = "Queue conditions permit this transfer to start.")
    }

    fun rank(downloads: List<Download>, nowEpochMs: Long): List<QueueRankedDownload> = downloads
        .map { download ->
            val ageMinutes = ((nowEpochMs - download.createdAtEpochMs).coerceAtLeast(0L) / 60_000L).coerceAtMost(10_000L)
            val remainingBytes = download.totalBytes?.let { (it - download.bytesReceived).coerceAtLeast(0L) }
            val nearCompleteBonus = if (download.progressFraction >= 0.85f) 30_000L else 0L
            val smallTransferBonus = when {
                remainingBytes == null -> 0L
                remainingBytes <= 25L * 1024L * 1024L -> 12_000L
                remainingBytes <= 250L * 1024L * 1024L -> 5_000L
                else -> 0L
            }
            val explicitPriority = download.priority.toLong() * 100_000L
            val score = explicitPriority + nearCompleteBonus + smallTransferBonus + ageMinutes
            val explanation = buildList {
                if (download.priority != 0) add("priority ${download.priority}")
                if (nearCompleteBonus > 0) add("near completion")
                if (smallTransferBonus > 0) add("short transfer")
                add("age fairness")
            }.joinToString(" • ")
            QueueRankedDownload(download, score, explanation)
        }
        .sortedWith(compareByDescending<QueueRankedDownload> { it.score }.thenBy { it.download.createdAtEpochMs }.thenBy { it.download.id })

    fun retryRecord(
        strategy: QueueRetryStrategy,
        previousAttempt: Int,
        failureAtEpochMs: Long,
    ): QueueRetryRecord {
        val attempt = previousAttempt.coerceAtLeast(0) + 1
        val baseMinutes = when (strategy) {
            QueueRetryStrategy.Manual -> 0L
            QueueRetryStrategy.Conservative -> 15L
            QueueRetryStrategy.Balanced -> 5L
            QueueRetryStrategy.Aggressive -> 1L
        }
        val cappedExponent = (attempt - 1).coerceIn(0, 6)
        val delayMinutes = baseMinutes * (1L shl cappedExponent)
        return QueueRetryRecord(attempt, failureAtEpochMs, failureAtEpochMs + delayMinutes * 60_000L)
    }

    fun assessFailure(message: String): QueueFailureAssessment {
        val normalized = message.lowercase()
        fun containsAny(values: List<String>) = values.any(normalized::contains)
        return when {
            containsAny(listOf("401", "403", "authentication", "login required", "credential", "cookie expired", "token expired")) ->
                QueueFailureAssessment(QueueFailureClass.Authentication, false, QueueHoldReason.AuthenticationRequired, "Authentication required", "Refresh credentials or share the link again before retrying.")
            containsAny(listOf("permission denied", "access denied", "securityexception", "read-only", "write permission")) ->
                QueueFailureAssessment(QueueFailureClass.Permission, false, QueueHoldReason.PermissionRequired, "Permission required", "Repair destination access before retrying.")
            containsAny(listOf("checksum", "hash mismatch", "verification failed", "integrity")) ->
                QueueFailureAssessment(QueueFailureClass.Verification, false, QueueHoldReason.VerificationFailed, "Verification failed", "Review the checksum or use verify-and-repair before retrying.")
            containsAny(listOf("unsupported", "drm", "widevine", "protected content")) ->
                QueueFailureAssessment(QueueFailureClass.Unsupported, false, QueueHoldReason.UnsupportedFailure, "Unsupported transfer", "This source needs manual review and cannot be retried automatically.")
            containsAny(listOf("400", "404", "405", "410", "411", "413", "414", "415", "416", "422")) ->
                QueueFailureAssessment(QueueFailureClass.PermanentHttp, false, QueueHoldReason.PermanentFailure, "Permanent server response", "The server rejected or no longer provides this resource.")
            containsAny(listOf("timeout", "timed out", "network", "connection", "dns", "offline", "temporarily", "429", "500", "502", "503", "504", "reset", "broken pipe", "host unreachable")) ->
                QueueFailureAssessment(QueueFailureClass.Transient, true, null, "Temporary failure", "The transfer can retry after backoff.")
            else -> QueueFailureAssessment(QueueFailureClass.Unknown, false, QueueHoldReason.NonRetryableFailure, "Manual review required", "XDM could not safely classify this failure for automatic retry.")
        }
    }

    fun isRetryableFailure(message: String): Boolean = assessFailure(message).retryable

    fun parseNetworkRequirement(value: String?): QueueNetworkRequirement = when (value?.trim()?.lowercase()) {
        "wifi", "wi-fi", "wifi_only" -> QueueNetworkRequirement.Wifi
        "unmetered", "unmetered_only" -> QueueNetworkRequirement.Unmetered
        else -> QueueNetworkRequirement.Any
    }

    fun parseRetryStrategy(value: String?): QueueRetryStrategy = when (value?.trim()?.lowercase()) {
        "manual" -> QueueRetryStrategy.Manual
        "conservative" -> QueueRetryStrategy.Conservative
        "aggressive" -> QueueRetryStrategy.Aggressive
        else -> QueueRetryStrategy.Balanced
    }

    fun parseDays(value: String?): Set<DayOfWeek> {
        val normalized = value.orEmpty().trim().lowercase()
        if (normalized.isBlank() || normalized == "every day" || normalized == "daily") return emptySet()
        if (normalized == "weekdays") return setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
        if (normalized == "weekends") return setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
        val aliases = mapOf(
            "mon" to DayOfWeek.MONDAY, "monday" to DayOfWeek.MONDAY,
            "tue" to DayOfWeek.TUESDAY, "tuesday" to DayOfWeek.TUESDAY,
            "wed" to DayOfWeek.WEDNESDAY, "wednesday" to DayOfWeek.WEDNESDAY,
            "thu" to DayOfWeek.THURSDAY, "thursday" to DayOfWeek.THURSDAY,
            "fri" to DayOfWeek.FRIDAY, "friday" to DayOfWeek.FRIDAY,
            "sat" to DayOfWeek.SATURDAY, "saturday" to DayOfWeek.SATURDAY,
            "sun" to DayOfWeek.SUNDAY, "sunday" to DayOfWeek.SUNDAY,
        )
        return normalized.split(',', ';', ' ').mapNotNull { aliases[it.trim()] }.toSet()
    }

    fun parseTime(value: String?): LocalTime? = runCatching { LocalTime.parse(value?.trim().orEmpty()) }.getOrNull()

    fun isWindowActive(
        days: String?,
        startTime: String?,
        endTime: String?,
        nowEpochMs: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): Boolean = QueueScheduleWindow(parseDays(days), parseTime(startTime), parseTime(endTime))
        .contains(LocalDateTime.ofInstant(Instant.ofEpochMilli(nowEpochMs), zoneId))

    private fun hold(reason: QueueHoldReason, title: String, detail: String) = QueueLaunchDecision(
        disposition = QueueLaunchDisposition.Hold,
        reason = reason,
        title = title,
        detail = detail,
    )
}
