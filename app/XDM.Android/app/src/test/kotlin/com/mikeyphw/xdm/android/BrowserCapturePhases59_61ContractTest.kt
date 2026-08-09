package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserCapturePhases59_61ContractTest {
    private val root = androidRoot()

    @Test
    fun captureSessionsAreFirstClassAndNonSecretInMediaInbox() {
        val models = source("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/BrowserCaptureSessionModels.kt")
        val registry = source("media/src/main/kotlin/com/mikeyphw/xdm/android/media/BrowserCaptureSessionRegistry.kt")
        val viewModel = source("app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt")
        val screen = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/media/MediaInboxScreen.kt")

        assertTrue(models.contains("data class BrowserCaptureSessionSummary"))
        assertTrue(models.contains("data class BrowserCaptureCandidateSummary"))
        assertTrue(registry.contains("no URLs or request headers"))
        assertTrue(registry.contains("candidate.") && registry.contains("captureId"))
        assertFalse("Registry must not persist raw URLs", registry.contains("sourceUrl"))
        assertFalse("Registry must not persist secret headers", registry.contains("authorization", ignoreCase = true))
        assertTrue(viewModel.contains("browserCaptureSessionRegistry.record"))
        assertTrue(viewModel.contains("browserCaptureSessionRegistry.sessions"))
        assertTrue(viewModel.contains("browserCaptureSessions = review.browserCaptureSessions"))
        assertTrue(screen.contains("Firefox capture sessions"))
        assertTrue(screen.contains("BrowserCaptureSessionHeader"))
        assertTrue(screen.contains("grouped in the Media inbox") || viewModel.contains("grouped in the Media inbox"))
    }

    @Test
    fun secureFirefoxHandoffCarriesEncryptedEnvelopeInsteadOfPlaintextSecrets() {
        val contract = source("browser-integration/src/main/kotlin/com/mikeyphw/xdm/android/browser/XdmBrowserDeepLinkContract.kt")
        val parser = source("browser-integration/src/main/kotlin/com/mikeyphw/xdm/android/browser/XdmBrowserDeepLinkParser.kt")
        val payload = source("browser-integration/src/main/kotlin/com/mikeyphw/xdm/android/browser/XdmBrowserDeepLinkPayload.kt")
        val manager = source("app/src/main/kotlin/com/mikeyphw/xdm/android/BrowserCaptureEnvelopeManager.kt")
        val handoff = source("browser-extension/src/main/extension/xdm-firefox/handoff.js")
        val network = source("browser-extension/src/main/extension/xdm-firefox/network-observer.js")

        assertTrue(contract.contains("CurrentVersion = 2"))
        assertTrue(contract.contains("WrappedKeyParameter"))
        assertTrue(contract.contains("EnvelopeCiphertextParameter"))
        assertTrue(parser.contains("parseEncryptedCaptureEnvelope"))
        assertTrue(parser.contains("UnsupportedContract"))
        assertTrue(payload.contains("hasEncryptedCaptureEnvelope"))
        assertTrue(manager.contains("AndroidKeyStore"))
        assertTrue(manager.contains("RSA/ECB/OAEPWithSHA-256AndMGF1Padding"))
        assertTrue(manager.contains("AES/GCM/NoPadding"))
        assertTrue(manager.contains("HEADER_ALLOWLIST"))
        assertTrue(handoff.contains("buildEncryptedCaptureSession"))
        assertTrue(handoff.contains("params.set(\"ct\""))
        assertTrue(handoff.contains("params.set(\"ek\""))
        assertFalse("No raw Cookie query parameter", handoff.contains("params.set(\"cookie\""))
        assertFalse("No raw Authorization query parameter", handoff.contains("params.set(\"authorization\""))
        assertTrue(network.contains("prebuiltXdmLink"))
        val frameBridge = source("browser-extension/src/main/extension/xdm-firefox/frame-bridge.js")
        assertTrue(frameBridge.contains("prebuiltXdmLink: input.prebuiltXdmLink"))
    }

    @Test
    fun firefoxTransfersTheBoundedCandidateSetInsteadOfOnlyBestCandidate() {
        val store = source("browser-extension/src/main/extension/xdm-firefox/candidate-store.js")
        val network = source("browser-extension/src/main/extension/xdm-firefox/network-observer.js")
        val screen = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/media/MediaInboxScreen.kt")
        val viewModel = source("app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt")

        assertTrue(store.contains("snapshot(tabId"))
        assertTrue(store.contains("this.maxPerTab"))
        assertTrue(network.contains("MAX_HANDOFF_CANDIDATES = 24"))
        assertTrue(network.contains("candidateStore.snapshot"))
        assertTrue(network.contains("totalCandidateCount"))
        assertTrue(network.contains("encryptedCandidateCount"))
        assertTrue(viewModel.contains("decoded.candidates.forEach"))
        assertTrue(viewModel.contains("summaries.distinctBy"))
        assertTrue("encrypted capture variants must preserve exact URLs only in the secure handoff store", viewModel.contains("MediaRequestHandoffStore.rememberVariant"))
        assertTrue("encrypted capture rows must persist redacted URLs", viewModel.contains("persistableBrowserCaptureUrl(rawRecord.sourceUrl)"))
        assertTrue("encrypted capture variant rows must persist redacted URLs", viewModel.contains("variant.copy(url = persistedVariantUrl)"))
        assertTrue(screen.contains("reviewable candidate(s) from"))
        assertTrue(screen.contains("bounded encrypted Android handoff"))
    }

    @Test
    fun exportedXpiReceivesTheAppPublicKeyAndKeepsCredentialRedactionBoundary() {
        val viewModel = source("app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt")
        val generator = source("browser-extension/src/main/kotlin/com/mikeyphw/xdm/android/browserextension/BrowserExtensionPackageGenerator.kt")
        val config = source("browser-extension/src/main/extension/xdm-firefox/generated-config.template.js")
        val diagnostics = source("app/src/main/kotlin/com/mikeyphw/xdm/android/BrowserBridgeIntegrationModels.kt")
        val secureTest = source("browser-extension/tests/test_secure_handoff.js")

        assertTrue(viewModel.contains("captureKeyId = browserCaptureEnvelopeManager.keyId"))
        assertTrue(viewModel.contains("capturePublicKeySpki = browserCaptureEnvelopeManager.publicKeySpkiBase64Url"))
        assertTrue(generator.contains("@@CAPTURE_KEY_ID@@"))
        assertTrue(generator.contains("@@CAPTURE_PUBLIC_KEY_SPKI@@"))
        assertTrue(config.contains("captureKeyId"))
        assertTrue(config.contains("capturePublicKeySpki"))
        assertTrue(diagnostics.contains("<encrypted-capture>"))
        assertTrue(secureTest.contains("assert(!link.includes(\"secret-cookie\"))"))
        assertTrue(secureTest.contains("assert(!link.includes(\"top-secret\"))"))
        assertTrue(secureTest.contains("full bounded candidate set survives encrypted handoff"))
    }

    private fun source(relative: String): String = File(root, relative).readText()

    private fun androidRoot(): File {
        var cursor = File(System.getProperty("user.dir") ?: ".").canonicalFile
        repeat(8) {
            if (File(cursor, "settings.gradle.kts").isFile && File(cursor, "app/src/main").isDirectory) return cursor
            cursor = cursor.parentFile ?: return@repeat
        }
        error("Unable to locate XDM Android root")
    }
}
