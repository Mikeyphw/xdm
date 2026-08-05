package com.mikeyphw.xdm.android.model

import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

/** Durable external automation command sources. These are persisted so repeated Tasker,
 * browser, or share-sheet deliveries can be recognized without duplicating downloads. */
enum class AutomationCommandSource { ShareSheet, ViewIntent, Tasker, BrowserExtension, DeepLink, Internal }
enum class AutomationCommandAction { EnqueueDownload, PromptAddDownload, CaptureMedia, PauseAll, ResumeAll, Unknown }
enum class AutomationCommandStatus { Received, Claimed, Executing, Applied, Accepted, Duplicate, Rejected, Executed, Failed }
enum class ExternalCommandAuthorization { Untrusted, UserConfirmed, IntegrationToken }
enum class ExternalNetworkTarget { Public, Loopback, PrivateAddress, LinkLocal, LocalHostname, Reserved, Unknown }
enum class AutomationRejectionReason { None, MissingUrl, UnsupportedAction, UnsupportedUrl, SensitivePayloadRejected, BackendUnavailable, NoMediaDetected, Duplicate }

data class AutomationCommandDraft(
    val source: AutomationCommandSource,
    val action: AutomationCommandAction,
    val url: String? = null,
    val fileName: String? = null,
    val pageTitle: String? = null,
    val pageUrl: String? = null,
    val explicitIdempotencyKey: String? = null,
    /** Platform-observed caller package when Android can establish one. Never caller supplied. */
    val originPackage: String? = null,
    /** Caller-claimed origin retained only for diagnostics. Never used for authorization or labels. */
    val claimedOriginPackage: String? = null,
    val verifiedIntegrationId: String? = null,
    val authorization: ExternalCommandAuthorization = ExternalCommandAuthorization.Untrusted,
    val privateNetworkApproved: Boolean = false,
    val cleartextCredentialsApproved: Boolean = false,
    val rawHeaders: String? = null,
    val mimeType: String? = null,
    /** Browser extension media kind hint, for example hls/dash/video/audio. It is sanitized
     * by the browser deep-link parser and used only for classification and diagnostics. */
    val mediaKind: String? = null,
    val contentLength: Long? = null,
    val frameUrl: String? = null,
    val stableMediaId: String? = null,
    val sessionRevision: Long? = null,
    val proposedHeaders: String? = null,
    val finalHeaders: String? = null,
    val pageObservationNonce: String? = null,
    val pageObservationCreatedAtEpochMs: Long? = null,
    val pageObservationExpiresAtEpochMs: Long? = null,
    val receivedAtEpochMs: Long = System.currentTimeMillis(),
) {
    val normalizedUrl: String? get() = ExternalUrlPolicy.normalizedUrl(url)
    val normalizedPageUrl: String? get() = ExternalUrlPolicy.normalizedUrl(pageUrl)
    val originHost: String? get() = ExternalUrlPolicy.originHost(normalizedPageUrl ?: normalizedUrl)
    val sanitizedHeaders: String? get() = ExternalUrlPolicy.sanitizeHeaders(rawHeaders)
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
    /** Platform-observed caller package. */
    val originPackage: String? = null,
    val claimedOriginPackage: String? = null,
    val verifiedIntegrationId: String? = null,
    val authorization: ExternalCommandAuthorization = ExternalCommandAuthorization.Untrusted,
    val privateNetworkApproved: Boolean = false,
    val cleartextCredentialsApproved: Boolean = false,
    val originHost: String? = null,
    val sanitizedHeaders: String? = null,
    val rejectionReason: AutomationRejectionReason = AutomationRejectionReason.None,
)

