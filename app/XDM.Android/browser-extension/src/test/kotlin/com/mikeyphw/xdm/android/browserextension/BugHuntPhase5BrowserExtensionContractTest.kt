package com.mikeyphw.xdm.android.browserextension

import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BugHuntPhase5BrowserExtensionContractTest {
    private val root = Path.of("src/main/extension/xdm-firefox")

    @Test fun observesProposedAndFinalHeadersAndSerializesOnlySanitizedAllowlistedReplayContext() {
        val observer = root.resolve("network-observer.js").readText()
        val handoff = root.resolve("handoff.js").readText()
        assertTrue(observer.contains("ProposedBeforeSend"))
        assertTrue(observer.contains("FinalSent"))
        assertTrue(observer.contains("onSendHeaders.addListener"))
        assertTrue(observer.contains("CORE.stableMediaIdentity"))
        assertTrue(handoff.contains("stableMediaId"))
        assertTrue(handoff.contains("sessionRevision"))
        assertTrue(handoff.contains("HEADER_ALLOWLIST"))
        assertTrue(handoff.contains("sanitizeHeaderBag"))
        assertTrue(handoff.contains("MAX_HEADER_BLOCK"))
        assertTrue(handoff.contains("params.set(\"headers\""))
        assertTrue(handoff.contains("params.set(\"proposedHeaders\""))
        assertTrue(handoff.contains("params.set(\"finalHeaders\""))
        assertTrue(handoff.contains("\"accept-language\""))
        val addBuilder = handoff.substringAfter("function buildXdmAdd").substringBefore("function buildXdmCapture")
        assertFalse(addBuilder.contains("params.set(\"headers\""))
        assertFalse(handoff.contains("extra_headers"))
    }

    @Test fun tabRemovalDoesNotDeleteRequestIdScopedHeaders() {
        val observer = root.resolve("network-observer.js").readText()
        assertTrue(observer.contains("Request headers are keyed by requestId"))
        assertFalse(observer.contains("capturedHeaders.delete(tabId)"))
    }
}
