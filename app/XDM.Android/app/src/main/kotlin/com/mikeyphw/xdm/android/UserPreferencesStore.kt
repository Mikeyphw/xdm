package com.mikeyphw.xdm.android

import android.content.Context
import android.os.Build
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import com.mikeyphw.xdm.android.model.FilenameConflictPolicy
import com.mikeyphw.xdm.android.storage.DestinationUris

private val Context.dataStore by preferencesDataStore("xdm_preferences")

data class UserPreferences(
    val lastRoute: AppRoute = AppRoute.Downloads,
    val compactDensity: Boolean = false,
    val destinationUri: String = DestinationUris.PUBLIC_DOWNLOADS,
    val conflictPolicy: FilenameConflictPolicy = FilenameConflictPolicy.Rename,
)

class UserPreferencesStore(private val context: Context) {
    private object Keys {
        val LastRoute = stringPreferencesKey("last_route")
        val CompactDensity = booleanPreferencesKey("compact_density")
        val DestinationUri = stringPreferencesKey("destination_uri")
        val ConflictPolicy = stringPreferencesKey("filename_conflict_policy")
    }

    val values: Flow<UserPreferences> = context.dataStore.data.map { preferences ->
        UserPreferences(
            lastRoute = preferences[Keys.LastRoute]?.let { runCatching { AppRoute.valueOf(it) }.getOrNull() } ?: AppRoute.Downloads,
            compactDensity = preferences[Keys.CompactDensity] ?: false,
            destinationUri = preferences[Keys.DestinationUri] ?: defaultDestinationUri(),
            conflictPolicy = preferences[Keys.ConflictPolicy]?.let { runCatching { FilenameConflictPolicy.valueOf(it) }.getOrNull() } ?: FilenameConflictPolicy.Rename,
        )
    }

    private fun defaultDestinationUri(): String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) DestinationUris.PUBLIC_DOWNLOADS else DestinationUris.APP_PRIVATE_DOWNLOADS

    suspend fun setRoute(route: AppRoute) {
        context.dataStore.edit { it[Keys.LastRoute] = route.name }
    }

    suspend fun setCompactDensity(compact: Boolean) {
        context.dataStore.edit { it[Keys.CompactDensity] = compact }
    }

    suspend fun setDestination(uri: String) {
        context.dataStore.edit { it[Keys.DestinationUri] = uri }
    }

    suspend fun setConflictPolicy(policy: FilenameConflictPolicy) {
        context.dataStore.edit { it[Keys.ConflictPolicy] = policy.name }
    }
}
