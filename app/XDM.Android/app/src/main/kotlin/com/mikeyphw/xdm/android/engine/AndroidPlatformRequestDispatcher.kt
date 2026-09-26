package com.mikeyphw.xdm.android.engine

import com.mikeyphw.xdm.android.network.AndroidNetworkPolicyBroker
import com.mikeyphw.xdm.android.network.AndroidSecretRef
import org.json.JSONArray
import org.json.JSONObject

/** Handles the XGO-70 platform requests that require Android OS facts or secure storage. */
class AndroidPlatformRequestDispatcher(
    private val bridge: AndroidGoEngineBridge,
    private val network: AndroidNetworkPolicyBroker,
) {
    fun dispatch(frameBytes: ByteArray): Boolean {
        val envelope = runCatching { JSONObject(String(frameBytes, Charsets.UTF_8)) }.getOrNull() ?: return false
        if (envelope.optString("kind") != "platform.request") return false
        val request = envelope.optJSONObject("payload") ?: return false
        val requestId = request.optLong("request_id", 0L)
        val session = request.optLong("session", 0L)
        if (requestId <= 0L || session <= 0L) return false
        val payload = request.optJSONObject("payload") ?: JSONObject()
        val reply = when (request.optString("kind")) {
            "runtime_conditions" -> successReply(requestId, session, runtimeConditionsJson())
            "network_policy" -> networkPolicyReply(requestId, session, payload)
            "system_proxy" -> successReply(requestId, session, proxyJson())
            "secret_lookup" -> secretReply(requestId, session, payload)
            else -> return false
        }
        bridge.platformReply(reply.toString().toByteArray(Charsets.UTF_8))
        return true
    }

    private fun runtimeConditionsJson(): JSONObject = network.runtimeConditions().let { snapshot ->
        JSONObject()
            .put("observed_at", snapshot.observedAt)
            .put("online", snapshot.online)
            .put("metered", snapshot.metered)
            .put("wifi", snapshot.wifi)
            .put("charging", snapshot.charging)
            .put("battery_percent", snapshot.batteryPercent)
            .put("storage_free_bytes", snapshot.storageFreeBytes)
            .put("power_source", snapshot.powerSource)
    }

    private fun networkPolicyReply(requestId: Long, session: Long, payload: JSONObject): JSONObject {
        val host = payload.optString("host").trim()
        if (host.isBlank()) return errorReply(requestId, session, "invalid_host")
        val tls = runCatching { network.tlsPlan() }.getOrElse { return errorReply(requestId, session, "tls_store_unavailable") }
        val response = JSONObject()
            .put("host", host)
            .put("cleartext_allowed", network.isCleartextAllowed(host))
            .put("certificate_store", tls.certificateStore)
            .put("platform_anchor_count", tls.platformAnchorCount)
            .put("device_test_server_required", tls.deviceTestServerRequired)
        return successReply(requestId, session, response)
    }

    private fun proxyJson(): JSONObject = network.systemProxy().let { proxy ->
        JSONObject()
            .put("mode", proxy.mode)
            .put("host", proxy.host)
            .put("port", proxy.port)
            .put("pac_url", proxy.pacUrl)
            .put("no_proxy", JSONArray(proxy.noProxy))
    }

    private fun secretReply(requestId: Long, session: Long, payload: JSONObject): JSONObject {
        val refObject = payload.optJSONObject("ref") ?: payload
        val id = refObject.optString("id")
        val scope = refObject.optString("scope")
        val generation = refObject.optLong("generation", 0L)
        if (id.isBlank()) return errorReply(requestId, session, "invalid_secret_ref")
        val resolved = network.resolveSecret(AndroidSecretRef(id, scope, generation))
        val value = resolved.value ?: return errorReply(requestId, session, "secret_not_found")
        // The value exists only in the platform reply payload. It is never sent to logcat/support diagnostics.
        return successReply(requestId, session, JSONObject().put("value", value))
    }

    private fun successReply(requestId: Long, session: Long, payload: JSONObject): JSONObject = JSONObject()
        .put("protocol", protocol())
        .put("request_id", requestId)
        .put("session", session)
        .put("ok", true)
        .put("payload", payload)

    private fun errorReply(requestId: Long, session: Long, errorCode: String): JSONObject = JSONObject()
        .put("protocol", protocol())
        .put("request_id", requestId)
        .put("session", session)
        .put("ok", false)
        .put("error_code", errorCode)

    private fun protocol(): JSONObject = JSONObject().put("major", 1).put("minor", 0)
}
