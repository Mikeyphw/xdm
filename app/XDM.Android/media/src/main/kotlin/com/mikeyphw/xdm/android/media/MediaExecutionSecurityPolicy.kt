package com.mikeyphw.xdm.android.media

import com.mikeyphw.xdm.android.model.ExternalUrlPolicy
import java.net.URI
import java.util.Locale

/**
 * XAR12 execution-side network boundary shared by embedded FFmpeg and Termux fallback planning.
 * It is intentionally pure so media planning/tests can prove credentials and private-network
 * requests never cross from the app-owned verifier into an external process by accident.
 */
object MediaExecutionSecurityPolicy {
    private val sensitiveHeaderNames = setOf(
        "authorization",
        "cookie",
        "proxy-authorization",
        "referer",
        "origin",
        "x-api-key",
        "x-auth-token",
        "x-access-token",
    )

    fun originKey(url: String): String? = runCatching {
        val uri = URI(url)
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        val host = uri.host?.lowercase(Locale.ROOT)
        if (scheme.isNullOrBlank() || host.isNullOrBlank()) {
            null
        } else {
            val port = when {
                uri.port >= 0 -> uri.port
                scheme == "http" -> 80
                scheme == "https" -> 443
                else -> -1
            }
            if (port >= 0) "$scheme://$host:$port" else "$scheme://$host"
        }
    }.getOrNull()

    fun scopedHeadersFor(url: String, boundUrl: String?, headers: Map<String, String>): Map<String, String> {
        val targetOrigin = originKey(url)
        val boundOrigin = boundUrl?.let(::originKey)
        if (targetOrigin != null && targetOrigin == boundOrigin) return headers
        return headers.filterKeys { it.lowercase(Locale.ROOT) !in sensitiveHeaderNames }
    }

    fun requireEmbeddedExecutable(urls: Collection<String>, headers: Map<String, String>) {
        val blocked = remoteUrls(urls).filter { url ->
            ExternalUrlPolicy.hasCredentialBearingQuery(url) && !url.startsWith("https://", ignoreCase = true)
        }
        require(blocked.isEmpty()) {
            "Embedded FFmpeg refused cleartext credential-bearing media URL(s): ${blocked.joinToString { ExternalUrlPolicy.persistableUrl(it) ?: it.substringBefore('?') }}"
        }
        require(headers.keys.none { it.lowercase(Locale.ROOT) in sensitiveHeaderNames } || remoteUrls(urls).all { originKey(it) != null }) {
            "Embedded FFmpeg refused unscoped credential headers for media execution."
        }
    }

    fun termuxNetworkEligible(urls: Collection<String>, headers: Map<String, String>): Boolean {
        if (headers.keys.any { it.lowercase(Locale.ROOT) in sensitiveHeaderNames }) return false
        val remotes = remoteUrls(urls)
        if (remotes.isEmpty()) return false
        return remotes.all { url ->
            val normalized = ExternalUrlPolicy.normalizedUrl(url) ?: return@all false
            val scheme = normalized.substringBefore(':').lowercase(Locale.ROOT)
            scheme in setOf("https") &&
                !ExternalUrlPolicy.hasCredentialBearingQuery(normalized) &&
                !ExternalUrlPolicy.requiresPrivateNetworkApproval(normalized) &&
                !hasPrivateOrLocalHost(normalized)
        }
    }

    private fun remoteUrls(urls: Collection<String>): List<String> = urls.filter { value ->
        value.startsWith("http://", ignoreCase = true) || value.startsWith("https://", ignoreCase = true)
    }

    private fun hasPrivateOrLocalHost(url: String): Boolean = runCatching {
        val host = URI(url).host?.lowercase(Locale.ROOT) ?: return false
        host == "localhost" || host == "0.0.0.0" || host == "::1" || host.endsWith(".local") ||
            host.startsWith("127.") || host.startsWith("10.") || host.startsWith("192.168.") ||
            Regex("^172\\.(1[6-9]|2\\d|3[0-1])\\.").containsMatchIn(host)
    }.getOrDefault(false)
}
