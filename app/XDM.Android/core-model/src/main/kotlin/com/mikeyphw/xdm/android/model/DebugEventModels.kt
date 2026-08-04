package com.mikeyphw.xdm.android.model

import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Runtime area that emitted a debug event. Values are stable for support-bundle filtering. */
enum class DebugArea {
    BrowserBridge,
    ExternalIntent,
    AddDownload,
    MediaSniffing,
    TransferPlanner,
    Scheduler,
    Notification,
    FileOpen,
    Extension,
    Validation,
}

/** Privacy-safe event severity. Debug output is bounded and redacted even for Trace events. */
enum class DebugSeverity { Trace, Info, Warning, Error }

data class DebugEvent(
    val sessionId: String,
    val timestampMillis: Long,
    val area: DebugArea,
    val severity: DebugSeverity,
    val action: String,
    val result: String,
    val safeDetails: Map<String, String> = emptyMap(),
    val id: String = DebugRedactor.fingerprint(
        listOf(sessionId, timestampMillis.toString(), area.name, severity.name, action, result, safeDetails.hashCode().toString())
            .joinToString("|"),
    ),
) {
    fun toJsonLine(): String {
        val redactedDetails = DebugRedactor.redactDetails(safeDetails)
        return buildString {
            append('{')
            appendJson("id", id)
            append(',')
            appendJson("sessionId", sessionId)
            append(',')
            append("\"timestampMillis\":").append(timestampMillis)
            append(',')
            appendJson("area", area.name)
            append(',')
            appendJson("severity", severity.name)
            append(',')
            appendJson("action", DebugRedactor.redactText(action))
            append(',')
            appendJson("result", DebugRedactor.redactText(result))
            append(',')
            append("\"safeDetails\":{")
            redactedDetails.entries.forEachIndexed { index, (key, value) ->
                if (index > 0) append(',')
                appendJson(key, value)
            }
            append("}}")
        }
    }

    private fun StringBuilder.appendJson(key: String, value: String?) {
        append('"').append(DebugRedactor.jsonEscape(key)).append("\":")
        if (value == null) {
            append("null")
        } else {
            append('"').append(DebugRedactor.jsonEscape(value)).append('"')
        }
    }
}

/** Sink abstraction so runtime code can be instrumented without forcing debug storage to exist. */
interface DebugEventRecorder {
    fun record(event: DebugEvent)

    fun record(
        area: DebugArea,
        severity: DebugSeverity = DebugSeverity.Info,
        action: String,
        result: String,
        safeDetails: Map<String, String> = emptyMap(),
        sessionId: String = "current",
        timestampMillis: Long = System.currentTimeMillis(),
    ) = record(
        DebugEvent(
            sessionId = sessionId,
            timestampMillis = timestampMillis,
            area = area,
            severity = severity,
            action = action,
            result = result,
            safeDetails = safeDetails,
        ),
    )
}

object NoOpDebugEventRecorder : DebugEventRecorder {
    override fun record(event: DebugEvent) = Unit
}

/** Optional application hook for later Debug Workbench UI phases. */
interface DebugRecorderProvider {
    val debugEventRecorder: DebugEventRecorder
}

/** Privacy redactor used before anything enters a debug timeline or support bundle. */
object DebugRedactor {
    private val sensitiveKeyMarkers = setOf(
        "authorization",
        "cookie",
        "set-cookie",
        "proxy-authorization",
        "token",
        "secret",
        "password",
        "session",
        "signature",
        "sig",
        "auth",
        "key",
    )
    private val jsonSecretValuePattern = Regex(
        """(?i)("(?:authorization|proxy-authorization|cookie|set-cookie|token|secret|password|session|signature|sig|api[_-]?key|access[_-]?key|refresh[_-]?token)"\s*:\s*")[^"]*(")""",
    )
    private val bearerPattern = Regex("(?i)\\b(bearer|basic)\\s+[A-Za-z0-9._~+/=-]{8,}")
    private val queryParameterPattern = Regex("""([?&])([^=&#\s]+)=([^&#\s"']+)""")

    fun redactDetails(details: Map<String, String>): Map<String, String> = details
        .entries
        .associate { (key, value) -> jsonSafeKey(key) to redactValueForKey(key, value) }

    fun redactValueForKey(key: String, value: String?): String {
        val normalizedKey = key.trim().lowercase(Locale.US)
        if (normalizedKey.isBlank()) return ""
        if (isSensitiveKey(normalizedKey)) return "<redacted>"
        return when {
            normalizedKey.contains("url") || normalizedKey == "uri" -> redactUrl(value)
            normalizedKey.contains("header") -> PrivacyDiagnosticsRedactor.redactHeaders(value) ?: ""
            else -> redactText(value)
        }
    }

    fun isSensitiveKey(key: String): Boolean = sensitiveKeyMarkers.any { marker -> key.lowercase(Locale.US).contains(marker) } ||
        PrivacyDiagnosticsRedactor.isSensitiveHeaderName(key)

    fun redactText(value: String?): String {
        val text = value?.trim()?.takeIf { it.isNotBlank() } ?: return ""
        return text
            .replace(bearerPattern) { match -> match.groupValues[1].lowercase(Locale.US) + " <redacted>" }
            .replace(queryParameterPattern) { match ->
                val part = "${match.groupValues[2]}=${match.groupValues[3]}"
                match.groupValues[1] + ExternalUrlPolicy.redactQueryParameter(part, "<redacted>")
            }
            .take(512)
    }

