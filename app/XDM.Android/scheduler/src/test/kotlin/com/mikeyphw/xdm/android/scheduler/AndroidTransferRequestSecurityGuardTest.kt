package com.mikeyphw.xdm.android.scheduler

import com.mikeyphw.xdm.android.model.BackendType
import com.mikeyphw.xdm.android.transfer.DownloadRequest
import com.mikeyphw.xdm.android.transfer.DownloadRequestApprovalScope
import java.net.InetAddress
import java.net.UnknownHostException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AndroidTransferRequestSecurityGuardTest {

    @Test
    fun transientDnsFailureRetriesAndReturnsOnlyValidatedAddresses() = runTest {
        var calls = 0
        val resolver = TransferHostnameResolver {
            calls += 1
            if (calls == 1) throw UnknownHostException("transient")
            listOf(InetAddress.getByName("8.8.8.8"))
        }
        val guard = AndroidTransferRequestSecurityGuard(
            context = null,
            hostnameResolver = resolver,
            dnsAttempts = 3,
            dnsRetryDelayMillis = 0,
        )

        val target = guard.validateAndResolveTarget(request("https://origin.example/video/part.ts"))

        assertEquals(2, calls)
        assertEquals("origin.example", target.host)
        assertEquals(listOf("8.8.8.8"), target.addresses.map { it.hostAddress })
    }

    @Test
    fun legitimatePublicCdnTransitionIsValidatedAsANewExactTarget() = runTest {
        val seen = mutableListOf<String>()
        val resolver = TransferHostnameResolver { host ->
            seen += host
            when (host) {
                "origin.example" -> listOf(InetAddress.getByName("8.8.8.8"))
                "cdn.example" -> listOf(InetAddress.getByName("1.1.1.1"))
                else -> throw UnknownHostException(host)
            }
        }
        val guard = AndroidTransferRequestSecurityGuard(null, resolver, dnsAttempts = 1, dnsRetryDelayMillis = 0)
        val request = request("https://origin.example/master.m3u8")

        val origin = guard.validateAndResolveTarget(request, request.sourceUrl)
        val cdn = guard.validateAndResolveTarget(request.copy(sourceUrl = "https://cdn.example/seg-12.ts"), "https://cdn.example/seg-12.ts")

        assertEquals("origin.example", origin.host)
        assertEquals("cdn.example", cdn.host)
        assertEquals(listOf("origin.example", "cdn.example"), seen)
    }

    @Test
    fun privateResolvedRouteIsRejectedWithoutExactApproval() = runTest {
        val guard = AndroidTransferRequestSecurityGuard(
            null,
            TransferHostnameResolver { listOf(InetAddress.getByName("10.10.0.12")) },
            dnsAttempts = 1,
            dnsRetryDelayMillis = 0,
        )

        val failure = captureSecurityFailure {
            guard.validateAndResolveTarget(request("https://private.example/segment.ts"))
        }

        assertEquals(TransferSecurityFailureKind.UnsafeNetworkTarget, failure.kind)
    }

    @Test
    fun privateResolvedRouteRequiresApprovalForTheExactUrl() = runTest {
        val url = "https://private.example/segment.ts?token=redacted"
        val scope = requireNotNull(DownloadRequestApprovalScope.forUrl(url))
        val guard = AndroidTransferRequestSecurityGuard(
            null,
            TransferHostnameResolver { listOf(InetAddress.getByName("10.10.0.12")) },
            dnsAttempts = 1,
            dnsRetryDelayMillis = 0,
        )
        val approved = request(url).copy(
            privateNetworkApproved = true,
            privateNetworkApprovalScopes = setOf(scope),
        )

        val target = guard.validateAndResolveTarget(approved)

        assertEquals("private.example", target.host)
        assertEquals(scope, target.approvalScope)
        assertTrue(target.addresses.single().isSiteLocalAddress)
    }

    @Test
    fun dnsFailureIsClassifiedWithoutWeakeningNetworkPolicy() = runTest {
        val guard = AndroidTransferRequestSecurityGuard(
            null,
            TransferHostnameResolver { throw UnknownHostException("offline") },
            dnsAttempts = 2,
            dnsRetryDelayMillis = 0,
        )

        val failure = captureSecurityFailure {
            guard.validateAndResolveTarget(request("https://cdn.example/segment.ts"))
        }

        assertEquals(TransferSecurityFailureKind.DnsResolutionFailed, failure.kind)
    }

    @Test
    fun validatedCdnTargetRetainsAllPublicAddressesForPinnedTransport() = runTest {
        val guard = AndroidTransferRequestSecurityGuard(
            null,
            TransferHostnameResolver { listOf(InetAddress.getByName("8.8.8.8"), InetAddress.getByName("1.1.1.1")) },
            dnsAttempts = 1,
            dnsRetryDelayMillis = 0,
        )
        val target = guard.validateAndResolveTarget(request("https://cdn.example/segment.ts"))
        assertEquals(listOf("8.8.8.8", "1.1.1.1"), target.addresses.map { it.hostAddress })
    }

    private fun request(url: String) = DownloadRequest(
        id = "security-test",
        sourceUrl = url,
        destinationUri = "file:///tmp/output.bin",
        fileName = "output.bin",
        preferredBackend = BackendType.Native,
    )

    private suspend fun captureSecurityFailure(block: suspend () -> Unit): TransferRequestSecurityException {
        try {
            block()
            fail("Expected TransferRequestSecurityException")
        } catch (failure: TransferRequestSecurityException) {
            return failure
        }
        error("unreachable")
    }
}
