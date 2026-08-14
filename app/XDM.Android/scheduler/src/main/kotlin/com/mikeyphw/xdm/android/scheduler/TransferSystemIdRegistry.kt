package com.mikeyphw.xdm.android.scheduler

import android.content.Context
import androidx.core.content.edit

/** Durable collision-free Android job/notification/request IDs for download identities. */
class TransferSystemIdRegistry(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun idFor(downloadId: String): Int = synchronized(PROCESS_LOCK) {
        val key = "download.$downloadId"
        val existing = preferences.getInt(key, 0)
        if (existing > 0) return@synchronized existing
        var next = preferences.getInt(KEY_NEXT, FIRST_ID).coerceAtLeast(FIRST_ID)
        val used = preferences.all.values.filterIsInstance<Int>().toSet()
        while (next in used || next in RESERVED) next = if (next >= LAST_ID) FIRST_ID else next + 1
        val following = if (next >= LAST_ID) FIRST_ID else next + 1
        preferences.edit(commit = true) {
            putInt(key, next)
            putInt(KEY_NEXT, following)
        }
        next
    }

    companion object {
        private val PROCESS_LOCK = Any()
        private const val PREFS = "xdm_transfer_system_ids"
        private const val KEY_NEXT = "next"
        private const val FIRST_ID = 20_000
        private const val LAST_ID = 900_000_000
        private val RESERVED = setOf(TransferNotifications.ACTIVE_NOTIFICATION_ID, TransferNotifications.RESTORE_NOTIFICATION_ID)
    }
}
