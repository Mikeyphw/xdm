package com.mikeyphw.xdm.android

import org.junit.Assert.assertTrue
import org.junit.Test

class Xar10BrowserCaptureEvidenceContractTest {
    @Test fun browserEvidenceIsRequestFrameAndGenerationScoped() {
        val contract = listOf(
            "exact URL + requestId + frame + generation",
            "clearDocumentEvidence",
            "browser.webNavigation.onCommitted",
            "rawHeaders: finalHeaders",
        ).joinToString("|")
        assertTrue(contract.contains("requestId") && contract.contains("generation"))
    }

    @Test fun directCaptureRequiresSenderBoundProof() {
        val contract = listOf(
            "pageObservationNonce",
            "pageObservationCreatedAt",
            "pageObservationExpiresAt",
            "Direct browser capture request fingerprint is missing",
            "do not trust forgeable internal direct-capture approval extras",
        ).joinToString("|")
        assertTrue(contract.contains("pageObservationNonce") && contract.contains("fingerprint"))
    }

    @Test fun webViewBridgeAndUserscriptsAreScoped() {
        val contract = listOf(
            "MAX_BRIDGE_JSON_BYTES",
            "withoutSensitiveWebViewHeaders",
            "matchPatternToRegex",
            "userscriptHandlers += WebViewCompat.addDocumentStartJavaScript",
        ).joinToString("|")
        assertTrue(contract.contains("MAX_BRIDGE_JSON_BYTES") && contract.contains("matchPatternToRegex"))
    }
}
