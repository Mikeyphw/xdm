package com.mikeyphw.xdm.android.scheduler

import android.content.Context
import com.mikeyphw.xdm.android.model.Download
import com.mikeyphw.xdm.android.model.QueueIntelligencePlanner
import com.mikeyphw.xdm.android.model.QueueRetryRecord
import com.mikeyphw.xdm.android.model.QueueRetryStrategy

class QueueRetryLedger(context: Context) {
    private val preferences = context.getSharedPreferences("xdm_queue_retry_ledger", Context.MODE_PRIVATE)

    fun observeFailure(download: Download, strategy: QueueRetryStrategy): QueueRetryRecord {
        val prefix = download.id + "."
        val recordedFailureAt = preferences.getLong(prefix + "failureAt", -1L)
        if (recordedFailureAt == download.updatedAtEpochMs) {
            return QueueRetryRecord(
                attempt = preferences.getInt(prefix + "attempt", 1),
                lastFailureAtEpochMs = recordedFailureAt,
                nextRetryAtEpochMs = preferences.getLong(prefix + "next", recordedFailureAt),
            )
        }
        val previousAttempt = preferences.getInt(prefix + "attempt", 0)
        val record = QueueIntelligencePlanner.retryRecord(strategy, previousAttempt, download.updatedAtEpochMs)
        preferences.edit()
            .putInt(prefix + "attempt", record.attempt)
            .putLong(prefix + "failureAt", record.lastFailureAtEpochMs)
            .putLong(prefix + "next", record.nextRetryAtEpochMs)
            .apply()
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

    fun clear(downloadId: String) {
        val prefix = downloadId + "."
        preferences.edit().remove(prefix + "attempt").remove(prefix + "failureAt").remove(prefix + "next").apply()
    }
}
