package com.mikeyphw.xdm.android.scheduler

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import com.mikeyphw.xdm.android.model.DestinationHealthStatus
import com.mikeyphw.xdm.android.model.DestinationSpaceState
import com.mikeyphw.xdm.android.model.QueueRuntimeConditions
import com.mikeyphw.xdm.android.storage.DestinationHealth
import com.mikeyphw.xdm.android.storage.DestinationWriter

internal data class DestinationSpaceSnapshot(
    val state: DestinationSpaceState,
    val availableBytes: Long?,
)

internal object DestinationSpacePolicy {
    fun fromHealth(health: DestinationHealth): DestinationSpaceSnapshot = when (health.status) {
        DestinationHealthStatus.Healthy, DestinationHealthStatus.LowSpace -> {
            if (health.availableBytes != null) {
                DestinationSpaceSnapshot(DestinationSpaceState.Known, health.availableBytes)
            } else {
                DestinationSpaceSnapshot(DestinationSpaceState.Unknown, null)
            }
        }
        DestinationHealthStatus.Unknown -> DestinationSpaceSnapshot(DestinationSpaceState.Unknown, null)
        DestinationHealthStatus.PermissionMissing,
        DestinationHealthStatus.Unavailable,
        DestinationHealthStatus.ReadOnly -> DestinationSpaceSnapshot(DestinationSpaceState.Unavailable, null)
    }
}

class AndroidQueueConditionsReader(
    private val context: Context,
    private val destinationWriter: DestinationWriter,
) {
    suspend fun snapshot(nowEpochMs: Long = System.currentTimeMillis(), destinationUri: String? = null): QueueRuntimeConditions {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val activeNetwork = connectivity.activeNetwork
        val capabilities = activeNetwork?.let(connectivity::getNetworkCapabilities)
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPercent = if (level >= 0 && scale > 0) ((level * 100f) / scale).toInt().coerceIn(0, 100) else null
        val connected = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val validated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        val destinationSpace = destinationSpace(destinationUri)
        return QueueRuntimeConditions(
            connected = connected,
            validated = validated,
            unmetered = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true,
            wifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true,
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
            batteryPercent = batteryPercent,
            availableStorageBytes = destinationSpace.availableBytes,
            destinationSpaceState = destinationSpace.state,
            nowEpochMs = nowEpochMs,
        )
    }

    private suspend fun destinationSpace(destinationUri: String?): DestinationSpaceSnapshot {
        val raw = destinationUri?.trim().orEmpty()
        if (raw.isBlank()) return DestinationSpaceSnapshot(DestinationSpaceState.Unknown, null)
        return runCatching { destinationWriter.health(raw) }
            .fold(
                onSuccess = DestinationSpacePolicy::fromHealth,
                onFailure = { DestinationSpaceSnapshot(DestinationSpaceState.Unavailable, null) },
            )
    }
}
