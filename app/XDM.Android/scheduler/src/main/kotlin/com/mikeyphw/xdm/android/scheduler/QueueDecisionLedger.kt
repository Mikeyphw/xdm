package com.mikeyphw.xdm.android.scheduler

import android.content.Context
import com.mikeyphw.xdm.android.model.Download
import com.mikeyphw.xdm.android.model.QueueDecisionEvent
import com.mikeyphw.xdm.android.model.QueueHoldReason
import com.mikeyphw.xdm.android.model.QueueLaunchDecision
import com.mikeyphw.xdm.android.model.QueueLaunchDisposition
import org.json.JSONArray
import org.json.JSONObject

/** Small browser-free activity ledger for explainable queue decisions. */
class QueueDecisionLedger(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    @Synchronized
    fun record(download: Download, decision: QueueLaunchDecision, createdAtEpochMs: Long): QueueDecisionEvent {
        val event = QueueDecisionEvent(
            id = "${download.id}:$createdAtEpochMs:${decision.disposition.name}",
            downloadId = download.id,
            fileName = download.fileName,
            disposition = decision.disposition,
            reason = decision.reason,
            title = decision.title,
            detail = decision.detail,
            createdAtEpochMs = createdAtEpochMs,
            nextEligibleAtEpochMs = decision.nextEligibleAtEpochMs,
            policyOverridden = decision.policyOverridden,
        )
        val existing = readAll().toMutableList()
        val latestForDownload = existing.firstOrNull { it.downloadId == event.downloadId }
        val duplicate = latestForDownload?.takeIf { previous ->
            previous.disposition == event.disposition &&
                previous.reason == event.reason &&
                previous.detail == event.detail
        }
        if (duplicate != null) return duplicate
        existing.add(0, event)
        writeAll(existing.take(MAX_EVENTS))
        return event
    }

    @Synchronized
    fun recent(limit: Int = 12): List<QueueDecisionEvent> = readAll().take(limit.coerceIn(0, MAX_EVENTS))

    @Synchronized
    fun clearDownload(downloadId: String) {
        writeAll(readAll().filterNot { it.downloadId == downloadId })
    }

    private fun readAll(): List<QueueDecisionEvent> {
        val raw = preferences.getString(KEY_EVENTS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    array.optJSONObject(index)?.toEventOrNull()?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun writeAll(events: List<QueueDecisionEvent>) {
        val array = JSONArray()
        events.forEach { array.put(it.toJson()) }
        preferences.edit().putString(KEY_EVENTS, array.toString()).apply()
    }

    private fun QueueDecisionEvent.toJson() = JSONObject().apply {
        put("id", id)
        put("downloadId", downloadId)
        put("fileName", fileName)
        put("disposition", disposition.name)
        reason?.let { put("reason", it.name) }
        put("title", title)
        put("detail", detail)
        put("createdAtEpochMs", createdAtEpochMs)
        nextEligibleAtEpochMs?.let { put("nextEligibleAtEpochMs", it) }
        put("policyOverridden", policyOverridden)
    }

    private fun JSONObject.toEventOrNull(): QueueDecisionEvent? = runCatching {
        QueueDecisionEvent(
            id = getString("id"),
            downloadId = getString("downloadId"),
            fileName = optString("fileName", "Download"),
            disposition = QueueLaunchDisposition.valueOf(getString("disposition")),
            reason = optString("reason").takeIf(String::isNotBlank)?.let(QueueHoldReason::valueOf),
            title = getString("title"),
            detail = getString("detail"),
            createdAtEpochMs = getLong("createdAtEpochMs"),
            nextEligibleAtEpochMs = if (has("nextEligibleAtEpochMs")) getLong("nextEligibleAtEpochMs") else null,
            policyOverridden = optBoolean("policyOverridden", false),
        )
    }.getOrNull()

    companion object {
        private const val PREFERENCES = "xdm_queue_decision_ledger"
        private const val KEY_EVENTS = "events"
        private const val MAX_EVENTS = 60
    }
}
