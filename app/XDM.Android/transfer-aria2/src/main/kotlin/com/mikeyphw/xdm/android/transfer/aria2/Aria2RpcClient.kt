package com.mikeyphw.xdm.android.transfer.aria2

import java.net.Proxy
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class Aria2RpcException(
    val code: Int,
    message: String,
) : IllegalStateException("aria2 RPC $code: $message")

class OkHttpAria2RpcControlFactory(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .writeTimeout(3, TimeUnit.SECONDS)
        .build(),
) : Aria2RpcControlFactory {
    override fun create(endpoint: Aria2Endpoint, secret: Aria2RpcSecret): Aria2RpcControl =
        OkHttpAria2RpcControl(endpoint, secret, client)
}

class OkHttpAria2RpcControl(
    private val endpoint: Aria2Endpoint,
    private val secret: Aria2RpcSecret,
    private val client: OkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : Aria2RpcControl {
    private val requestId = AtomicLong()

    override suspend fun getVersion(): Aria2Version {
        val result = invoke("aria2.getVersion")
        val objectResult = result as? JsonObject ?: error("aria2.getVersion returned an invalid result")
        val version = objectResult["version"]?.jsonPrimitive?.content ?: "unknown"
        val features = objectResult["enabledFeatures"]
            ?.jsonArray
            ?.mapNotNull { element -> element.jsonPrimitive.content.takeIf(String::isNotBlank) }
            ?.toSet()
            .orEmpty()
        return Aria2Version(version, features)
    }

    override suspend fun saveSession(): Boolean = invoke("aria2.saveSession").jsonPrimitive.content == "OK"

    override suspend fun shutdown(force: Boolean) {
        invoke(if (force) "aria2.forceShutdown" else "aria2.shutdown")
    }

    internal fun authenticatedParameters(parameters: JsonArray): JsonArray = buildJsonArray {
        add(JsonPrimitive(secret.tokenParameter()))
        parameters.forEach(::add)
    }

    private suspend fun invoke(method: String, parameters: JsonArray = JsonArray(emptyList())): JsonElement = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", requestId.incrementAndGet().toString())
            put("method", method)
            put("params", authenticatedParameters(parameters))
        }
        val request = Request.Builder()
            .url(endpoint.url)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "aria2 RPC HTTP ${response.code}" }
            val body = response.body.string()
            val root = json.parseToJsonElement(body).jsonObject
            root["error"]?.jsonObject?.let { error ->
                val code = error["code"]?.jsonPrimitive?.content?.toIntOrNull() ?: -1
                val message = error["message"]?.jsonPrimitive?.content ?: "Unknown aria2 RPC failure"
                throw Aria2RpcException(code, message)
            }
            root["result"] ?: error("aria2 RPC response did not contain a result")
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
