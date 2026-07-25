package com.mikeyphw.xdm.android

import android.content.Context
import com.mikeyphw.xdm.android.model.Download
import com.mikeyphw.xdm.android.model.DownloadState
import com.mikeyphw.xdm.android.model.OperationalActivityCategory
import com.mikeyphw.xdm.android.model.OperationalActivityEvent
import com.mikeyphw.xdm.android.model.OperationalActivitySeverity
import com.mikeyphw.xdm.android.model.PrivacyDiagnosticsRedactor
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Small browser-free activity ledger. It records transfer state transitions only and keeps
 * download records in Room as the source of truth. Clearing this ledger never removes a transfer.
 */
data class OperationalActivityStoreSnapshot(
    val events: List<OperationalActivityEvent> = emptyList(),
    val dismissedEventIds: Set<String> = emptySet(),
)

class OperationalActivityStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val _snapshot = MutableStateFlow(readSnapshot())
    val snapshot: StateFlow<OperationalActivityStoreSnapshot> = _snapshot

    @Synchronized
    fun observeDownloads(downloads: List<Download>, observedAtEpochMs: Long = System.currentTimeMillis()) {
        val previous = readFingerprints().toMutableMap()
        if (previous.isEmpty()) {
            writeFingerprints(downloads.associate { it.id to it.fingerprint() })
            return
        }

        val events = readEvents().toMutableList()
        downloads.forEach { download ->
            val old = previous[download.id]
            val current = download.fingerprint()
            if (old == null) {
                events.add(0, download.toEvent(previousState = null, observedAtEpochMs = observedAtEpochMs))
            } else if (old != current) {
                events.replaceAll { event ->
                    if (event.downloadId == download.id && event.unresolved && download.state !in unresolvedStates) {
                        event.copy(unresolved = false)
                    } else {
                        event
                    }
                }
                events.add(0, download.toEvent(previousState = old.substringBefore('|'), observedAtEpochMs = observedAtEpochMs))
            }
            previous[download.id] = current
        }
        previous.keys.retainAll(downloads.mapTo(mutableSetOf(), Download::id))
        writeFingerprints(previous)
        writeEvents(prune(events, observedAtEpochMs))
        publish()
    }

    @Synchronized
    fun dismiss(eventId: String) {
        val dismissed = readDismissed().toMutableSet()
        dismissed += eventId
        writeDismissed(dismissed.toList().takeLast(MAX_DISMISSED).toSet())
        publish()
    }

    @Synchronized
    fun clearHistory(preserveUnresolved: Boolean = true) {
        val kept = if (preserveUnresolved) readEvents().filter(OperationalActivityEvent::unresolved) else emptyList()
        writeEvents(kept)
        writeDismissed(emptySet())
        publish()
    }

    private fun publish() {
        _snapshot.value = OperationalActivityStoreSnapshot(readEvents(), readDismissed())
    }

    private fun readSnapshot() = OperationalActivityStoreSnapshot(readEvents(), readDismissed())

    private fun Download.fingerprint(): String = listOf(
        state.name,
        backend.name,
        errorMessage?.let(::stableHash).orEmpty(),
    ).joinToString("|")

    private fun Download.toEvent(previousState: String?, observedAtEpochMs: Long): OperationalActivityEvent {
        val safeError = PrivacyDiagnosticsRedactor.redactText(errorMessage)
        val detail = when (state) {
            DownloadState.Created -> "Created and waiting for review."
            DownloadState.Queued -> "Queued for execution${previousState?.let { " after $it" }.orEmpty()}."
            DownloadState.Connecting -> "Connecting with the ${backend.name} engine."
            DownloadState.Downloading -> "Transfer started with the ${backend.name} engine."
            DownloadState.Paused -> safeError ?: "Transfer paused."
            DownloadState.WaitingForNetwork -> safeError ?: "Waiting for network conditions."
            DownloadState.WaitingForPower -> safeError ?: "Waiting for power conditions."
            DownloadState.Verifying -> "Verifying downloaded data."
            DownloadState.Repairing -> "Repairing untrusted or missing ranges."
            DownloadState.Finalizing -> "Promoting the completed artifact into its destination."
            DownloadState.Completed -> "Download completed and finalization passed."
            DownloadState.Failed -> safeError ?: "The transfer failed and needs review."
            DownloadState.Cancelled -> "Transfer cancelled."
            DownloadState.RecoveryRequired -> safeError ?: "Interrupted transfer requires recovery review."
        }
        val category = when (state) {
            DownloadState.WaitingForNetwork -> OperationalActivityCategory.Network
            DownloadState.Verifying -> OperationalActivityCategory.Verification
            DownloadState.Repairing,
            DownloadState.RecoveryRequired -> OperationalActivityCategory.Recovery
            else -> OperationalActivityCategory.Transfer
        }
        val severity = when (state) {
            DownloadState.Completed -> OperationalActivitySeverity.Success
            DownloadState.Failed,
            DownloadState.RecoveryRequired -> OperationalActivitySeverity.Error
            DownloadState.WaitingForNetwork,
            DownloadState.WaitingForPower,
            DownloadState.Paused -> OperationalActivitySeverity.Warning
            else -> OperationalActivitySeverity.Info
        }
        val action = when (state) {
            DownloadState.Failed -> "Retry now"
            DownloadState.RecoveryRequired -> "Open recovery"
            DownloadState.WaitingForNetwork,
            DownloadState.WaitingForPower -> "Start anyway"
            else -> null
        }
        val timestamp = updatedAtEpochMs.takeIf { it > 0 } ?: observedAtEpochMs
        return OperationalActivityEvent(
            id = "transfer:$id:${state.name}:$timestamp",
            downloadId = id,
            fileName = fileName,
            category = category,
            severity = severity,
            title = state.title,
            detail = detail,
            engine = backend.name,
            actionLabel = action,
            createdAtEpochMs = timestamp,
            unresolved = state in unresolvedStates,
            source = "transfer-state",
        )
    }

    private val DownloadState.title: String
        get() = when (this) {
            DownloadState.Created -> "Download added"
            DownloadState.Queued -> "Download queued"
            DownloadState.Connecting -> "Connecting"
            DownloadState.Downloading -> "Download started"
            DownloadState.Paused -> "Download paused"
            DownloadState.WaitingForNetwork -> "Waiting for network"
            DownloadState.WaitingForPower -> "Waiting for power"
            DownloadState.Verifying -> "Verification started"
            DownloadState.Repairing -> "Repair started"
            DownloadState.Finalizing -> "Finalizing download"
            DownloadState.Completed -> "Download completed"
            DownloadState.Failed -> "Download failed"
            DownloadState.Cancelled -> "Download cancelled"
            DownloadState.RecoveryRequired -> "Recovery required"
        }

    private fun prune(events: List<OperationalActivityEvent>, nowEpochMs: Long): List<OperationalActivityEvent> {
        val cutoff = nowEpochMs - RETENTION_MS
        val unresolved = events.filter(OperationalActivityEvent::unresolved)
        val recentResolved = events.filterNot(OperationalActivityEvent::unresolved).filter { it.createdAtEpochMs >= cutoff }
        return (unresolved + recentResolved)
            .distinctBy(OperationalActivityEvent::id)
            .sortedByDescending(OperationalActivityEvent::createdAtEpochMs)
            .take(MAX_EVENTS)
    }

    private fun readEvents(): List<OperationalActivityEvent> {
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

    private fun writeEvents(events: List<OperationalActivityEvent>) {
        val array = JSONArray()
        events.forEach { array.put(it.toJson()) }
        preferences.edit().putString(KEY_EVENTS, array.toString()).apply()
    }

    private fun readFingerprints(): Map<String, String> {
        val raw = preferences.getString(KEY_FINGERPRINTS, null) ?: return emptyMap()
        return runCatching {
            val json = JSONObject(raw)
            buildMap {
                val keys = json.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    put(key, json.optString(key))
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun writeFingerprints(values: Map<String, String>) {
        val json = JSONObject()
        values.forEach(json::put)
        preferences.edit().putString(KEY_FINGERPRINTS, json.toString()).apply()
    }

    private fun readDismissed(): Set<String> = preferences.getStringSet(KEY_DISMISSED, emptySet()).orEmpty().toSet()

    private fun writeDismissed(values: Set<String>) {
        preferences.edit().putStringSet(KEY_DISMISSED, values).apply()
    }

    private fun OperationalActivityEvent.toJson() = JSONObject().apply {
        put("id", id)
        downloadId?.let { put("downloadId", it) }
        fileName?.let { put("fileName", it) }
        put("category", category.name)
        put("severity", severity.name)
        put("title", title)
        put("detail", detail)
        engine?.let { put("engine", it) }
        actionLabel?.let { put("actionLabel", it) }
        put("createdAtEpochMs", createdAtEpochMs)
        put("unresolved", unresolved)
        put("source", source)
        nextEligibleAtEpochMs?.let { put("nextEligibleAtEpochMs", it) }
    }

    private fun JSONObject.toEventOrNull(): OperationalActivityEvent? = runCatching {
        OperationalActivityEvent(
            id = getString("id"),
            downloadId = optString("downloadId").takeIf(String::isNotBlank),
            fileName = optString("fileName").takeIf(String::isNotBlank),
            category = OperationalActivityCategory.valueOf(getString("category")),
            severity = OperationalActivitySeverity.valueOf(getString("severity")),
            title = getString("title"),
            detail = getString("detail"),
            engine = optString("engine").takeIf(String::isNotBlank),
            actionLabel = optString("actionLabel").takeIf(String::isNotBlank),
            createdAtEpochMs = getLong("createdAtEpochMs"),
            unresolved = optBoolean("unresolved", false),
            source = optString("source", "runtime"),
            nextEligibleAtEpochMs = if (has("nextEligibleAtEpochMs")) getLong("nextEligibleAtEpochMs") else null,
        )
    }.getOrNull()

    private fun stableHash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
        .take(16)

    companion object {
        private const val PREFERENCES = "xdm_operational_activity"
        private const val KEY_EVENTS = "events"
        private const val KEY_FINGERPRINTS = "download_fingerprints"
        private const val KEY_DISMISSED = "dismissed_event_ids"
        private const val MAX_EVENTS = 300
        private const val MAX_DISMISSED = 300
        private const val RETENTION_MS = 30L * 24L * 60L * 60L * 1_000L
        private val unresolvedStates = setOf(
            DownloadState.Failed,
            DownloadState.RecoveryRequired,
            DownloadState.WaitingForNetwork,
            DownloadState.WaitingForPower,
        )
    }
}
