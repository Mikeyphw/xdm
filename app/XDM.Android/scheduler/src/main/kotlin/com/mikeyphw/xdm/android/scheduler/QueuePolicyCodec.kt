package com.mikeyphw.xdm.android.scheduler

import com.mikeyphw.xdm.android.model.QueueDefinition
import com.mikeyphw.xdm.android.model.QueueExecutionPolicy
import com.mikeyphw.xdm.android.model.QueueIntelligencePlanner
import com.mikeyphw.xdm.android.model.QueueNetworkRequirement
import com.mikeyphw.xdm.android.model.QueueResolvedSchedule
import com.mikeyphw.xdm.android.model.QueueRetryStrategy
import com.mikeyphw.xdm.android.model.ScheduleRule
import org.json.JSONObject

internal object QueuePolicyCodec {
    fun resolve(
        queue: QueueDefinition,
        rules: List<ScheduleRule>,
        nowEpochMs: Long,
    ): QueueResolvedSchedule {
        val specific = rules.filter { it.enabled && it.queueId == queue.id }
        val global = rules.filter { it.enabled && it.queueId == null }
        val applicable = specific.ifEmpty { global }
        if (applicable.isEmpty()) {
            return QueueResolvedSchedule(defaultPolicy(queue), hasApplicableRules = false)
        }
        val ordered = applicable.sortedWith(compareByDescending<ScheduleRule> { it.queueId == queue.id }.thenBy { it.name.lowercase() }.thenBy { it.id })
        val active = ordered.firstOrNull { rule ->
            val json = parse(rule.constraintsJson)
            QueueIntelligencePlanner.isWindowActive(
                days = json.optString("days", "Every day"),
                startTime = json.optString("startTime", ""),
                endTime = json.optString("endTime", ""),
                nowEpochMs = nowEpochMs,
            )
        }
        val selected = active ?: ordered.first()
        return QueueResolvedSchedule(
            policy = decode(queue, selected.constraintsJson),
            hasApplicableRules = true,
            activeRuleName = active?.name,
            nextWindowSummary = if (active == null) windowSummary(selected.constraintsJson) else null,
        )
    }

    fun decode(queue: QueueDefinition, constraintsJson: String): QueueExecutionPolicy {
        val json = parse(constraintsJson)
        val network = when {
            json.optBoolean("wifiOnly", false) -> QueueNetworkRequirement.Wifi
            json.optBoolean("unmetered", false) || json.optBoolean("unmeteredOnly", false) -> QueueNetworkRequirement.Unmetered
            else -> QueueIntelligencePlanner.parseNetworkRequirement(json.optString("networkRequirement", "any"))
        }
        return QueueExecutionPolicy(
            networkRequirement = network,
            chargingRequired = json.optBoolean("charging", json.optBoolean("requiresCharging", false)),
            minimumBatteryPercent = json.optIntOrNull("minimumBatteryPercent")?.coerceIn(1, 100),
            stopOnStoragePressure = json.optBoolean("stopOnStoragePressure", true),
            minimumFreeStorageBytes = json.optLongOrNull("minimumFreeStorageMb")
                ?.coerceIn(128L, 16_384L)
                ?.times(1024L * 1024L)
                ?: QueueExecutionPolicy.DEFAULT_STORAGE_RESERVE_BYTES,
            retryStrategy = QueueIntelligencePlanner.parseRetryStrategy(json.optString("retryStrategy", "balanced")),
            maxAutoRetries = json.optInt("maxAutoRetries", 4).coerceIn(0, 12),
            maxConcurrent = queue.maxConcurrent.coerceIn(1, 16),
        )
    }

    fun windowSummary(constraintsJson: String): String {
        val json = parse(constraintsJson)
        val days = json.optString("days", "Every day")
        val start = json.optString("startTime", "")
        val end = json.optString("endTime", "")
        return if (start.isBlank() || end.isBlank()) days else "$days • $start–$end"
    }

    private fun defaultPolicy(queue: QueueDefinition) = QueueExecutionPolicy(maxConcurrent = queue.maxConcurrent.coerceIn(1, 16))

    private fun parse(value: String): JSONObject = runCatching { JSONObject(value.ifBlank { "{}" }) }.getOrElse { JSONObject() }

    private fun JSONObject.optIntOrNull(key: String): Int? = if (has(key) && !isNull(key)) optInt(key) else null
    private fun JSONObject.optLongOrNull(key: String): Long? = if (has(key) && !isNull(key)) optLong(key) else null
}
