package com.mikeyphw.xdm.android.engine

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import com.mikeyphw.xdm.android.XdmApplication
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/**
 * Binder/service facade over the process-wide Go engine authority.
 * Activity recreation, duplicate starts and scheduler host components reconnect
 * to one AndroidEngineProcessAuthority instead of creating component-local engines.
 */
class AndroidEngineService : Service() {
    private val binder = EngineBinder()
    private var engineIdentity: String? = null

    private val authority: AndroidEngineProcessAuthority
        get() = (application as XdmApplication).androidEngineProcessAuthority

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

    fun projections(): StateFlow<AndroidEngineProjection> = authority.projections()
    fun downloadProjections(): StateFlow<AndroidDownloadUiProjection> = authority.downloadProjections()
    fun mediaProjections(): StateFlow<AndroidMediaUiProjection> = authority.mediaProjections()
    suspend fun submitDownloadUiCommand(payload: JSONObject): AndroidDownloadUiCommandResult = authority.submitDownloadUiCommand(payload)
    suspend fun submitMediaUiCommand(kind: String, payload: JSONObject): AndroidMediaUiCommandResult = authority.submitMediaUiCommand(kind, payload)

    @Synchronized
    fun ensureSingleEngine(reason: ProcessRestartRecovery): String = authority.ensureSingleEngine(reason).also {
        engineIdentity = it
    }

    fun requestForegroundEscalation(reason: String): AndroidHostRequest =
        AndroidHostRequest.ForegroundEscalation(reason = reason, engineIdentity = engineIdentity ?: authority.currentIdentity())

    override fun onDestroy() {
        // The service is a host primitive, not the engine lifetime owner. The process authority stays alive.
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
    PlatformWake,
}
