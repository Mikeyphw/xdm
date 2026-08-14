package com.mikeyphw.xdm.android.scheduler

import android.content.Context
import androidx.core.content.edit
import com.mikeyphw.xdm.android.model.Download
import com.mikeyphw.xdm.android.model.QueueIntelligencePlanner
import com.mikeyphw.xdm.android.model.QueueRetryRecord
import com.mikeyphw.xdm.android.model.QueueRetryStrategy
import java.security.MessageDigest

class QueueRetryLedger(context: Context) {
    private val preferences = context.getSharedPreferences("xdm_queue_retry_ledger", Context.MODE_PRIVATE)

    fun observeFailure(download: Download, strategy: QueueRetryStrategy, secureContextPresent: Boolean): QueueRetryRecord {
        val prefix = download.id + "."
        val identity = failureIdentity(download)
        if (preferences.getString(prefix + "identity", null) == identity) return requireNotNull(get(download.id))
        val previousAttempt = preferences.getInt(prefix + "attempt", 0)
        val failureAt = download.updatedAtEpochMs.coerceAtLeast(1L)
        val record = QueueIntelligencePlanner.retryRecord(strategy, previousAttempt, failureAt)
        preferences.edit(commit = true) {
            putString(prefix + "identity", identity)
            putInt(prefix + "attempt", record.attempt)
            putLong(prefix + "failureAt", record.lastFailureAtEpochMs)
            putLong(prefix + "next", record.nextRetryAtEpochMs)
            putBoolean(prefix + "secureRequired", secureContextPresent || preferences.getBoolean(prefix + "secureRequired", false))
        }
        return record
    }

    fun get(downloadId: String): QueueRetryRecord? {
        val prefix = downloadId + "."
        val failureAt = preferences.getLong(prefix + "failureAt", -1L)
        if (failureAt < 0L) return null
        return QueueRetryRecord(
            attempt = preferences.getInt(prefix + "attempt", 0),
            lastFailureAtEpochMs = failureAt,
            nextRetryAtEpochMs = preferences.getLong(prefix + "next", failureAt),
        )
    }

    fun requiresSecureContext(downloadId: String): Boolean = preferences.getBoolean(downloadId + ".secureRequired", false)

    fun recordHold(downloadId: String, reason: String, detail: String) {
        val prefix = downloadId + "."
        preferences.edit(commit = true) {
            putString(prefix + "holdReason", reason)
            putString(prefix + "holdDetail", detail.take(512))
        }
    }

    fun clear(downloadId: String) {
        val prefix = downloadId + "."
        val keys = preferences.all.keys.filter { it.startsWith(prefix) }
        preferences.edit(commit = true) { keys.forEach(::remove) }
    }

    private fun failureIdentity(download: Download): String {
        val material = "${download.attemptGeneration}|${download.errorMessage.orEmpty().trim()}"
        return MessageDigest.getInstance("SHA-256").digest(material.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
