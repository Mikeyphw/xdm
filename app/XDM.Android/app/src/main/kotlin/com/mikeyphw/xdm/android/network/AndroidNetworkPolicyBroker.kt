package com.mikeyphw.xdm.android.network

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.ProxyInfo
import android.net.Uri
import android.os.BatteryManager
import android.os.StatFs
import android.security.NetworkSecurityPolicy
import com.mikeyphw.xdm.android.scheduler.MediaRequestHandoff
import com.mikeyphw.xdm.android.scheduler.MediaRequestHandoffStore
import java.security.KeyStore
import java.time.Instant

/**
 * XGO-70 Android platform-fact broker.
 *
 * This class reports Android facts to Go. It never makes transfer eligibility,
 * retry, backend, or ownership decisions, and it never emits raw secrets to logs.
 */
class AndroidNetworkPolicyBroker(
    private val context: Context,
    private val secretStore: AndroidSecureCredentialSource = AndroidMediaRequestCredentialSource(),
) {
    fun isCleartextAllowed(host: String): Boolean {
        val canonicalHost = host.trim().lowercase()
        if (canonicalHost.isBlank()) return false
        return NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted(canonicalHost)
    }

    fun resolveSecret(secretRef: AndroidSecretRef): AndroidSecretResolution {
        val value = secretStore.resolve(secretRef)
        return AndroidSecretResolution(secretRef, value, "secret_ref:${secretRef.id}")
    }

    fun runtimeConditions(): AndroidRuntimeConditionSnapshot {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val active = connectivity?.activeNetwork
        val caps: NetworkCapabilities? = active?.let { connectivity.getNetworkCapabilities(it) }
        val batteryManager = context.getSystemService(BatteryManager::class.java)
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        return AndroidRuntimeConditionSnapshot(
            observedAt = Instant.now().toString(),
            online = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            metered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true,
            wifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true,
            charging = batteryManager?.isCharging == true,
            batteryPercent = (batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 0).coerceIn(0, 100),
            storageFreeBytes = AndroidStorageProbe.availableBytes(context),
            powerSource = when (plugged) {
                BatteryManager.BATTERY_PLUGGED_AC -> "ac"
                BatteryManager.BATTERY_PLUGGED_USB -> "usb"
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
                else -> "battery"
            },
        )
    }

    fun systemProxy(): AndroidProxyReply {
        val proxyInfo: ProxyInfo? = context.getSystemService(ConnectivityManager::class.java)?.defaultProxy
        if (proxyInfo == null) return AndroidProxyReply(mode = "direct", safeLog = "system_proxy:direct")
        val pac = proxyInfo.pacFileUrl?.takeUnless { it == Uri.EMPTY }?.toString().orEmpty()
        return if (pac.isNotBlank()) {
            AndroidProxyReply(mode = "pac", pacUrl = pac, safeLog = "system_proxy:pac")
        } else {
            AndroidProxyReply(
                mode = "manual",
                host = proxyInfo.host.orEmpty(),
                port = proxyInfo.port,
                noProxy = proxyInfo.exclusionList?.toList().orEmpty(),
                safeLog = "system_proxy:manual",
            )
        }
    }

    fun tlsPlan(): AndroidTlsIntegrationPlan {
        val platformStore = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
        return AndroidTlsIntegrationPlan(
            certificateStore = "AndroidCAStore/network-security-config",
            platformAnchorCount = platformStore.size(),
            deviceTestServerRequired = true,
            logcatMustNotContainSecrets = true,
        )
    }
}

data class AndroidSecretRef(val id: String, val scope: String, val generation: Long)

/** Raw value is deliberately excluded from toString() so accidental structured logging stays safe. */
class AndroidSecretResolution(
    val secretRef: AndroidSecretRef,
    val value: String?,
    val safeLog: String,
) {
    val resolved: Boolean get() = value != null
    override fun toString(): String = "AndroidSecretResolution(secretRef=$secretRef,resolved=$resolved,safeLog=$safeLog)"
}

fun interface AndroidSecureCredentialSource { fun resolve(secretRef: AndroidSecretRef): String? }

/**
 * Resolves Go SecretRefs from the existing AndroidKeyStore-backed media request envelope store.
 * Ref format: media-request/<download|capture|variant|command>/<id>/header/<header-name>
 */
class AndroidMediaRequestCredentialSource : AndroidSecureCredentialSource {
    override fun resolve(secretRef: AndroidSecretRef): String? {
        val parts = secretRef.id.split('/', limit = 5)
        if (parts.size != 5 || parts[0] != "media-request" || parts[3] != "header") return null
        val handoff = when (parts[1]) {
            "download" -> MediaRequestHandoffStore.forDownload(parts[2])
            "capture" -> MediaRequestHandoffStore.forCapture(parts[2])
            "variant" -> MediaRequestHandoffStore.forVariant(parts[2])
            "command" -> MediaRequestHandoffStore.forCommand(parts[2])
            else -> null
        } ?: return null
        if (secretRef.generation > 0L && handoff.subjectGeneration != secretRef.generation) return null
        if (!scopeMatches(secretRef.scope, handoff)) return null
        return handoff.headers.entries.firstOrNull { it.key.equals(parts[4], ignoreCase = true) }?.value
    }

    private fun scopeMatches(scope: String, handoff: MediaRequestHandoff): Boolean {
        if (scope.isBlank()) return true
        val expectedHost = runCatching { Uri.parse(scope).host }.getOrNull()?.lowercase()
            ?: scope.substringAfter("://", scope).substringBefore('/').substringBefore(':').lowercase()
        val actualHost = handoff.boundHost?.lowercase() ?: return false
        return expectedHost == actualHost
    }
}

data class AndroidRuntimeConditionSnapshot(
    val observedAt: String,
    val online: Boolean,
    val metered: Boolean,
    val wifi: Boolean,
    val charging: Boolean,
    val batteryPercent: Int,
    val storageFreeBytes: Long,
    val powerSource: String,
)

data class AndroidProxyReply(
    val mode: String,
    val host: String = "",
    val port: Int = 0,
    val pacUrl: String = "",
    val noProxy: List<String> = emptyList(),
    val safeLog: String,
)

data class AndroidTlsIntegrationPlan(
    val certificateStore: String,
    val platformAnchorCount: Int,
    val deviceTestServerRequired: Boolean,
    val logcatMustNotContainSecrets: Boolean,
)

object AndroidStorageProbe {
    fun availableBytes(context: Context): Long = StatFs(context.filesDir.absolutePath).availableBytes.coerceAtLeast(0L)
}
