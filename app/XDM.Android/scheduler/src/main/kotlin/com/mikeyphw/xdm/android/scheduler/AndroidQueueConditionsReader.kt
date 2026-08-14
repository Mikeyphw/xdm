package com.mikeyphw.xdm.android.scheduler

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import com.mikeyphw.xdm.android.model.QueueRuntimeConditions
import java.io.File

class AndroidQueueConditionsReader(private val context: Context) {
    fun snapshot(nowEpochMs: Long = System.currentTimeMillis(), destinationUri: String? = null): QueueRuntimeConditions {
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
        return QueueRuntimeConditions(
            connected = connected,
            validated = validated,
            unmetered = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true,
            wifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true,
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
            batteryPercent = batteryPercent,
            availableStorageBytes = availableBytesForDestination(destinationUri),
            nowEpochMs = nowEpochMs,
        )
    }

    private fun availableBytesForDestination(destinationUri: String?): Long? {
        val raw = destinationUri?.trim().orEmpty()
        val target = when {
            raw.isBlank() -> return null
            raw.startsWith("public-downloads://", ignoreCase = true) -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            raw.startsWith("app-private://", ignoreCase = true) -> context.filesDir
            raw.startsWith("file://", ignoreCase = true) -> runCatching { File(requireNotNull(Uri.parse(raw).path)) }.getOrNull()
            raw.startsWith('/') -> File(raw)
            // A generic content:// provider does not expose reliable filesystem free-space through
            // the URI contract. Returning unknown makes storage-pressure policy fail closed.
            else -> null
        } ?: return null
        val probe = if (target.isDirectory) target else target.parentFile ?: target
        return runCatching { StatFs(probe.absolutePath).availableBytes }.getOrNull()
    }
}