    fun redactUrl(value: String?): String {
        val raw = value?.trim()?.takeIf { it.isNotBlank() } ?: return ""
        val uri = runCatching { URI(raw) }.getOrNull() ?: return redactText(raw)
        val scheme = uri.scheme?.lowercase(Locale.US) ?: return redactText(raw)
        val host = uri.host?.lowercase(Locale.US) ?: return redactText(raw)
        val port = when {
            uri.port == -1 -> ""
            scheme == "http" && uri.port == 80 -> ""
            scheme == "https" && uri.port == 443 -> ""
            else -> ":${uri.port}"
        }
        val path = uri.rawPath?.takeIf { it.isNotBlank() } ?: "/"
        val query = uri.rawQuery
            ?.split('&')
            ?.filter { it.isNotBlank() }
            ?.joinToString("&") { part -> ExternalUrlPolicy.redactQueryParameter(part, "<redacted>") }
            ?.takeIf { it.isNotBlank() }
            ?.let { "?$it" }
            .orEmpty()
        return "$scheme://$host$port$path$query"
    }

    fun redactExportLine(value: String): String = value
        .replace(jsonSecretValuePattern) { match -> match.groupValues[1] + "<redacted>" + match.groupValues[2] }
        .replace(bearerPattern) { match -> match.groupValues[1].lowercase(Locale.US) + " <redacted>" }
        .replace(queryParameterPattern) { match ->
            val part = "${match.groupValues[2]}=${match.groupValues[3]}"
            match.groupValues[1] + ExternalUrlPolicy.redactQueryParameter(part, "<redacted>")
        }
        .take(16 * 1024)

    fun jsonEscape(value: String): String = value.flatMap { char ->
        when (char) {
            '\\' -> listOf('\\', '\\')
            '"' -> listOf('\\', '"')
            '\n' -> listOf('\\', 'n')
            '\r' -> listOf('\\', 'r')
            '\t' -> listOf('\\', 't')
            else -> if (char.code < 0x20) "\\u%04x".format(char.code).toList() else listOf(char)
        }
    }.joinToString("")

    fun fingerprint(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
        .take(16)

    private fun jsonSafeKey(key: String): String = key.trim().take(80).ifBlank { "detail" }
}

/** Bounded private JSONL recorder. D1 intentionally avoids Room migrations and automatic upload. */
class RollingJsonlDebugEventRecorder(
    private val rootDirectory: File,
    private val sessionId: String = "current",
    private val maxSessionBytes: Long = 2L * 1024L * 1024L,
    private val retainedSessions: Int = 5,
    private val clock: () -> Long = System::currentTimeMillis,
) : DebugEventRecorder {
    private val lock = Any()
    private val sessionsDirectory: File get() = File(rootDirectory, "sessions")
    private val currentFile: File get() = File(rootDirectory, "current.jsonl")

    override fun record(event: DebugEvent) {
        val safeEvent = event.copy(sessionId = event.sessionId.ifBlank { sessionId })
        val line = safeEvent.toJsonLine() + "\n"
        synchronized(lock) {
            rootDirectory.mkdirs()
            sessionsDirectory.mkdirs()
            rotateIfNeeded(line.toByteArray(Charsets.UTF_8).size.toLong())
            currentFile.appendText(line, Charsets.UTF_8)
        }
    }

    fun copySanitizedTimeline(maxChars: Int = 64 * 1024): String = synchronized(lock) {
        if (!currentFile.isFile) return@synchronized ""
        currentFile.readText(Charsets.UTF_8).takeLast(maxChars)
    }

    fun clear() = synchronized(lock) {
        if (rootDirectory.exists()) rootDirectory.deleteRecursively()
        rootDirectory.mkdirs()
        sessionsDirectory.mkdirs()
    }

    fun exportSupportBundle(
        destinationZip: File,
        metadata: Map<String, String> = emptyMap(),
    ): File = synchronized(lock) {
        destinationZip.parentFile?.mkdirs()
        ZipOutputStream(FileOutputStream(destinationZip)).use { zip ->
            if (currentFile.isFile) {
                zip.putNextEntry(ZipEntry("debug-session.jsonl"))
                currentFile.useLines(Charsets.UTF_8) { lines ->
                    lines.forEach { line ->
                        zip.write((DebugRedactor.redactExportLine(line) + "\n").toByteArray(Charsets.UTF_8))
                    }
                }
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("debug-metadata.txt"))
            val redactedMetadata = DebugRedactor.redactDetails(metadata)
                .entries
                .joinToString("\n") { (key, value) -> "$key=$value" }
            zip.write(redactedMetadata.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("redaction-report.txt"))
            zip.write(
                "XDM Debug Workbench D1 redacted support bundle. No automatic upload. Cookie, Authorization, token, signature, session, and key-like values are redacted before export.\n"
                    .toByteArray(Charsets.UTF_8),
            )
            zip.closeEntry()
        }
        destinationZip
    }

    private fun rotateIfNeeded(nextBytes: Long) {
        val file = currentFile
        if (!file.exists() || file.length() + nextBytes <= maxSessionBytes) return
        val rotatedName = "debug-${clock()}-${DebugRedactor.fingerprint(sessionId)}.jsonl"
        val rotated = File(sessionsDirectory, rotatedName)
        file.renameTo(rotated)
        sessionsDirectory
            .listFiles { candidate -> candidate.isFile && candidate.name.endsWith(".jsonl") }
            .orEmpty()
            .sortedByDescending(File::lastModified)
            .drop(retainedSessions)
            .forEach(File::delete)
    }
}
