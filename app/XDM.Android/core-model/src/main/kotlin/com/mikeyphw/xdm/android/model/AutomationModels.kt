package com.mikeyphw.xdm.android.model

import java.net.URI
import java.security.MessageDigest
import java.util.Locale

/** Durable external automation command sources. These are persisted so repeated Tasker,
 * browser, or share-sheet deliveries can be recognized without duplicating downloads. */
enum class AutomationCommandSource { ShareSheet, ViewIntent, Tasker, BrowserExtension, DeepLink, Internal }
enum class AutomationCommandAction { EnqueueDownload, CaptureMedia, PauseAll, ResumeAll, Unknown }
enum class AutomationCommandStatus { Accepted, Duplicate, Rejected, Executed, Failed }
enum class AutomationRejectionReason { None, MissingUrl, UnsupportedAction, UnsupportedUrl, SensitivePayloadRejected, BackendUnavailable, NoMediaDetected, Duplicate }

data class AutomationCommandDraft(
    val source: AutomationCommandSource,
    val action: AutomationCommandAction,
    val url: String? = null,
    val fileName: String? = null,
    val pageTitle: String? = null,
    val pageUrl: String? = null,
    val explicitIdempotencyKey: String? = null,
    val originPackage: String? = null,
    val rawHeaders: String? = null,
) {
    val normalizedUrl: String? get() = BrowserHandoffPolicy.normalizedUrl(url)
    val normalizedPageUrl: String? get() = BrowserHandoffPolicy.normalizedUrl(pageUrl)
    val originHost: String? get() = BrowserHandoffPolicy.originHost(normalizedPageUrl ?: normalizedUrl)
    val sanitizedHeaders: String? get() = BrowserHandoffPolicy.sanitizeHeaders(rawHeaders)
    val stableIdempotencyKey: String get() = AutomationCommandIds.stableKey(this)
}

data class AutomationCommandRecord(
    val id: String,
    val idempotencyKey: String,
    val source: AutomationCommandSource,
    val action: AutomationCommandAction,
    val url: String?,
    val fileName: String?,
    val pageTitle: String?,
    val pageUrl: String?,
    val mediaCaptureId: String?,
    val downloadId: String?,
    val status: AutomationCommandStatus,
    val resultMessage: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val originPackage: String? = null,
    val originHost: String? = null,
    val sanitizedHeaders: String? = null,
    val rejectionReason: AutomationRejectionReason = AutomationRejectionReason.None,
)

object BrowserHandoffPolicy {
    private val urlPattern = Regex("""https?://[^\s<>()\[\]{}\"']+""", RegexOption.IGNORE_CASE)
    private val trailingNoise = Regex("""[),.;:!?]+$""")
    private val sensitiveHeaderNames = setOf("authorization", "cookie", "set-cookie", "proxy-authorization", "x-api-key", "x-auth-token")

    fun normalizedUrl(raw: String?): String? {
        val candidate = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val extracted = urlPattern.find(candidate)?.value ?: candidate
        val cleaned = extracted.trim().replace(trailingNoise, "")
        return normalizeHttpUrl(cleaned)
    }

    fun originHost(raw: String?): String? = normalizedUrl(raw)?.let { url ->
        runCatching { URI(url).host?.lowercase(Locale.US)?.takeIf { it.isNotBlank() } }.getOrNull()
    }

    fun sanitizeHeaders(raw: String?): String? {
        val lines = raw?.lineSequence()?.map(String::trim)?.filter { it.isNotBlank() }?.toList().orEmpty()
        if (lines.isEmpty()) return null
        return lines.mapNotNull { line ->
            val name = line.substringBefore(':', missingDelimiterValue = line).trim().lowercase(Locale.US)
            if (name.isBlank()) return@mapNotNull null
            if (name in sensitiveHeaderNames) "$name: <redacted>" else line.take(160)
        }.joinToString("\n").takeIf { it.isNotBlank() }
    }

    private fun normalizeHttpUrl(raw: String): String? {
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.US) ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.lowercase(Locale.US)?.takeIf { it.isNotBlank() } ?: return null
        val port = when {
            uri.port == -1 -> ""
            scheme == "http" && uri.port == 80 -> ""
            scheme == "https" && uri.port == 443 -> ""
            else -> ":${uri.port}"
        }
        val rawPath = uri.rawPath?.takeIf { it.isNotBlank() } ?: "/"
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        return "$scheme://$host$port$rawPath$query"
    }
}

object AutomationCommandIds {
    fun stableKey(draft: AutomationCommandDraft): String {
        val explicit = draft.explicitIdempotencyKey?.trim()?.takeIf { it.isNotBlank() }
        if (explicit != null) return "external:${draft.source.name}:$explicit"
        return stableKey(
            source = draft.source,
            action = draft.action,
            url = draft.url,
            fileName = draft.fileName,
            pageUrl = draft.pageUrl,
        )
    }

    fun stableKey(
        source: AutomationCommandSource,
        action: AutomationCommandAction,
        url: String?,
        fileName: String? = null,
        pageUrl: String? = null,
    ): String {
        val normalizedUrl = BrowserHandoffPolicy.normalizedUrl(url)
        val normalizedPage = BrowserHandoffPolicy.normalizedUrl(pageUrl)
        val sourcePart = if (normalizedUrl != null || normalizedPage != null) "external-handoff" else source.name
        val raw = listOf(
            sourcePart,
            action.name,
            normalizedUrl.normalizedCommandPart(),
            fileName.normalizedCommandPart(),
            normalizedPage.normalizedCommandPart(),
        ).joinToString("|")
        return "auto:" + sha256(raw).take(32)
    }

    fun commandId(idempotencyKey: String): String = "cmd-" + sha256(idempotencyKey).take(32)

    private fun String?.normalizedCommandPart(): String = this?.trim()?.lowercase(Locale.US).orEmpty()

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}
