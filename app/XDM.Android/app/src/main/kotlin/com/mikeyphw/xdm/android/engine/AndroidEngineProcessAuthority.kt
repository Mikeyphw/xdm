package com.mikeyphw.xdm.android.engine

import android.content.Context
import com.mikeyphw.xdm.android.model.Download
import com.mikeyphw.xdm.android.network.AndroidNetworkPolicyBroker
import com.mikeyphw.xdm.android.scheduler.AndroidEngineWakeDisposition
import com.mikeyphw.xdm.android.scheduler.AndroidEngineWakeRequest
import com.mikeyphw.xdm.android.scheduler.AndroidEngineWakeResult
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/**
 * Process-wide owner of the single JNI Go engine.
 *
 * Services, WorkManager, UIDT and later UI bindings all rendezvous here so an
 * Android component lifecycle can never accidentally create a second engine.
 */
class AndroidEngineProcessAuthority(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val state = MutableStateFlow<AndroidEngineProjection>(AndroidEngineProjection.Stopped)
    private val outboundFrames = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    private val downloadProjection = MutableStateFlow(AndroidDownloadUiProjection())
    private val pendingUiCommands = ConcurrentHashMap<String, CompletableDeferred<AndroidDownloadUiCommandResult>>()
    private val uiCommandSequence = AtomicLong(1L)
    private val acceptedWakeEvents = ConcurrentHashMap.newKeySet<String>()
    private var bridge: AndroidGoEngineBridge? = null
    private var framePump: Job? = null
    private var engineIdentity: String? = null

    fun projections(): StateFlow<AndroidEngineProjection> = state.asStateFlow()
    fun downloadProjections(): StateFlow<AndroidDownloadUiProjection> = downloadProjection.asStateFlow()
    fun frames(): SharedFlow<ByteArray> = outboundFrames.asSharedFlow()

    @Synchronized
    fun ensureSingleEngine(reason: ProcessRestartRecovery): String {
        engineIdentity?.let { return it }
        val nextBridge = AndroidGoEngineBridge().also { it.create(ByteArray(0)) }
        val nextIdentity = "android-go-engine-${System.currentTimeMillis()}"
        bridge = nextBridge
        engineIdentity = nextIdentity
        state.value = AndroidEngineProjection.Running(nextIdentity, reason.name)
        startFramePump(nextBridge)
        return nextIdentity
    }

    fun wake(request: AndroidEngineWakeRequest): AndroidEngineWakeResult {
        if (request.eventId.isBlank()) {
            return AndroidEngineWakeResult(AndroidEngineWakeDisposition.FAILED, "Android host event id is blank")
        }
        if (!acceptedWakeEvents.add(request.eventId)) {
            return AndroidEngineWakeResult(AndroidEngineWakeDisposition.DUPLICATE, "Duplicate Android host event suppressed")
        }
        return runCatching {
            ensureSingleEngine(
                if (request.afterBootOrPackageRestart) ProcessRestartRecovery.ProcessRestart
                else ProcessRestartRecovery.PlatformWake,
            )
            val currentBridge = synchronized(this) { bridge }
                ?: error("Go engine bridge unavailable after process authority initialization")
            currentBridge.command(schedulerCommand(request))
            AndroidEngineWakeResult(AndroidEngineWakeDisposition.ACCEPTED, "Execution opportunity delegated to Go")
        }.getOrElse {
            acceptedWakeEvents.remove(request.eventId)
            AndroidEngineWakeResult(AndroidEngineWakeDisposition.RETRYABLE, "Go engine host command could not be submitted")
        }
    }

    @Synchronized
    fun currentIdentity(): String? = engineIdentity

    fun syncLegacyDownloadProjection(revision: Long, downloads: List<Download>) {
        val currentBridge = synchronized(this) {
            ensureSingleEngine(ProcessRestartRecovery.PlatformWake)
            bridge
        } ?: return
        val payload = AndroidDownloadUiWire.projectionJson(revision, downloads)
        runCatching { currentBridge.command(uiCommand("android.ui.sync_downloads", payload, "projection:$revision")) }
    }

    suspend fun submitDownloadUiCommand(payload: JSONObject): AndroidDownloadUiCommandResult {
        val requestId = payload.optString("client_request_id").takeIf(String::isNotBlank)
            ?: "ui-${uiCommandSequence.getAndIncrement()}"
        val commandPayload = JSONObject(payload.toString()).put("client_request_id", requestId)
        val deferred = CompletableDeferred<AndroidDownloadUiCommandResult>()
        check(pendingUiCommands.putIfAbsent(requestId, deferred) == null) { "duplicate Android UI request id" }
        val currentBridge = synchronized(this) {
            ensureSingleEngine(ProcessRestartRecovery.Bind)
            bridge
        }
        if (currentBridge == null) {
            pendingUiCommands.remove(requestId)
            return AndroidDownloadUiCommandResult(requestId, commandPayload.optString("action"), false, errorCode = "engine_disconnected", message = "Go engine is unavailable")
        }
        return try {
            currentBridge.command(uiCommand("android.ui.download_command", commandPayload, "ui-command:$requestId"))
            withTimeout(UI_COMMAND_TIMEOUT_MS) { deferred.await() }
        } catch (error: Throwable) {
            pendingUiCommands.remove(requestId)
            AndroidDownloadUiCommandResult(requestId, commandPayload.optString("action"), false, errorCode = "engine_disconnected", message = error.message ?: "Go engine command failed")
        }
    }

    private fun startFramePump(currentBridge: AndroidGoEngineBridge) {
        framePump?.cancel()
        val dispatcher = AndroidPlatformRequestDispatcher(
            currentBridge,
            AndroidNetworkPolicyBroker(appContext),
        ) { (appContext as? AndroidDownloadUiPlatformBrokerProvider)?.androidDownloadUiPlatformBrokerOrNull() }
        framePump = scope.launch {
            while (isActive) {
                val frame = runCatching { currentBridge.nextFrame(FRAME_POLL_TIMEOUT_MS) }.getOrNull()
                if (frame == null) {
                    delay(FRAME_IDLE_BACKOFF_MS)
                    continue
                }
                if (consumeUiFrame(frame)) continue
                if (!dispatcher.dispatch(frame)) outboundFrames.emit(frame.copyOf())
            }
        }
    }

    private fun consumeUiFrame(frame: ByteArray): Boolean {
        val envelope = runCatching { JSONObject(String(frame, Charsets.UTF_8)) }.getOrNull() ?: return false
        val payload = envelope.optJSONObject("payload") ?: return false
        return when (envelope.optString("kind")) {
            "android.ui.projection" -> {
                runCatching { AndroidDownloadUiWire.projection(payload) }
                    .onSuccess { downloadProjection.value = it }
                true
            }
            "android.ui.command_result" -> {
                val result = runCatching { AndroidDownloadUiWire.result(payload) }.getOrNull() ?: return true
                pendingUiCommands.remove(result.requestId)?.complete(result)
                true
            }
            else -> false
        }
    }

    private fun uiCommand(kind: String, payload: JSONObject, seed: String): ByteArray {
        val command = JSONObject()
            .put("protocol", JSONObject().put("major", 1).put("minor", 0))
            .put("command_id", "cmd_${token("command:$seed")}")
            .put("operation_id", "op_${token("operation:$seed")}")
            .put("kind", kind)
            .put("payload", payload)
        return command.toString().toByteArray(Charsets.UTF_8)
    }

    private fun schedulerCommand(request: AndroidEngineWakeRequest): ByteArray {
        val payload = JSONObject()
            .put("event_id", request.eventId)
            .put("reason", request.reason.name.lowercase())
            .put("requires_foreground", request.requiresForeground)
            .put("user_initiated", request.userInitiated)
            .put("after_boot_or_package_restart", request.afterBootOrPackageRestart)
        request.downloadId?.takeIf(String::isNotBlank)?.let { payload.put("download_id", it) }
        request.engineRetryDueAtEpochMs?.takeIf { it > 0L }?.let { payload.put("engine_retry_due_at_epoch_ms", it) }
        val command = JSONObject()
            .put("protocol", JSONObject().put("major", 1).put("minor", 0))
            .put("command_id", "cmd_${token(request.eventId)}")
            .put("operation_id", "op_${token("operation:${request.eventId}:${request.downloadId.orEmpty()}")}")
            .put("kind", "android.scheduler_wake")
            .put("payload", payload)
        return command.toString().toByteArray(Charsets.UTF_8)
    }

    private fun token(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .take(16)
        .joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        const val FRAME_POLL_TIMEOUT_MS = 250
        const val FRAME_IDLE_BACKOFF_MS = 25L
        const val UI_COMMAND_TIMEOUT_MS = 10_000L
    }
}
