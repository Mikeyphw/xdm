package com.mikeyphw.xdm.android.scheduler

import android.content.Context
import androidx.core.content.edit

/** Durable user-facing notification permission history. No permission prompt state is inferred from transient UI. */
class NotificationPermissionStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    val promptRequested: Boolean get() = preferences.getBoolean(KEY_PROMPT_REQUESTED, false)
    val deniedOnce: Boolean get() = preferences.getBoolean(KEY_DENIED_ONCE, false)
    val lastPromptGranted: Boolean? get() = if (preferences.contains(KEY_LAST_GRANTED)) preferences.getBoolean(KEY_LAST_GRANTED, false) else null

    fun recordPromptRequested() {
        preferences.edit { putBoolean(KEY_PROMPT_REQUESTED, true) }
    }

    fun recordPromptResult(granted: Boolean) {
        preferences.edit {
            putBoolean(KEY_PROMPT_REQUESTED, true)
            putBoolean(KEY_LAST_GRANTED, granted)
            putBoolean(KEY_DENIED_ONCE, deniedOnce || !granted)
        }
    }

    companion object {
        private const val PREFERENCES = "xdm_notification_permission_state"
        private const val KEY_PROMPT_REQUESTED = "prompt_requested"
        private const val KEY_LAST_GRANTED = "last_granted"
        private const val KEY_DENIED_ONCE = "denied_once"
    }
}
