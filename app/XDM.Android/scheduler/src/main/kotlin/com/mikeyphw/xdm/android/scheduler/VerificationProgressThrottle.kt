package com.mikeyphw.xdm.android.scheduler

import com.mikeyphw.xdm.android.model.VerificationRecord
import com.mikeyphw.xdm.android.model.VerificationStatus

/** Bounds durable verification writes while keeping start/terminal checkpoints immediate. */
internal class VerificationProgressThrottle(
    private val minIntervalMillis: Long = 350L,
    private val maxIntervalMillis: Long = 1_000L,
    private val minBytesDelta: Long = 4L * 1024L * 1024L,
) {
    private var lastPersistedAt: Long? = null
    private var lastPersistedBytes: Long = 0L

    fun shouldPersist(record: VerificationRecord): Boolean {
        if (record.status != VerificationStatus.Running || record.bytesVerified == 0L) {
            mark(record)
            return true
        }
        val previousAt = lastPersistedAt
        if (previousAt == null) {
            mark(record)
            return true
        }
        val elapsed = (record.updatedAtEpochMs - previousAt).coerceAtLeast(0L)
        val bytesDelta = (record.bytesVerified - lastPersistedBytes).coerceAtLeast(0L)
        val persist = elapsed >= maxIntervalMillis || (elapsed >= minIntervalMillis && bytesDelta >= minBytesDelta)
        if (persist) mark(record)
        return persist
    }

    private fun mark(record: VerificationRecord) {
        lastPersistedAt = record.updatedAtEpochMs
        lastPersistedBytes = record.bytesVerified
    }
}
