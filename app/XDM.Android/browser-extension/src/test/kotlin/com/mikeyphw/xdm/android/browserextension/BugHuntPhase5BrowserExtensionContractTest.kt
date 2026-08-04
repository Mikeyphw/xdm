package com.mikeyphw.xdm.android.browserextension

import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BugHuntPhase5BrowserExtensionContractTest {
    private val root = Path.of("src/main/extension/xdm-firefox")

    @Test fun observesProposedAndFinalHeadersWithoutLeakingThemIntoSchemeUrl() {
        val observer = root.resolve("network-observer.js").readText()
        val handoff = root.resolve("handoff.js").readText()
        assertTrue(observer.contains("ProposedBeforeSend"))
        assertTrue(observer.contains("FinalSent"))
        assertTrue(observer.contains("onSendHeaders.addListener"))
        assertTrue(observer.contains("CORE.stableMediaIdentity"))
        assertTrue(handoff.contains("stableMediaId"))
        assertTrue(handoff.contains("sessionRevision"))
        assertFalse(handoff.contains("params.set(\"headers\""))
        assertFalse(handoff.contains("extra_headers"))
    }

    @Test fun tabRemovalDoesNotDeleteRequestIdScopedHeaders() {
        val observer = root.resolve("network-observer.js").readText()
        assertTrue(observer.contains("Request headers are keyed by requestId"))
        assertFalse(observer.contains("capturedHeaders.delete(tabId)"))
    }
}
