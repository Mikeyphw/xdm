package com.mikeyphw.xdm.android.scheduler

import android.content.Context
import com.mikeyphw.xdm.android.model.ExternalNetworkTarget
import com.mikeyphw.xdm.android.model.ExternalUrlPolicy
import com.mikeyphw.xdm.android.transfer.DownloadRequest
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

fun interface TransferRequestSecurityGuard {
    suspend fun validate(request: DownloadRequest)

    companion object {
        val AllowAll = TransferRequestSecurityGuard { }
    }
}

class AndroidTransferRequestSecurityGuard(
    @Suppress("UNUSED_PARAMETER") context: Context,
) : TransferRequestSecurityGuard {

    override suspend fun validate(request: DownloadRequest) {
        val uri = runCatching { URI(request.sourceUrl) }.getOrNull()
            ?: throw SecurityException("The transfer URL is malformed")
        val host = uri.host?.takeIf(String::isNotBlank)
            ?: throw SecurityException("The transfer URL has no host")
        val cleartext = uri.scheme.equals("http", true) || uri.scheme.equals("ftp", true)
        if (cleartext && !AndroidNetworkSecurityPolicy.isCleartextTrafficPermitted(host)) {
            throw SecurityException("Cleartext downloads are blocked by XDM network security policy. Use HTTPS.")
        }
        if (cleartext && request.hasSensitiveHeaders() && !request.cleartextCredentialsApproved) {
            throw SecurityException("Cookie or Authorization headers cannot be sent over cleartext transport")
        }

        val literalClassification = ExternalUrlPolicy.classifyNetworkTarget(request.sourceUrl)
        val resolvedPrivate = runCatching { InetAddress.getAllByName(host).any(::isPrivateOrSpecial) }
            .getOrDefault(true)
        if ((literalClassification != ExternalNetworkTarget.Public || resolvedPrivate) && !request.privateNetworkApproved) {
            throw SecurityException("This transfer targets a local, private, link-local, reserved, or unresolved network address and requires explicit approval")
        }
    }

    private fun DownloadRequest.hasSensitiveHeaders(): Boolean = headers.keys.any {
        it.equals("Cookie", true) || it.equals("Authorization", true)
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
}

private object AndroidNetworkSecurityPolicy {
    fun isCleartextTrafficPermitted(host: String): Boolean = try {
        android.security.NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted(host)
    } catch (error: RuntimeException) {
        if (error.isAndroidJvmUnitTestStub()) {
            // Local JVM tests use Android SDK stubs whose platform methods throw
            // "not mocked". Production and instrumented tests still use the real
            // Android NetworkSecurityPolicy implementation.
            true
        } else {
            throw error
        }
    }

    private fun RuntimeException.isAndroidJvmUnitTestStub(): Boolean =
        message.orEmpty().contains("not mocked", ignoreCase = true) &&
            stackTrace.any { it.className == "android.security.NetworkSecurityPolicy" }
}
