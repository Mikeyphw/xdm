package com.mikeyphw.xdm.android.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import androidx.core.content.ContextCompat

/**
 * Converts meaningful runtime condition changes into coalesced queue evaluations.
 * It owns no download state and never starts a transfer directly.
 */
class QueueConditionMonitor(
    private val context: Context,
    private val onConditionsChanged: () -> Unit,
) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private var started = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = onConditionsChanged()
        override fun onLost(network: Network) = onConditionsChanged()
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: android.net.NetworkCapabilities) = onConditionsChanged()
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action in MONITORED_ACTIONS) onConditionsChanged()
        }
    }

    fun start() {
        if (started) return
        var networkRegistered = false
        var receiverRegistered = false
        try {
            connectivity.registerDefaultNetworkCallback(networkCallback)
            networkRegistered = true
            val filter = IntentFilter().apply { MONITORED_ACTIONS.forEach(::addAction) }
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
            started = true
        } catch (error: Throwable) {
            if (networkRegistered) runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
            if (receiverRegistered) runCatching { context.unregisterReceiver(receiver) }
            started = false
            throw error
        }
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
        runCatching { context.unregisterReceiver(receiver) }
    }

    companion object {
        // The public Intent fields are deprecated, but these protected system broadcasts
        // remain useful as immediate reevaluation hints. Queue policy still verifies actual free space before starting a transfer,
        // so the broadcasts are never authoritative.
        private const val ACTION_DEVICE_STORAGE_LOW = "android.intent.action.DEVICE_STORAGE_LOW"
        private const val ACTION_DEVICE_STORAGE_OK = "android.intent.action.DEVICE_STORAGE_OK"

        private val MONITORED_ACTIONS = setOf(
            Intent.ACTION_POWER_CONNECTED,
            Intent.ACTION_POWER_DISCONNECTED,
            ACTION_DEVICE_STORAGE_LOW,
            ACTION_DEVICE_STORAGE_OK,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
        )
    }
}
