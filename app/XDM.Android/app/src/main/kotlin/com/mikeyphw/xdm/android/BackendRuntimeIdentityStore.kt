package com.mikeyphw.xdm.android

import android.content.Context
import com.mikeyphw.xdm.android.model.BackendRuntimeIdentity
import com.mikeyphw.xdm.android.model.BackendType
import java.util.Locale
import java.util.UUID

/**
 * Keeps one stable installation identity per backend while rotating the session identity on each
 * application process start. Ownership records persist both values so stale processes and sessions
 * can never be mistaken for the current backend runtime.
 */
class BackendRuntimeIdentityStore(context: Context) {
    private val preferences = context.getSharedPreferences("backend-runtime-identities", Context.MODE_PRIVATE)
    private val sessionIds = mutableMapOf<BackendType, String>()

    @Synchronized
    fun identityFor(backend: BackendType): BackendRuntimeIdentity {
        require(backend != BackendType.Automatic) { "Automatic is a selection policy, not a backend runtime" }
        val key = "${backend.name.lowercase(Locale.ROOT)}-instance"
        val instanceId = preferences.getString(key, null) ?: UUID.randomUUID().toString().also { generated ->
            check(preferences.edit().putString(key, generated).commit()) { "Could not persist backend instance identity" }
        }
        val sessionId = sessionIds.getOrPut(backend) { UUID.randomUUID().toString() }
        return BackendRuntimeIdentity(instanceId, sessionId)
    }
}
