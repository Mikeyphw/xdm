package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserCapturePhases59_61ContractTest {
    private val root = androidRoot()

    @Test
    fun captureSessionsRemainFirstClassAndNonSecretInMediaInbox() {
        val models = source("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/BrowserCaptureSessionModels.kt")
        val registry = source("media/src/main/kotlin/com/mikeyphw/xdm/android/media/BrowserCaptureSessionRegistry.kt")
        val viewModel = source("app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt")
        val screen = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/media/MediaInboxScreen.kt")

        assertTrue(models.contains("data class BrowserCaptureSessionSummary"))
        assertTrue(models.contains("data class BrowserCaptureCandidateSummary"))
        assertTrue(registry.contains("no URLs or request headers"))
        assertFalse(registry.contains("sourceUrl"))
        assertFalse(registry.contains("authorization", ignoreCase = true))
        assertTrue(viewModel.contains("browserCaptureSessionRegistry.record"))
        assertTrue(screen.contains("Firefox capture sessions"))
        assertTrue(screen.contains("BrowserCaptureSessionHeader"))
    }

    @Test
    fun currentFirefoxHandoffIsDirectV3WhileEncryptedV2RemainsReaderOnlyMigrationSupport() {
        val contract = source("browser-integration/src/main/kotlin/com/mikeyphw/xdm/android/browser/XdmBrowserDeepLinkContract.kt")
        val parser = source("browser-integration/src/main/kotlin/com/mikeyphw/xdm/android/browser/XdmBrowserDeepLinkParser.kt")
        val handoff = source("browser-extension/src/main/extension/xdm-firefox/handoff.js")
        val config = source("browser-extension/src/main/extension/xdm-firefox/generated-config.template.js")
        val network = source("browser-extension/src/main/extension/xdm-firefox/network-observer.js")

        assertTrue(contract.contains("EncryptedCaptureVersion = 2"))
        assertTrue(contract.contains("CurrentVersion = 3"))
        assertTrue(parser.contains("parseEncryptedCaptureEnvelope"))
        assertTrue(parser.contains("parseDirectPayload"))
        assertTrue(handoff.contains("function buildXdmCapture"))
        assertTrue(handoff.contains("params.set(\"url\""))
        assertTrue(handoff.contains("params.set(\"headers\""))
        assertTrue(handoff.contains("sanitizeHeaderBag"))
        assertTrue(network.contains("buildCaptureSession"))
        assertTrue(network.contains("prebuiltXdmLink"))
        assertFalse(config.contains("captureKeyId"))
        assertFalse(config.contains("capturePublicKeySpki"))
        assertFalse(config.contains("captureOaepHash"))
        assertFalse(handoff.contains("crypto.subtle"))
    }

    @Test
    fun firefoxTransfersOneBestEvidenceBackedCandidatePerReviewHandoff() {
        val store = source("browser-extension/src/main/extension/xdm-firefox/candidate-store.js")
        val network = source("browser-extension/src/main/extension/xdm-firefox/network-observer.js")
        val handoff = source("browser-extension/src/main/extension/xdm-firefox/handoff.js")
        val detector = source("browser-extension/src/main/extension/xdm-firefox/detector-core.js")
        val pageSniffer = source("browser-extension/src/main/extension/xdm-firefox/page-sniffer.js")
        val screen = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/media/MediaInboxScreen.kt")

        assertTrue(store.contains("snapshot(tabId"))
        assertTrue(network.contains("visibleCandidateSnapshot(tabId, MAX_CANDIDATES_PER_TAB)"))
        assertTrue(network.contains("visibleCandidates.slice(0, MAX_HANDOFF_CANDIDATES)"))
        assertTrue(network.contains("candidateStore.snapshot(tabId, MAX_CANDIDATES_PER_TAB)"))
        assertTrue(network.contains("capturedCandidateCount: Math.min(1, sessionCandidates.length)"))
        assertTrue(handoff.contains("candidates.find(item => item && item.url)"))
        assertTrue(detector.contains("HARD_NON_MEDIA_MIME_RE"))
        assertTrue(pageSniffer.contains("HARD_NON_MEDIA_MIME_RE"))
        assertTrue(screen.contains("bounded browser handoff"))
    }

    @Test
    fun exportedXpiIsKeylessAndStalesOnlyForPackageAffectingChanges() {
        val viewModel = source("app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt")
        val generator = source("browser-extension/src/main/kotlin/com/mikeyphw/xdm/android/browserextension/BrowserExtensionPackageGenerator.kt")
        val config = source("browser-extension/src/main/extension/xdm-firefox/generated-config.template.js")
        val exportModels = source("app/src/main/kotlin/com/mikeyphw/xdm/android/BrowserExtensionExportModels.kt")
        val lifecycleTest = source("browser-extension/tests/test_secure_handoff.js")

        assertFalse(viewModel.contains("captureKeyId = browserCaptureEnvelopeManager.keyId"))
        assertFalse(generator.contains("@@CAPTURE_KEY_ID@@"))
        assertFalse(generator.contains("@@CAPTURE_PUBLIC_KEY_SPKI@@"))
        assertFalse(config.contains("captureKeyId"))
        assertFalse(config.contains("capturePublicKeySpki"))
        assertFalse(exportModels.contains("appVersion != metadata.appVersion"))
        assertTrue(lifecycleTest.contains("capture link must not depend on Android app version/key lifecycle"))
        assertTrue(lifecycleTest.contains("keyless v3 lifecycle test passed"))
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
