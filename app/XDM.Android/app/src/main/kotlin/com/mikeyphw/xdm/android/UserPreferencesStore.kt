package com.mikeyphw.xdm.android

import android.content.Context
import android.os.Build
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mikeyphw.xdm.android.model.ConversionPreset
import com.mikeyphw.xdm.android.model.FilenameConflictPolicy
import com.mikeyphw.xdm.android.model.PostProcessingSettings
import com.mikeyphw.xdm.android.model.ProxyCredentialSettings
import com.mikeyphw.xdm.android.model.SettingsExchangeSnapshot
import com.mikeyphw.xdm.android.storage.DestinationUris
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("xdm_preferences")

data class UserPreferences(
    val lastRoute: AppRoute = AppRoute.Downloads,
    val compactDensity: Boolean = false,
    val destinationUri: String = DestinationUris.PUBLIC_DOWNLOADS,
    val conflictPolicy: FilenameConflictPolicy = FilenameConflictPolicy.Rename,
    val proxySettings: ProxyCredentialSettings = ProxyCredentialSettings(),
    val postProcessingSettings: PostProcessingSettings = PostProcessingSettings(),
)

class UserPreferencesStore(private val context: Context) {
    private object Keys {
        val LastRoute = stringPreferencesKey("last_route")
        val CompactDensity = booleanPreferencesKey("compact_density")
        val DestinationUri = stringPreferencesKey("destination_uri")
        val ConflictPolicy = stringPreferencesKey("filename_conflict_policy")
        val ProxyEnabled = booleanPreferencesKey("proxy_enabled")
        val ProxyHost = stringPreferencesKey("proxy_host")
        val ProxyPort = stringPreferencesKey("proxy_port")
        val ProxyUsername = stringPreferencesKey("proxy_username")
        val ProxyCredentialAlias = stringPreferencesKey("proxy_credential_alias")
        val PostProcessingEnabled = booleanPreferencesKey("post_processing_enabled")
        val ConversionPreset = stringPreferencesKey("conversion_preset")
        val CustomCommandLabel = stringPreferencesKey("custom_command_label")
    }

    val values: Flow<UserPreferences> = context.dataStore.data.map { preferences ->
        UserPreferences(
            lastRoute = preferences[Keys.LastRoute]?.let { runCatching { AppRoute.valueOf(it) }.getOrNull() } ?: AppRoute.Downloads,
            compactDensity = preferences[Keys.CompactDensity] ?: false,
            destinationUri = preferences[Keys.DestinationUri] ?: defaultDestinationUri(),
            conflictPolicy = preferences[Keys.ConflictPolicy]?.let { runCatching { FilenameConflictPolicy.valueOf(it) }.getOrNull() } ?: FilenameConflictPolicy.Rename,
            proxySettings = ProxyCredentialSettings(
                enabled = preferences[Keys.ProxyEnabled] ?: false,
                host = preferences[Keys.ProxyHost].orEmpty(),
                port = preferences[Keys.ProxyPort]?.toIntOrNull()?.takeIf { it in 1..65535 },
                username = preferences[Keys.ProxyUsername].orEmpty(),
                credentialAlias = preferences[Keys.ProxyCredentialAlias].orEmpty(),
            ),
            postProcessingSettings = PostProcessingSettings(
                enabled = preferences[Keys.PostProcessingEnabled] ?: false,
                preset = preferences[Keys.ConversionPreset]?.let { runCatching { ConversionPreset.valueOf(it) }.getOrNull() } ?: ConversionPreset.None,
                customCommandLabel = preferences[Keys.CustomCommandLabel].orEmpty(),
            ),
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

    suspend fun setProxySettings(settings: ProxyCredentialSettings) {
        context.dataStore.edit {
            it[Keys.ProxyEnabled] = settings.enabled
            it[Keys.ProxyHost] = settings.host.trim()
            it[Keys.ProxyPort] = settings.port?.toString().orEmpty()
            it[Keys.ProxyUsername] = settings.username.trim()
            it[Keys.ProxyCredentialAlias] = settings.credentialAlias.trim()
        }
    }

    suspend fun setPostProcessingSettings(settings: PostProcessingSettings) {
        context.dataStore.edit {
            it[Keys.PostProcessingEnabled] = settings.enabled
            it[Keys.ConversionPreset] = settings.preset.name
            it[Keys.CustomCommandLabel] = settings.customCommandLabel.trim()
        }
    }

    suspend fun importSnapshot(snapshot: SettingsExchangeSnapshot) {
        context.dataStore.edit {
            it[Keys.CompactDensity] = snapshot.compactDensity
            if (snapshot.destinationUri.isNotBlank()) it[Keys.DestinationUri] = snapshot.destinationUri
            it[Keys.ConflictPolicy] = snapshot.conflictPolicy.name
            it[Keys.ProxyEnabled] = snapshot.proxy.enabled
            it[Keys.ProxyHost] = snapshot.proxy.host.trim()
            it[Keys.ProxyPort] = snapshot.proxy.port?.toString().orEmpty()
            it[Keys.ProxyUsername] = snapshot.proxy.username.trim()
            it[Keys.ProxyCredentialAlias] = snapshot.proxy.credentialAlias.trim()
            it[Keys.PostProcessingEnabled] = snapshot.postProcessing.enabled
            it[Keys.ConversionPreset] = snapshot.postProcessing.preset.name
            it[Keys.CustomCommandLabel] = snapshot.postProcessing.customCommandLabel.trim()
        }
    }
}