object ExternalUrlPolicy {
    private val externalUrlPattern = Regex("""(?:https?|ftp)://[^\s<>()\[\]{}\"']+""", RegexOption.IGNORE_CASE)
    private val clipboardUrlPattern = Regex("""https?://[^\s<>()\[\]{}\"']+""", RegexOption.IGNORE_CASE)
    private val trailingNoise = Regex("""[),.;:!?]+$""")
    private val sensitiveQueryNames = setOf(
        "access_token", "auth", "auth_token", "code", "cookie", "credential", "expires", "key",
        "password", "policy", "session", "session_id", "sessionid", "sig", "signature", "secret", "token",
        "x_amz_credential", "x_amz_security_token", "x_amz_signature",
        "x_goog_credential", "x_goog_security_token", "x_goog_signature",
    )
    private val sensitiveQuerySuffixes = setOf(
        "_auth", "_credential", "_key", "_password", "_secret", "_session", "_session_id", "_signature", "_token",
    )
    private val localHostSuffixes = setOf(".local", ".localhost", ".lan", ".home", ".internal")

    fun normalizedUrl(raw: String?): String? {
        val candidate = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val extracted = externalUrlPattern.find(candidate)?.value ?: candidate
        val cleaned = extracted.trim().replace(trailingNoise, "")
        return normalizeDownloadUrl(cleaned)
    }

    /** URL safe for Room, diagnostics, clipboard previews, and backend metadata. Exact signed URLs
     * belong in SecureRequestEnvelopeStore and are resolved only at execution time. */
    fun persistableUrl(raw: String?): String? {
        val normalized = normalizedUrl(raw) ?: return null
        val uri = runCatching { URI(normalized) }.getOrNull() ?: return null
        val query = uri.rawQuery
            ?.split('&')
            ?.filter(String::isNotBlank)
            ?.joinToString("&") { part -> redactQueryParameter(part, "REDACTED") }
            ?.takeIf(String::isNotBlank)
        val port = when {
            uri.port == -1 -> ""
            uri.scheme.equals("http", true) && uri.port == 80 -> ""
            uri.scheme.equals("https", true) && uri.port == 443 -> ""
            uri.scheme.equals("ftp", true) && uri.port == 21 -> ""
            else -> ":${uri.port}"
        }
        return buildString {
            append(uri.scheme.lowercase(Locale.US)).append("://").append(uri.host.lowercase(Locale.US)).append(port)
            append(uri.rawPath?.takeIf(String::isNotBlank) ?: "/")
            if (!query.isNullOrBlank()) append('?').append(query)
        }
    }

    fun hasCredentialBearingQuery(raw: String?): Boolean {
        val uri = normalizedUrl(raw)?.let { runCatching { URI(it) }.getOrNull() } ?: return false
        return uri.rawQuery?.split('&').orEmpty().any { part ->
            isSensitiveQueryName(part.substringBefore('=', missingDelimiterValue = part).lowercase(Locale.US))
        }
    }

    fun isCleartext(raw: String?): Boolean = normalizedUrl(raw)
        ?.substringBefore(':')
        ?.lowercase(Locale.US)
        .let { it == "http" || it == "ftp" }

    fun classifyNetworkTarget(raw: String?): ExternalNetworkTarget {
        val uri = normalizedUrl(raw)?.let { runCatching { URI(it) }.getOrNull() } ?: return ExternalNetworkTarget.Unknown
        val host = uri.host?.trim()?.lowercase(Locale.US) ?: return ExternalNetworkTarget.Unknown
        if (host == "localhost" || host == "ip6-localhost") return ExternalNetworkTarget.Loopback
        if (host.endsWithAny(localHostSuffixes) || '.' !in host && ':' !in host) return ExternalNetworkTarget.LocalHostname
        parseIpLiteral(host)?.let { address ->
            if (address.isAnyLocalAddress || address.isLoopbackAddress) return ExternalNetworkTarget.Loopback
            if (address.isLinkLocalAddress) return ExternalNetworkTarget.LinkLocal
            if (address.isSiteLocalAddress || address.isIpv6UniqueLocal()) return ExternalNetworkTarget.PrivateAddress
            if (address.isMulticastAddress || address.isReservedIpv4()) return ExternalNetworkTarget.Reserved
        }
        return ExternalNetworkTarget.Public
    }

    fun requiresPrivateNetworkApproval(raw: String?): Boolean = classifyNetworkTarget(raw) != ExternalNetworkTarget.Public

