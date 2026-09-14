package com.mikeyphw.xdm.android.scheduler

import android.content.Context
import androidx.core.content.edit

/**
 * Process-independent startup/boot/package-restore lease for the scheduler recovery pipeline.
 *
 * XAR09: Application startup, BOOT_COMPLETED and package replacement can all arrive close
 * together. Only one owner may run durable backend recovery and unblock queue admission. A stale
 * owner may be superseded only after [LEASE_TTL_MS], preventing duplicate restore pipelines while
 * still allowing recovery from a killed process.
 */
class SchedulerRecoveryLeaseCoordinator(context: Context, private val clock: () -> Long = System::currentTimeMillis) {
    data class Lease(val owner: String, val epoch: Long, val acquiredAtEpochMs: Long) {
        val token: String get() = "$owner:$epoch"
    }

    private val preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun tryAcquire(owner: String, epoch: Long = clock()): Lease? = synchronized(LOCK) {
        val now = clock()
        val activeOwner = preferences.getString(KEY_OWNER, null)
        val activeEpoch = preferences.getLong(KEY_EPOCH, 0L)
        val activeAt = preferences.getLong(KEY_ACQUIRED_AT, 0L)
        val activeFresh = !activeOwner.isNullOrBlank() && activeEpoch > 0L && now - activeAt < LEASE_TTL_MS
        if (activeFresh) return@synchronized null
        preferences.edit(commit = true) {
            putString(KEY_OWNER, owner)
            putLong(KEY_EPOCH, epoch.coerceAtLeast(1L))
            putLong(KEY_ACQUIRED_AT, now)
        }
        Lease(owner, epoch.coerceAtLeast(1L), now)
    }

    fun release(lease: Lease, message: String = "complete"): Boolean = synchronized(LOCK) {
        val matches = preferences.getString(KEY_OWNER, null) == lease.owner && preferences.getLong(KEY_EPOCH, 0L) == lease.epoch
        if (!matches) return@synchronized false
        preferences.edit(commit = true) {
            putString(KEY_LAST_RELEASE, message.take(256))
            putLong(KEY_LAST_RELEASED_AT, clock())
            remove(KEY_OWNER)
            remove(KEY_EPOCH)
            remove(KEY_ACQUIRED_AT)
        }
        true
    }

    fun activeLeaseToken(nowEpochMs: Long = clock()): String? = synchronized(LOCK) {
        val owner = preferences.getString(KEY_OWNER, null)?.takeIf(String::isNotBlank) ?: return@synchronized null
        val epoch = preferences.getLong(KEY_EPOCH, 0L).takeIf { it > 0L } ?: return@synchronized null
        val acquiredAt = preferences.getLong(KEY_ACQUIRED_AT, 0L)
        if (nowEpochMs - acquiredAt >= LEASE_TTL_MS) null else "$owner:$epoch"
    }

    companion object {
        private val LOCK = Any()
        private const val PREFS = "xdm_scheduler_recovery_lease"
        private const val KEY_OWNER = "owner"
        private const val KEY_EPOCH = "epoch"
        private const val KEY_ACQUIRED_AT = "acquired_at"
        private const val KEY_LAST_RELEASE = "last_release"
        private const val KEY_LAST_RELEASED_AT = "last_released_at"
        internal const val LEASE_TTL_MS = 10 * 60 * 1000L
    }
}
