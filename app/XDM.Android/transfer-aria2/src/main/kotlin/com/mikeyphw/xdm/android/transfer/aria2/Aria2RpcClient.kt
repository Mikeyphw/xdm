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
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
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
        val result = invoke("aria2.getVersion") as? JsonObject ?: error("aria2.getVersion returned an invalid result")
        return Aria2Version(
            version = result.string("version") ?: "unknown",
            enabledFeatures = result["enabledFeatures"]?.jsonArray?.mapNotNull { it.contentOrNull() }?.toSet().orEmpty(),
        )
    }

    override suspend fun addUri(uris: List<String>, options: Aria2TaskOptions): String {
        require(uris.isNotEmpty()) { "At least one aria2 URI is required" }
        val safeHeaders = options.headers.map { (name, value) ->
            require(name.isNotBlank() && name.none { it == '\r' || it == '\n' }) { "Unsafe HTTP header name" }
            require(value.none { it == '\r' || it == '\n' }) { "Unsafe HTTP header value" }
            "$name: $value"
        }
        val optionObject = buildJsonObject {
            put("dir", options.directory)
            put("out", options.outputName)
            put("pause", options.pause.toString())
            put("continue", options.continueDownload.toString())
            put("always-resume", "true")
            put("allow-overwrite", "false")
            put("auto-file-renaming", "false")
            put("split", options.split.coerceIn(1, 16).toString())
            put("max-connection-per-server", options.maxConnectionsPerServer.coerceIn(1, 16).toString())
            if (safeHeaders.isNotEmpty()) put("header", buildJsonArray { safeHeaders.forEach { add(JsonPrimitive(it)) } })
        }
        val params = buildJsonArray {
            add(buildJsonArray { uris.forEach { add(JsonPrimitive(it)) } })
            add(optionObject)
        }
        return invoke("aria2.addUri", params).requiredString("aria2.addUri")
    }

    override suspend fun pause(gid: String, force: Boolean) {
        invoke(if (force) "aria2.forcePause" else "aria2.pause", scalarParams(gid))
    }

    override suspend fun unpause(gid: String) {
        invoke("aria2.unpause", scalarParams(gid))
    }

    override suspend fun remove(gid: String, force: Boolean) {
        invoke(if (force) "aria2.forceRemove" else "aria2.remove", scalarParams(gid))
    }

    override suspend fun tellStatus(gid: String): Aria2TaskStatus = invoke(
        "aria2.tellStatus",
        buildJsonArray {
            add(JsonPrimitive(gid))
            add(statusKeys())
        },
    ).jsonObject.toTaskStatus()

    override suspend fun tellActive(): List<Aria2TaskStatus> = invoke(
        "aria2.tellActive",
        buildJsonArray { add(statusKeys()) },
    ).jsonArray.map { it.jsonObject.toTaskStatus() }

    override suspend fun tellWaiting(offset: Int, count: Int): List<Aria2TaskStatus> = invoke(
        "aria2.tellWaiting",
        buildJsonArray {
            add(JsonPrimitive(offset))
            add(JsonPrimitive(count.coerceIn(1, 1000)))
            add(statusKeys())
        },
    ).jsonArray.map { it.jsonObject.toTaskStatus() }

    override suspend fun tellStopped(offset: Int, count: Int): List<Aria2TaskStatus> = invoke(
        "aria2.tellStopped",
        buildJsonArray {
            add(JsonPrimitive(offset))
            add(JsonPrimitive(count.coerceIn(1, 1000)))
            add(statusKeys())
        },
    ).jsonArray.map { it.jsonObject.toTaskStatus() }

    override suspend fun removeDownloadResult(gid: String) {
        invoke("aria2.removeDownloadResult", scalarParams(gid))
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
        val request = Request.Builder().url(endpoint.url).post(payload.toString().toRequestBody(JSON_MEDIA_TYPE)).build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "aria2 RPC HTTP ${response.code}" }
            val root = json.parseToJsonElement(response.body.string()).jsonObject
            root["error"]?.jsonObject?.let { error ->
                throw Aria2RpcException(
                    code = error.string("code")?.toIntOrNull() ?: -1,
                    message = error.string("message") ?: "Unknown aria2 RPC failure",
                )
            }
            root["result"] ?: error("aria2 RPC response did not contain a result")
        }
    }

    private fun statusKeys(): JsonArray = buildJsonArray {
        listOf(
            "gid", "status", "totalLength", "completedLength", "downloadSpeed", "dir", "files",
            "errorCode", "errorMessage", "followedBy", "following", "belongsTo",
        ).forEach { add(JsonPrimitive(it)) }
    }

    private fun scalarParams(value: String) = buildJsonArray { add(JsonPrimitive(value)) }

    private fun JsonObject.toTaskStatus(): Aria2TaskStatus = Aria2TaskStatus(
        gid = string("gid") ?: error("aria2 status has no GID"),
        status = when (string("status")) {
            "active" -> Aria2TaskStatusValue.Active
            "waiting" -> Aria2TaskStatusValue.Waiting
            "paused" -> Aria2TaskStatusValue.Paused
            "error" -> Aria2TaskStatusValue.Error
            "complete" -> Aria2TaskStatusValue.Complete
            "removed" -> Aria2TaskStatusValue.Removed
            else -> Aria2TaskStatusValue.Unknown
        },
        totalLength = long("totalLength"),
        completedLength = long("completedLength"),
        downloadSpeed = long("downloadSpeed"),
        dir = string("dir"),
        files = get("files")?.jsonArray?.map { fileElement ->
            val file = fileElement.jsonObject
            Aria2RpcFile(
                index = file.string("index")?.toIntOrNull() ?: 0,
                path = file.string("path").orEmpty(),
                length = file.long("length"),
                completedLength = file.long("completedLength"),
                selected = file.string("selected") != "false",
                uris = file["uris"]?.jsonArray?.map { uriElement ->
                    val uri = uriElement.jsonObject
                    Aria2RpcUri(uri.string("uri").orEmpty(), uri.string("status"))
                }.orEmpty(),
            )
        }.orEmpty(),
        errorCode = string("errorCode"),
        errorMessage = string("errorMessage"),
        followedBy = get("followedBy")?.jsonArray?.mapNotNull { it.contentOrNull() }.orEmpty(),
        following = string("following"),
        belongsTo = string("belongsTo"),
    )

    private fun JsonObject.string(key: String): String? = get(key)?.contentOrNull()
    private fun JsonObject.long(key: String): Long = string(key)?.toLongOrNull() ?: 0L
    private fun JsonElement.contentOrNull(): String? = runCatching { jsonPrimitive.content }.getOrNull()
    private fun JsonElement.requiredString(method: String): String = contentOrNull()?.takeIf(String::isNotBlank)
        ?: error("$method returned an invalid result")

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
