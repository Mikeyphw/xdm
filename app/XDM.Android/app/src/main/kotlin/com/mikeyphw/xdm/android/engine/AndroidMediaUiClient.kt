package com.mikeyphw.xdm.android.engine

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.mikeyphw.xdm.android.media.MediaTrackSelection
import com.mikeyphw.xdm.android.model.MediaCaptureRecord
import com.mikeyphw.xdm.android.model.MediaVariant
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

/** Process-scoped media UI binding to the single Android-hosted Go engine. */
class AndroidMediaUiClient(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _projection = MutableStateFlow(AndroidMediaUiProjection())
    private var service: AndroidEngineService? = null
    private var projectionJob: Job? = null
    private var connectedSignal = CompletableDeferred<Unit>()

    val projection: StateFlow<AndroidMediaUiProjection> = _projection.asStateFlow()

    private val connectionCallbacks = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val next = (binder as? AndroidEngineService.EngineBinder)?.service() ?: return
            service = next
            if (!connectedSignal.isCompleted) connectedSignal.complete(Unit)
            projectionJob?.cancel()
            projectionJob = scope.launch { next.mediaProjections().collectLatest { _projection.value = it } }
        }
        override fun onServiceDisconnected(name: ComponentName?) = scheduleRebind()
        override fun onBindingDied(name: ComponentName?) = scheduleRebind()
        override fun onNullBinding(name: ComponentName?) = scheduleRebind()
    }

    init { bind() }

    fun bind() {
        if (service != null) return
        val bound = runCatching {
            context.bindService(Intent(context, AndroidEngineService::class.java), connectionCallbacks, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (!bound) scheduleRebind()
    }

    suspend fun capture(
        record: MediaCaptureRecord,
        variants: List<MediaVariant>,
        sessionId: String,
        documentGeneration: Long,
        headers: Map<String, String> = emptyMap(),
    ): AndroidMediaUiCommandResult {
        val requestId = "media-${UUID.randomUUID()}"
        val payload = AndroidMediaUiWire.capturePayload(requestId, record, variants, sessionId, documentGeneration, headers)
        return submit("android.media.capture", requestId, "capture", payload)
    }

    suspend fun select(captureId: String, selection: MediaTrackSelection): AndroidMediaUiCommandResult {
        val requestId = "media-${UUID.randomUUID()}"
        return submit("android.media.select", requestId, "select", AndroidMediaUiWire.selectionPayload(requestId, captureId, selection))
    }

    suspend fun execute(captureId: String): AndroidMediaUiCommandResult {
        val requestId = "media-${UUID.randomUUID()}"
        return submit("android.media.execute", requestId, "execute", AndroidMediaUiWire.executePayload(requestId, captureId))
    }

    private suspend fun submit(kind: String, requestId: String, action: String, payload: org.json.JSONObject): AndroidMediaUiCommandResult {
        var current = service
        if (current == null) {
            bind()
            withTimeoutOrNull(CONNECTION_WAIT_MS) { connectedSignal.await() }
            current = service
        }
        val connected = current ?: return AndroidMediaUiCommandResult(requestId, action, false, errorCode = "engine_disconnected", message = "XDM engine is reconnecting")
        return runCatching { connected.submitMediaUiCommand(kind, payload) }
            .getOrElse {
                scheduleRebind()
                AndroidMediaUiCommandResult(requestId, action, false, errorCode = "engine_disconnected", message = it.message ?: "Engine connection was lost")
            }
    }

    private fun scheduleRebind() {
        service = null
        projectionJob?.cancel()
        if (connectedSignal.isCompleted) connectedSignal = CompletableDeferred()
        scope.launch {
            delay(REBIND_DELAY_MS)
            bind()
        }
    }

    private companion object {
        const val CONNECTION_WAIT_MS = 4_000L
        const val REBIND_DELAY_MS = 750L
    }
}
