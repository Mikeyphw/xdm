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
import kotlinx.coroutines.delay

fun interface TransferRequestSecurityGuard {
    suspend fun validate(request: DownloadRequest)

    companion object {
        val AllowAll = TransferRequestSecurityGuard { }
    }
}

/** Resolver seam used to make bounded DNS validation deterministic in tests and to keep the
 * security decision separate from the transport implementation. */
fun interface TransferHostnameResolver {
    fun resolve(hostname: String): List<InetAddress>

    companion object {
        val System = TransferHostnameResolver { hostname -> InetAddress.getAllByName(hostname).toList() }
    }
}

enum class TransferSecurityFailureKind {
    DnsResolutionFailed,
    UnsafeNetworkTarget,
}

class TransferRequestSecurityException(
    val kind: TransferSecurityFailureKind,
    message: String,
    cause: Throwable? = null,
) : SecurityException(message, cause)

/**
 * A target whose URL policy, exact-URL approval scope and resolved addresses were checked together.
 * Network clients that can pin DNS should connect only to [addresses], preventing a second DNS
 * lookup from racing the security decision.
 */
data class ValidatedTransferNetworkTarget(
    val url: String,
    val host: String,
    val approvalScope: String,
    val addresses: List<InetAddress>,
)

/**
 * Applies one request-security contract to the primary URL and every mirror before a backend may
 * receive the request. Explicit approvals are accepted only when their opaque scope matches the
 * exact URL that was reviewed. Native HLS additionally consumes [validateAndResolveTarget]'s
 * resolved addresses so OkHttp connects to the exact routes that passed this policy check instead
 * of resolving the hostname again after validation.
 */
class AndroidTransferRequestSecurityGuard(
    @Suppress("UNUSED_PARAMETER") context: Context? = null,
    private val hostnameResolver: TransferHostnameResolver = TransferHostnameResolver.System,
    private val dnsAttempts: Int = DEFAULT_DNS_ATTEMPTS,
    private val dnsRetryDelayMillis: Long = DEFAULT_DNS_RETRY_DELAY_MILLIS,
) : TransferRequestSecurityGuard {

    init {
        require(dnsAttempts >= 1) { "DNS validation must make at least one attempt" }
        require(dnsRetryDelayMillis >= 0L) { "DNS retry delay cannot be negative" }
    }

    override suspend fun validate(request: DownloadRequest) {
        validateHeaderSurface(request.headers)
        if (request.requestKind == DownloadRequestKind.Magnet) {
            validateMagnet(request)
            return
        }

        val targets = buildList {
            add(request.sourceUrl)
            request.mirrors.forEach { mirror -> if (mirror !in this) add(mirror) }
        }
        for (target in targets) {
            validateAndResolveTargetInternal(request, target, headersAlreadyValidated = true)
        }
    }

    /**
     * Validates one exact target and returns the addresses that passed the private-network policy.
     * This is intentionally suspendable: transient DNS lookup failures receive a short bounded
     * retry window, while an unsafe resolved route is rejected immediately and is never retried.
     */
    suspend fun validateAndResolveTarget(
        request: DownloadRequest,
        target: String = request.sourceUrl,
    ): ValidatedTransferNetworkTarget {
        validateHeaderSurface(request.headers)
        if (request.requestKind == DownloadRequestKind.Magnet) {
            throw SecurityException("Magnet handoffs do not expose an HTTP network target")
        }
        return validateAndResolveTargetInternal(request, target, headersAlreadyValidated = true)
    }

    private fun validateMagnet(request: DownloadRequest) {
        val uri = runCatching { URI(request.sourceUrl) }.getOrNull()
            ?: throw SecurityException("The magnet handoff is malformed")
        if (!uri.scheme.equals("magnet", true) || uri.rawSchemeSpecificPart.isNullOrBlank()) {
            throw SecurityException("The magnet handoff is malformed")
        }
        if (request.headers.isNotEmpty() || request.mirrors.isNotEmpty()) {
            throw SecurityException("Magnet handoffs cannot inherit HTTP headers or mirrors")
        }
    }

    private suspend fun validateAndResolveTargetInternal(
        request: DownloadRequest,
        target: String,
        @Suppress("UNUSED_PARAMETER") headersAlreadyValidated: Boolean,
    ): ValidatedTransferNetworkTarget {
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
        val addresses = resolveWithBoundedRetry(host)
        val resolvedPrivate = addresses.any(::isPrivateOrSpecial)
        if (literalClassification != ExternalNetworkTarget.Public || resolvedPrivate) {
            val approved = request.privateNetworkApproved && approvalScope in request.privateNetworkApprovalScopes
            if (!approved) {
                throw TransferRequestSecurityException(
                    kind = TransferSecurityFailureKind.UnsafeNetworkTarget,
                    message = "This transfer targets a local, private, link-local, reserved, or unresolved network address and requires approval for this exact URL",
                )
            }
        }
        return ValidatedTransferNetworkTarget(
            url = target,
            host = host,
            approvalScope = approvalScope,
            addresses = addresses,
        )
    }

    private suspend fun resolveWithBoundedRetry(host: String): List<InetAddress> {
        var lastFailure: Throwable? = null
        repeat(dnsAttempts) { index ->
            try {
                val addresses = hostnameResolver.resolve(host)
                if (addresses.isNotEmpty()) return addresses
                lastFailure = IllegalStateException("Resolver returned no addresses")
            } catch (error: Throwable) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                lastFailure = error
            }
            if (index + 1 < dnsAttempts && dnsRetryDelayMillis > 0L) {
                delay(dnsRetryDelayMillis * (index + 1L))
            }
        }
        throw TransferRequestSecurityException(
            kind = TransferSecurityFailureKind.DnsResolutionFailed,
            message = "The transfer hostname could not be resolved safely after bounded retries",
            cause = lastFailure,
        )
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
        const val DEFAULT_DNS_ATTEMPTS = 3
        const val DEFAULT_DNS_RETRY_DELAY_MILLIS = 200L
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
