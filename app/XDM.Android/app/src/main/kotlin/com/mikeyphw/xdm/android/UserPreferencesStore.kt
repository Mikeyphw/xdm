package com.mikeyphw.xdm.android

import android.content.Context
import android.os.Build
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mikeyphw.xdm.android.model.ConversionPreset
import com.mikeyphw.xdm.android.model.FilenameConflictPolicy
import com.mikeyphw.xdm.android.model.PostProcessingSettings
import com.mikeyphw.xdm.android.model.ProxyCredentialSettings
import com.mikeyphw.xdm.android.model.SettingsExchangeSnapshot
import com.mikeyphw.xdm.android.browserextension.BrowserExtensionSourceContract
import com.mikeyphw.xdm.android.storage.DestinationUris
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class XdmThemeMode(val label: String) {
    Dark("Dark"),
    Amoled("AMOLED black"),
}

private val Context.dataStore by preferencesDataStore("xdm_preferences")

data class UserPreferences(
    val lastRoute: AppRoute = AppRoute.Downloads,
    val compactDensity: Boolean = false,
    val themeMode: XdmThemeMode = XdmThemeMode.Dark,
    val developerOptionsEnabled: Boolean = false,
    val destinationUri: String = DestinationUris.PUBLIC_DOWNLOADS,
    val conflictPolicy: FilenameConflictPolicy = FilenameConflictPolicy.Rename,
    val proxySettings: ProxyCredentialSettings = ProxyCredentialSettings(),
    val postProcessingSettings: PostProcessingSettings = PostProcessingSettings(),
    val browserExtension: BrowserExtensionExportPreferences = BrowserExtensionExportPreferences(),
)

class UserPreferencesStore(private val context: Context) {
    private object Keys {
        val LastRoute = stringPreferencesKey("last_route")
        val CompactDensity = booleanPreferencesKey("compact_density")
        val ThemeMode = stringPreferencesKey("theme_mode")
        val DeveloperOptionsEnabled = booleanPreferencesKey("developer_options_enabled")
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
        val BrowserExtensionExportTreeUri = stringPreferencesKey("browser_extension_export_tree_uri")
        val BrowserExtensionDefaultTarget = stringPreferencesKey("browser_extension_default_target")
        val BrowserExtensionRequestedTheme = stringPreferencesKey("browser_extension_requested_theme")
        val BrowserExtensionLastExportTheme = stringPreferencesKey("browser_extension_last_export_theme")
        val BrowserExtensionLastExportAppVersion = stringPreferencesKey("browser_extension_last_export_app_version")
        val BrowserExtensionLastExportExtensionVersion = stringPreferencesKey("browser_extension_last_export_extension_version")
        val BrowserExtensionLastExportSha256 = stringPreferencesKey("browser_extension_last_export_sha256")
        val BrowserExtensionLastExportEpochMs = longPreferencesKey("browser_extension_last_export_epoch_ms")
        val BrowserExtensionLastExportFileName = stringPreferencesKey("browser_extension_last_export_file_name")
        val BrowserExtensionLastExportByteCount = longPreferencesKey("browser_extension_last_export_byte_count")
    }

    val values: Flow<UserPreferences> = context.dataStore.data.map { preferences ->
        UserPreferences(
            lastRoute = AppRoute.restore(preferences[Keys.LastRoute]),
            compactDensity = preferences[Keys.CompactDensity] ?: false,
            themeMode = preferences[Keys.ThemeMode]
                ?.let { runCatching { XdmThemeMode.valueOf(it) }.getOrNull() }
                ?: XdmThemeMode.Dark,
            developerOptionsEnabled = preferences[Keys.DeveloperOptionsEnabled] ?: false,
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
            browserExtension = BrowserExtensionExportPreferences(
                exportTreeUri = preferences[Keys.BrowserExtensionExportTreeUri].orEmpty(),
                defaultTarget = preferences[Keys.BrowserExtensionDefaultTarget]
                    ?.let { stored -> BrowserExtensionSourceContract.Target.entries.firstOrNull { it.wireValue == stored } }
                    ?: BrowserExtensionSourceContract.Target.Xdm,
                requestedTheme = preferences[Keys.BrowserExtensionRequestedTheme]
                    ?.let { stored -> BrowserExtensionSourceContract.ThemeMode.entries.firstOrNull { it.wireValue == stored } }
                    ?: BrowserExtensionSourceContract.ThemeMode.Dark,
                lastExportTheme = preferences[Keys.BrowserExtensionLastExportTheme]
                    ?.let { stored -> BrowserExtensionSourceContract.ThemeMode.entries.firstOrNull { it.wireValue == stored } },
                lastExportAppVersion = preferences[Keys.BrowserExtensionLastExportAppVersion].orEmpty(),
                lastExportExtensionVersion = preferences[Keys.BrowserExtensionLastExportExtensionVersion].orEmpty(),
                lastExportSha256 = preferences[Keys.BrowserExtensionLastExportSha256].orEmpty(),
                lastExportEpochMs = preferences[Keys.BrowserExtensionLastExportEpochMs] ?: 0L,
                lastExportFileName = preferences[Keys.BrowserExtensionLastExportFileName].orEmpty(),
                lastExportByteCount = preferences[Keys.BrowserExtensionLastExportByteCount] ?: 0L,
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

    suspend fun setThemeMode(mode: XdmThemeMode) {
        context.dataStore.edit { it[Keys.ThemeMode] = mode.name }
    }

    suspend fun setDeveloperOptionsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DeveloperOptionsEnabled] = enabled }
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


    suspend fun setBrowserExtensionExportTreeUri(uri: String) {
        context.dataStore.edit { it[Keys.BrowserExtensionExportTreeUri] = uri.trim() }
    }

    suspend fun setBrowserExtensionDefaultTarget(target: BrowserExtensionSourceContract.Target) {
        context.dataStore.edit { it[Keys.BrowserExtensionDefaultTarget] = target.wireValue }
    }

    suspend fun setBrowserExtensionRequestedTheme(theme: BrowserExtensionSourceContract.ThemeMode) {
        context.dataStore.edit { it[Keys.BrowserExtensionRequestedTheme] = theme.wireValue }
    }

    suspend fun recordBrowserExtensionExport(
        theme: BrowserExtensionSourceContract.ThemeMode,
        appVersion: String,
        extensionVersion: String,
        sha256: String,
        epochMs: Long,
        fileName: String,
        byteCount: Long,
    ) {
        context.dataStore.edit {
            it[Keys.BrowserExtensionLastExportTheme] = theme.wireValue
            it[Keys.BrowserExtensionLastExportAppVersion] = appVersion
            it[Keys.BrowserExtensionLastExportExtensionVersion] = extensionVersion
            it[Keys.BrowserExtensionLastExportSha256] = sha256
            it[Keys.BrowserExtensionLastExportEpochMs] = epochMs
            it[Keys.BrowserExtensionLastExportFileName] = fileName
            it[Keys.BrowserExtensionLastExportByteCount] = byteCount
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
