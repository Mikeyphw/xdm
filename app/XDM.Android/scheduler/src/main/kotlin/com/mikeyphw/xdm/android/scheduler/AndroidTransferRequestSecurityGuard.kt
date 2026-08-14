package com.mikeyphw.xdm.android.scheduler

import android.content.Context
import android.security.NetworkSecurityPolicy
import com.mikeyphw.xdm.android.model.ExternalNetworkTarget
import com.mikeyphw.xdm.android.model.ExternalUrlPolicy
import com.mikeyphw.xdm.android.transfer.DownloadRequest
import com.mikeyphw.xdm.android.transfer.DownloadRequestApprovalScope
import com.mikeyphw.xdm.android.transfer.DownloadRequestKind
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

fun interface TransferRequestSecurityGuard {
    suspend fun validate(request: DownloadRequest)

    companion object {
        val AllowAll = TransferRequestSecurityGuard { }
    }
}

/**
 * Applies one request-security contract to the primary URL and every mirror before a backend may
 * receive the request. Explicit approvals are accepted only when their opaque scope matches the
 * exact URL that was reviewed. Redirects are separately rechecked by the native network
 * interceptor; aria2 is configured with redirects disabled.
 */
class AndroidTransferRequestSecurityGuard(
    @Suppress("UNUSED_PARAMETER") context: Context,
) : TransferRequestSecurityGuard {

    override suspend fun validate(request: DownloadRequest) {
        validateHeaderSurface(request.headers)
        if (request.requestKind == DownloadRequestKind.Magnet) {
            val uri = runCatching { URI(request.sourceUrl) }.getOrNull()
                ?: throw SecurityException("The magnet handoff is malformed")
            if (!uri.scheme.equals("magnet", true) || uri.rawSchemeSpecificPart.isNullOrBlank()) {
                throw SecurityException("The magnet handoff is malformed")
            }
            if (request.headers.isNotEmpty() || request.mirrors.isNotEmpty()) {
                throw SecurityException("Magnet handoffs cannot inherit HTTP headers or mirrors")
            }
            return
        }

        val targets = buildList {
            add(request.sourceUrl)
            request.mirrors.forEach { mirror -> if (mirror !in this) add(mirror) }
        }
        targets.forEach { target -> validateNetworkTarget(request, target) }
    }

    private fun validateNetworkTarget(request: DownloadRequest, target: String) {
        val uri = runCatching { URI(target) }.getOrNull()
            ?: throw SecurityException("The transfer URL is malformed")
        if (uri.rawUserInfo != null || uri.rawFragment != null) {
            throw SecurityException("Transfer URLs cannot contain user-info credentials or fragments")
        }
        val scheme = uri.scheme?.lowercase()
            ?: throw SecurityException("The transfer URL has no scheme")
        if (scheme !in setOf("http", "https", "ftp")) {
            throw SecurityException("Unsupported transfer URL scheme: $scheme")
        }
        val host = uri.host?.takeIf(String::isNotBlank)
            ?: throw SecurityException("The transfer URL has no host")
        val approvalScope = DownloadRequestApprovalScope.forUrl(target)
            ?: throw SecurityException("The transfer URL cannot be bound to an approval scope")

        val cleartext = scheme == "http" || scheme == "ftp"
        if (cleartext && !NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted(host)) {
            throw SecurityException("Cleartext downloads are blocked by XDM network security policy. Use HTTPS.")
        }
        if (cleartext && (request.hasSensitiveHeaders() || ExternalUrlPolicy.hasCredentialBearingQuery(target))) {
            val approved = request.cleartextCredentialsApproved &&
                approvalScope in request.cleartextCredentialApprovalScopes
            if (!approved) {
                throw SecurityException("Sensitive request credentials cannot be sent over cleartext transport without approval for this exact URL")
            }
        }

        val literalClassification = ExternalUrlPolicy.classifyNetworkTarget(target)
        val addresses = runCatching { InetAddress.getAllByName(host).toList() }.getOrElse {
            throw SecurityException("The transfer hostname could not be resolved safely")
        }
        if (addresses.isEmpty()) throw SecurityException("The transfer hostname resolved to no addresses")
        val resolvedPrivate = addresses.any(::isPrivateOrSpecial)
        if (literalClassification != ExternalNetworkTarget.Public || resolvedPrivate) {
            val approved = request.privateNetworkApproved && approvalScope in request.privateNetworkApprovalScopes
            if (!approved) {
                throw SecurityException("This transfer targets a local, private, link-local, reserved, or unresolved network address and requires approval for this exact URL")
            }
        }
    }

    private fun validateHeaderSurface(headers: Map<String, String>) {
        headers.forEach { (name, value) ->
            if (name.isBlank() || name.any { it == '\r' || it == '\n' } || value.any { it == '\r' || it == '\n' }) {
                throw SecurityException("Unsafe request header")
            }
            if (!HEADER_NAME.matches(name)) throw SecurityException("Unsupported request header name")
            val normalized = name.lowercase()
            if (normalized !in ALLOWED_HEADERS && !normalized.startsWith("sec-fetch-")) {
                throw SecurityException("Unsupported request header")
            }
            if (name.equals("Host", true) || name.equals("Content-Length", true) || name.equals("Connection", true) ||
                name.equals("Transfer-Encoding", true) || name.equals("Proxy-Connection", true)) {
                throw SecurityException("Transport-owned request headers cannot be supplied by a handoff")
            }
        }
    }

    private fun DownloadRequest.hasSensitiveHeaders(): Boolean = headers.keys.any(::isSensitiveHeader)

    private fun isSensitiveHeader(name: String): Boolean {
        val normalized = name.trim().lowercase()
        return normalized in SENSITIVE_HEADERS || normalized.contains("token") || normalized.endsWith("-key")
    }

    private fun isPrivateOrSpecial(address: InetAddress): Boolean {
        if (
            address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress
        ) return true
        val bytes = address.address
        if (address is Inet6Address && bytes.firstOrNull()?.toInt()?.and(0xFE) == 0xFC) return true
        if (bytes.size == 4) {
            val first = bytes[0].toInt() and 0xFF
            val second = bytes[1].toInt() and 0xFF
            if (first == 0 || first >= 224 || first == 127 || (first == 100 && second in 64..127)) return true
        }
        return false
    }

    private companion object {
        val HEADER_NAME = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
        val ALLOWED_HEADERS = setOf(
            "accept", "accept-encoding", "accept-language", "authorization", "cookie", "origin",
            "referer", "range", "user-agent", "if-range", "if-none-match", "if-modified-since",
            "x-api-key", "x-auth-token", "x-access-token", "x-csrf-token",
        )
        val SENSITIVE_HEADERS = setOf(
            "authorization",
            "cookie",
            "proxy-authorization",
            "x-api-key",
            "api-key",
            "x-auth-token",
            "x-access-token",
            "x-csrf-token",
        )
    }
}
