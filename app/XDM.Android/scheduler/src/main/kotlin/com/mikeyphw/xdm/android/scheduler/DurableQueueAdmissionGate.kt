package com.mikeyphw.xdm.android.scheduler

import android.content.Context
import androidx.core.content.edit

data class DurableQueueAdmissionHold(
    val reason: String,
    val generation: Long,
    val createdAtEpochMs: Long,
)

/** Process-independent queue admission gate for startup recovery and explicit Pause All. */
class DurableQueueAdmissionGate(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun currentHold(): DurableQueueAdmissionHold? {
        val reason = preferences.getString(KEY_REASON, null)?.takeIf(String::isNotBlank) ?: return null
        return DurableQueueAdmissionHold(
            reason = reason,
            generation = preferences.getLong(KEY_GENERATION, 0L),
            createdAtEpochMs = preferences.getLong(KEY_CREATED_AT, 0L),
        )
    }

    fun installPauseAll(generation: Long = System.currentTimeMillis(), nowEpochMs: Long = System.currentTimeMillis()) =
        install(REASON_PAUSE_ALL, generation, nowEpochMs)

    fun installStartupRecovery(generation: Long = System.currentTimeMillis(), nowEpochMs: Long = System.currentTimeMillis()) =
        install(REASON_STARTUP_RECOVERY, generation, nowEpochMs)

    fun clearPauseAll() = clearOnly(REASON_PAUSE_ALL)
    fun clearStartupRecovery() = clearOnly(REASON_STARTUP_RECOVERY)

    private fun install(reason: String, generation: Long, nowEpochMs: Long) {
        // Pause All has higher authority than a transient startup hold and must never be overwritten.
        if (reason == REASON_STARTUP_RECOVERY && currentHold()?.reason == REASON_PAUSE_ALL) return
        preferences.edit(commit = true) {
            putString(KEY_REASON, reason)
            putLong(KEY_GENERATION, generation)
            putLong(KEY_CREATED_AT, nowEpochMs)
        }
    }

    private fun clearOnly(reason: String) {
        if (currentHold()?.reason != reason) return
        preferences.edit(commit = true) {
            remove(KEY_REASON)
            remove(KEY_GENERATION)
            remove(KEY_CREATED_AT)
        }
    }

    companion object {
        const val REASON_PAUSE_ALL = "pause-all"
        const val REASON_STARTUP_RECOVERY = "startup-recovery"
        private const val PREFS = "xdm_durable_queue_admission"
        private const val KEY_REASON = "hold.reason"
        private const val KEY_GENERATION = "hold.generation"
        private const val KEY_CREATED_AT = "hold.createdAt"
    }
}
