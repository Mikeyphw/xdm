package com.mikeyphw.xdm.android.engine

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single Android process authority for the Go engine lifecycle.
 *
 * Activity recreation and repeated start/bind calls reconnect to the same bridge.
 * Host-only escalations are exposed as requests for Android UI/notification code.
 */
class AndroidEngineService : Service() {
    private val binder = EngineBinder()
    private val state = MutableStateFlow(AndroidEngineProjection.Stopped)
    private var bridge: AndroidGoEngineBridge? = null
    private var engineIdentity: String? = null

    inner class EngineBinder : Binder() {
        fun service(): AndroidEngineService = this@AndroidEngineService
    }

    override fun onBind(intent: Intent?): IBinder {
        ensureSingleEngine(ProcessRestartRecovery.Bind)
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureSingleEngine(ProcessRestartRecovery.StartCommand)
        return START_STICKY
    }

    fun projections(): StateFlow<AndroidEngineProjection> = state.asStateFlow()

    @Synchronized
    fun ensureSingleEngine(reason: ProcessRestartRecovery): String {
        engineIdentity?.let { return it }
        val nextIdentity = "android-go-engine-${System.currentTimeMillis()}"
        val nextBridge = AndroidGoEngineBridge()
        nextBridge.create(ByteArray(0))
        bridge = nextBridge
        engineIdentity = nextIdentity
        state.value = AndroidEngineProjection.Running(nextIdentity, reason.name)
        return nextIdentity
    }

    fun requestForegroundEscalation(reason: String): AndroidHostRequest =
        AndroidHostRequest.ForegroundEscalation(reason = reason, engineIdentity = engineIdentity)

    override fun onDestroy() {
        bridge?.close()
        bridge = null
        engineIdentity = null
        state.value = AndroidEngineProjection.Stopped
        super.onDestroy()
    }
}

sealed class AndroidEngineProjection {
    object Stopped : AndroidEngineProjection()
    data class Running(val engineIdentity: String, val recoveryReason: String) : AndroidEngineProjection()
}

sealed class AndroidHostRequest {
    data class ForegroundEscalation(val reason: String, val engineIdentity: String?) : AndroidHostRequest()
}

enum class ProcessRestartRecovery {
    Bind,
    StartCommand,
    ProcessRestart,
}
