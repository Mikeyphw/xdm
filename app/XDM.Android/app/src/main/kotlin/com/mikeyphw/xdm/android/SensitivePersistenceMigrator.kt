package com.mikeyphw.xdm.android

import android.content.Context
import android.util.AtomicFile
import com.mikeyphw.xdm.android.model.ExternalUrlPolicy
import com.mikeyphw.xdm.android.persistence.DownloadRepository
import com.mikeyphw.xdm.android.scheduler.MediaRequestHandoffStore
import java.io.File
import java.net.URI
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

/**
 * One-time migration for data written before encrypted request envelopes were introduced.
 *
 * The migration is fail-closed: exact request material is durably enveloped before any legacy row
 * is redacted, no private/cleartext approval is inferred from a legacy URL, and the completion
 * marker is written only after every Room and JSON-sidecar rewrite succeeds.
 */
internal class SensitivePersistenceMigrator(
    context: Context,
    private val repository: DownloadRepository,
) {
    private val appContext = context.applicationContext
    private val marker = File(appContext.noBackupFilesDir, "sensitive-persistence-v2.complete")

    suspend fun migrateIfNeeded() {
        if (marker.isFile) return

        repository.downloads.first().forEach { download ->
            if (needsRedaction(download.sourceUrl)) {
                MediaRequestHandoffStore.remember(
                    downloadId = download.id,
                    headers = emptyMap(),
                    redactedSummary = "Migrated legacy request URL; approval reset for review",
                    isExpiringUrl = ExternalUrlPolicy.hasCredentialBearingQuery(download.sourceUrl),
                    exactUrl = download.sourceUrl,
                    privateNetworkApproved = false,
                    cleartextCredentialsApproved = false,
                )
                val migrated = download.copy(
                    updatedAtEpochMs = maxOf(System.currentTimeMillis(), download.updatedAtEpochMs + 1L),
                )
                check(repository.save(migrated)) { "Failed to redact legacy download ${download.id}" }
            }
        }

        repository.mediaCaptures.first().forEach { capture ->
            if (needsRedaction(capture.sourceUrl) || needsRedaction(capture.pageUrl)) {
                MediaRequestHandoffStore.rememberCapture(
                    captureId = capture.id,
                    headers = emptyMap(),
                    redactedSummary = "Migrated legacy media URL; approval reset for review",
                    isExpiringUrl = ExternalUrlPolicy.hasCredentialBearingQuery(capture.sourceUrl),
                    exactUrl = capture.sourceUrl,
                    pageUrl = capture.pageUrl,
                    privateNetworkApproved = false,
                    cleartextCredentialsApproved = false,
                )
            }
            repository.saveMediaCapture(capture)
        }

        repository.mediaVariants.first().forEach { variant ->
            if (needsRedaction(variant.url)) {
                MediaRequestHandoffStore.rememberVariant(
                    variantId = variant.id,
                    exactUrl = variant.url,
                    expiresAtEpochMs = variant.expiresAtEpochMs ?: Long.MAX_VALUE,
                )
            }
            repository.saveMediaVariants(listOf(variant))
        }

        repository.automationCommands.first().forEach { command ->
            if (needsRedaction(command.url) || needsRedaction(command.pageUrl)) {
                MediaRequestHandoffStore.rememberCommand(
                    commandId = command.id,
                    exactUrl = command.url,
                    pageUrl = command.pageUrl,
                    headers = emptyMap(),
                    redactedSummary = command.sanitizedHeaders.orEmpty(),
                    // Historical booleans were not bound to the exact URL and cannot be trusted.
                    privateNetworkApproved = false,
                    cleartextCredentialsApproved = false,
                )
            }
            check(repository.saveAutomationCommand(command.copy(privateNetworkApproved = false, cleartextCredentialsApproved = false))) {
                "Failed to redact legacy automation command ${command.id}"
            }
        }

        repository.clipboardInbox.first().forEach { item -> repository.saveClipboardItem(item) }
        scrubJsonSidecars(listOf(appContext.filesDir, appContext.cacheDir))
        writeMarkerAtomically()
    }

    private fun needsRedaction(value: String?): Boolean {
        val raw = value?.trim().orEmpty()
        if (raw.isBlank()) return false
        return ExternalUrlPolicy.persistableUrl(raw) != raw || containsSensitiveUrlMaterial(raw)
    }

    private fun scrubJsonSidecars(roots: List<File>) {
        val failures = mutableListOf<String>()
        roots.filter(File::exists).forEach { root ->
            root.walkTopDown()
                .onEnter { directory -> directory.name != "secure-request-envelopes-v1" }
                .filter { file -> file.isFile && file.extension.equals("json", true) && file.length() <= MAX_JSON_BYTES }
                .forEach { file ->
                    runCatching { scrubJsonFile(file) }
                        .onFailure { failures += "${file.name}:${it.javaClass.simpleName}" }
                }
        }
        check(failures.isEmpty()) { "Sensitive JSON scrub failed: ${failures.joinToString()}" }
    }

    private fun scrubJsonFile(file: File) {
        val original = file.readText(Charsets.UTF_8)
        val rootValue: Any = if (original.trimStart().startsWith("[")) JSONArray(original) else JSONObject(original)
        // Recognizable legacy sidecars can still contain the only copy of exact request headers.
        // Move that material into the encrypted envelope before any destructive redaction.
        promoteLegacyRequestMaterial(rootValue)
        redactJson(rootValue)
        val updated = rootValue.toString()
        if (updated == original) return
        atomicWriteText(file, updated)
    }

    private fun promoteLegacyRequestMaterial(value: Any?) {
        when (value) {
            is JSONObject -> {
                promoteLegacyObject(value)
                value.keys().asSequence().toList().forEach { key ->
                    val child = value.opt(key)
                    if (child is JSONObject || child is JSONArray) promoteLegacyRequestMaterial(child)
                }
            }
            is JSONArray -> for (index in 0 until value.length()) {
                val child = value.opt(index)
                if (child is JSONObject || child is JSONArray) promoteLegacyRequestMaterial(child)
            }
        }
    }

    private fun promoteLegacyObject(json: JSONObject) {
        val exactUrl = firstNetworkUrl(json, "exactUrl", "sourceUrl", "requestUrl", "url") ?: return
        val pageUrl = firstNetworkUrl(json, "pageUrl", "referrer", "referer")
        val headers = legacyHeaders(json.opt("headers")) + legacyHeaders(json.opt("requestHeaders"))
        if (headers.isEmpty() && !containsSensitiveUrlMaterial(exactUrl)) return
        val summary = "Migrated legacy sidecar request material; network approvals reset for review"
        val downloadId = firstId(json, "downloadId")
        val captureId = firstId(json, "captureId", "mediaCaptureId")
        val commandId = firstId(json, "commandId", "automationCommandId")
        val variantId = firstId(json, "variantId", "mediaVariantId")
        when {
            downloadId != null -> MediaRequestHandoffStore.remember(
                downloadId = downloadId,
                headers = headers,
                redactedSummary = summary,
                isExpiringUrl = headers.isNotEmpty() || ExternalUrlPolicy.hasCredentialBearingQuery(exactUrl),
                exactUrl = exactUrl,
                pageUrl = pageUrl,
                privateNetworkApproved = false,
                cleartextCredentialsApproved = false,
            )
            captureId != null -> MediaRequestHandoffStore.rememberCapture(
                captureId = captureId,
                headers = headers,
                redactedSummary = summary,
                isExpiringUrl = headers.isNotEmpty() || ExternalUrlPolicy.hasCredentialBearingQuery(exactUrl),
                exactUrl = exactUrl,
                pageUrl = pageUrl,
                privateNetworkApproved = false,
                cleartextCredentialsApproved = false,
            )
            commandId != null -> MediaRequestHandoffStore.rememberCommand(
                commandId = commandId,
                exactUrl = exactUrl,
                pageUrl = pageUrl,
                headers = headers,
                redactedSummary = summary,
                privateNetworkApproved = false,
                cleartextCredentialsApproved = false,
            )
            variantId != null -> MediaRequestHandoffStore.rememberVariant(
                variantId = variantId,
                exactUrl = exactUrl,
                headers = headers,
                redactedSummary = summary,
            )
        }
    }

    private fun firstNetworkUrl(json: JSONObject, vararg keys: String): String? = keys.asSequence()
        .mapNotNull { key -> json.optString(key, "").trim().takeIf(String::isNotBlank) }
        .firstOrNull(::looksLikeNetworkUrl)

    private fun firstId(json: JSONObject, vararg keys: String): String? = keys.asSequence()
        .mapNotNull { key -> json.optString(key, "").trim().takeIf(String::isNotBlank) }
        .firstOrNull()

    private fun legacyHeaders(value: Any?): Map<String, String> = when (value) {
        is JSONObject -> value.keys().asSequence().mapNotNull { name ->
            val headerValue = value.optString(name, "").takeIf(String::isNotBlank) ?: return@mapNotNull null
            name to headerValue
        }.toMap()
        is String -> value.lineSequence().mapNotNull { line ->
            val separator = line.indexOf(':')
            if (separator <= 0) return@mapNotNull null
            val name = line.substring(0, separator).trim()
            val headerValue = line.substring(separator + 1).trim()
            if (name.isBlank() || headerValue.isBlank()) null else name to headerValue
        }.toMap()
        else -> emptyMap()
    }

    /** Redacts by value as well as key so a signed URL stored under `value`, `source`, etc. is not missed. */
    private fun redactJson(value: Any?) {
        when (value) {
            is JSONObject -> value.keys().asSequence().toList().forEach { key ->
                val child = value.opt(key)
                when {
                    child is JSONObject || child is JSONArray -> redactJson(child)
                    key.isSensitiveKey() -> value.put(key, "<redacted>")
                    child is String && looksLikeNetworkUrl(child) -> value.put(key, persistedUrl(child))
                }
            }
            is JSONArray -> for (index in 0 until value.length()) {
                val child = value.opt(index)
                when {
                    child is JSONObject || child is JSONArray -> redactJson(child)
                    child is String && looksLikeNetworkUrl(child) -> value.put(index, persistedUrl(child))
                }
            }
        }
    }

    private fun String.isSensitiveKey(): Boolean {
        val lower = lowercase()
        return lower.contains("header") || lower.contains("cookie") || lower.contains("authorization") ||
            lower.contains("token") || lower.contains("secret") || lower.contains("password") || lower.contains("credential")
    }

    private fun persistedUrl(raw: String): String = ExternalUrlPolicy.persistableUrl(raw)
        ?: runCatching {
            val uri = URI(raw.trim())
            URI(uri.scheme, null, uri.host, uri.port, uri.rawPath, null, null).toASCIIString()
        }.getOrDefault(raw.substringBefore('?').substringBefore('#'))

    private fun looksLikeNetworkUrl(raw: String): Boolean = runCatching {
        URI(raw.trim()).scheme?.lowercase() in setOf("http", "https", "ftp", "magnet")
    }.getOrDefault(false)

    private fun containsSensitiveUrlMaterial(raw: String): Boolean = runCatching {
        val uri = URI(raw)
        uri.rawUserInfo != null || uri.rawFragment != null || !uri.rawQuery.isNullOrBlank()
    }.getOrDefault(false)

    private fun writeMarkerAtomically() {
        atomicWriteText(marker, "complete\n")
    }

    private fun atomicWriteText(target: File, content: String) {
        target.parentFile?.mkdirs()
        val atomicFile = AtomicFile(target)
        val output = atomicFile.startWrite()
        try {
            output.write(content.toByteArray(Charsets.UTF_8))
            atomicFile.finishWrite(output)
        } catch (error: Throwable) {
            atomicFile.failWrite(output)
            throw error
        }
    }

    companion object {
        private const val MAX_JSON_BYTES = 4L * 1024L * 1024L
    }
}