    fun urlsInText(text: String): List<String> = clipboardUrlPattern.findAll(text)
        .mapNotNull { normalizedUrl(it.value) }
        .distinct()
        .toList()

    fun originHost(raw: String?): String? = normalizedUrl(raw)?.let { url ->
        runCatching { URI(url).host?.lowercase(Locale.US)?.takeIf { it.isNotBlank() } }.getOrNull()
    }

    fun sanitizeHeaders(raw: String?): String? = PrivacyDiagnosticsRedactor.redactHeaders(raw)

    private fun normalizeDownloadUrl(raw: String): String? {
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.US) ?: return null
        if (scheme != "http" && scheme != "https" && scheme != "ftp") return null
        if (uri.userInfo != null || uri.fragment != null) return null
        val host = uri.host?.lowercase(Locale.US)?.takeIf { it.isNotBlank() } ?: return null
        val port = when {
            uri.port == -1 -> ""
            scheme == "http" && uri.port == 80 -> ""
            scheme == "https" && uri.port == 443 -> ""
            scheme == "ftp" && uri.port == 21 -> ""
            else -> ":${uri.port}"
        }
        val rawPath = uri.rawPath?.takeIf { it.isNotBlank() } ?: "/"
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        return "$scheme://$host$port$rawPath$query"
    }

    internal fun isSensitiveQueryName(rawName: String): Boolean {
        val name = normalizeQueryName(rawName)
        if (name in sensitiveQueryNames) return true
        return sensitiveQuerySuffixes.any { suffix -> name.endsWith(suffix) }
    }

    internal fun redactQueryParameter(part: String, replacement: String): String {
        val separator = part.indexOf('=')
        val rawName = if (separator >= 0) part.substring(0, separator) else part
        return if (isSensitiveQueryName(rawName)) "$rawName=$replacement" else part
    }

    private fun normalizeQueryName(rawName: String): String {
        val decoded = runCatching { URLDecoder.decode(rawName, StandardCharsets.UTF_8) }.getOrDefault(rawName)
        return decoded
            .trim()
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
    }

    private fun String.endsWithAny(suffixes: Set<String>): Boolean = suffixes.any(::endsWith)

    private fun parseIpLiteral(host: String): InetAddress? {
        val candidate = host.removePrefix("[").removeSuffix("]")
        val looksIpv4 = candidate.matches(Regex("""\d{1,3}(?:\.\d{1,3}){3}""")) &&
            candidate.split('.').all { it.toIntOrNull() in 0..255 }
        val looksIpv6 = ':' in candidate
        if (!looksIpv4 && !looksIpv6) return null
        return runCatching { InetAddress.getByName(candidate) }.getOrNull()
    }

    private fun InetAddress.isIpv6UniqueLocal(): Boolean = this is Inet6Address &&
        address.firstOrNull()?.toInt()?.and(0xFE) == 0xFC

    private fun InetAddress.isReservedIpv4(): Boolean {
        val bytes = address
        if (bytes.size != 4) return false
        val first = bytes[0].toInt() and 0xFF
        val second = bytes[1].toInt() and 0xFF
        return first == 0 || first >= 224 || (first == 100 && second in 64..127)
    }
}


/** Compatibility facade retained while older integrations migrate to the browser-neutral name. */
@Deprecated("Use ExternalUrlPolicy", ReplaceWith("ExternalUrlPolicy"))
object BrowserHandoffPolicy {
    fun normalizedUrl(raw: String?): String? = ExternalUrlPolicy.normalizedUrl(raw)
    fun urlsInText(text: String): List<String> = ExternalUrlPolicy.urlsInText(text)
    fun originHost(raw: String?): String? = ExternalUrlPolicy.originHost(raw)
    fun sanitizeHeaders(raw: String?): String? = ExternalUrlPolicy.sanitizeHeaders(raw)
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
        val normalizedUrl = ExternalUrlPolicy.normalizedUrl(url)
        val normalizedPage = ExternalUrlPolicy.normalizedUrl(pageUrl)
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
