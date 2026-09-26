package com.mikeyphw.xdm.android.engine

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.mikeyphw.xdm.android.model.ChecksumExpectation
import com.mikeyphw.xdm.android.model.Download
import com.mikeyphw.xdm.android.model.DuplicateUrlAction
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Process-scoped UI connection. Activity recreation reuses this client and the same EngineService. */
class AndroidDownloadUiClient(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _connection = MutableStateFlow(AndroidEngineUiConnection.Connecting)
    private val _projection = MutableStateFlow(AndroidDownloadUiProjection())
    private var service: AndroidEngineService? = null
    private var projectionJob: Job? = null
    private var connectedSignal = CompletableDeferred<Unit>()

    val connection: StateFlow<AndroidEngineUiConnection> = _connection.asStateFlow()
    val projection: StateFlow<AndroidDownloadUiProjection> = _projection.asStateFlow()

    private val connectionCallbacks = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val next = (binder as? AndroidEngineService.EngineBinder)?.service() ?: return
            service = next
            _connection.value = AndroidEngineUiConnection.Connected
            if (!connectedSignal.isCompleted) connectedSignal.complete(Unit)
            projectionJob?.cancel()
            projectionJob = scope.launch {
                next.downloadProjections().collectLatest { _projection.value = it }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) = scheduleRebind()
        override fun onBindingDied(name: ComponentName?) = scheduleRebind()
        override fun onNullBinding(name: ComponentName?) = scheduleRebind()
    }

    init { bind() }

    fun bind() {
        if (_connection.value == AndroidEngineUiConnection.Connected) return
        _connection.value = if (service == null) AndroidEngineUiConnection.Connecting else AndroidEngineUiConnection.Rebinding
        val intent = Intent(context, AndroidEngineService::class.java)
        val bound = runCatching { context.bindService(intent, connectionCallbacks, Context.BIND_AUTO_CREATE) }.getOrDefault(false)
        if (!bound) {
            _connection.value = AndroidEngineUiConnection.Disconnected
            scheduleRebind()
        }
    }

    suspend fun command(
        action: String,
        downloadId: String? = null,
        download: Download? = null,
        duplicateAction: DuplicateUrlAction? = null,
        checksum: ChecksumExpectation? = null,
        policyOverride: Boolean = false,
    ): AndroidDownloadUiCommandResult {
        val requestId = "ui-${UUID.randomUUID()}"
        var current = service
        if (current == null) {
            bind()
            withTimeoutOrNull(CONNECTION_WAIT_MS) { connectedSignal.await() }
            current = service
        }
        val connected = current ?: return AndroidDownloadUiCommandResult(
            requestId = requestId,
            action = action,
            ok = false,
            errorCode = "engine_disconnected",
            message = "XDM engine is reconnecting. Try the action again when the connection is restored.",
        )
        val payload = AndroidDownloadUiWire.command(requestId, action, downloadId, download, duplicateAction, checksum, policyOverride)
        return runCatching { connected.submitDownloadUiCommand(payload) }
            .getOrElse {
                scheduleRebind()
                AndroidDownloadUiCommandResult(requestId, action, false, errorCode = "engine_disconnected", message = it.message ?: "Engine connection was lost")
            }
    }

    private fun scheduleRebind() {
        service = null
        projectionJob?.cancel()
        if (_connection.value != AndroidEngineUiConnection.Rebinding) {
            _connection.value = AndroidEngineUiConnection.Rebinding
            connectedSignal = CompletableDeferred()
            scope.launch {
                delay(REBIND_DELAY_MS)
                bind()
            }
        }
    }

    private companion object {
        const val CONNECTION_WAIT_MS = 3_000L
        const val REBIND_DELAY_MS = 250L
    }
}
