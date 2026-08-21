package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaLocatorParityContractTest {
    private val root = androidRoot()

    @Test
    fun liveLocatorUsesRuntimeDomFetchXhrEvidenceAndSharedAppClassifier() {
        val locator = source("app/src/main/kotlin/com/mikeyphw/xdm/android/MediaLocatorActivity.kt")
        val manifest = source("app/src/main/AndroidManifest.xml")
        val screen = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/media/MediaInboxScreen.kt")

        assertTrue(locator.contains("document.querySelectorAll('video,audio,source')"))
        assertTrue(locator.contains("window.fetch = async function"))
        assertTrue(locator.contains("XMLHttpRequest"))
        assertTrue(locator.contains("performance.getEntriesByType('resource')"))
        assertTrue(locator.contains("initiator !== 'video' && initiator !== 'audio'"))
        assertFalse(locator.contains("MEDIA_EXT.test(entry.name)"))
        assertTrue(locator.contains("MediaSniffingEngine()"))
        assertTrue(locator.contains("MediaSniffingSource.NetworkObservation"))
        assertTrue(locator.contains("CookieManager.getInstance().getCookie"))
        assertTrue(locator.contains("XdmBrowserDeepLinkContract.CurrentVersion.toString()"))
        assertTrue(locator.contains("ExternalHandoffReviewActivity::class.java"))
        assertTrue(manifest.contains("android:name=\".MediaLocatorActivity\""))
        assertTrue(manifest.substringAfter("android:name=\".MediaLocatorActivity\"").substringBefore("/>").contains("android:exported=\"false\""))
        assertTrue(screen.contains("Live media locator"))
        assertTrue(screen.contains("Static sniff"))
    }

    @Test
    fun extensionAndAppRejectHardNonMediaEvenWhenUrlLooksLikeMedia() {
        val detector = source("browser-extension/src/main/extension/xdm-firefox/detector-core.js")
        val sniffer = source("browser-extension/src/main/extension/xdm-firefox/page-sniffer.js")
        val classifier = source("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaInboxContract.kt")
        val engine = source("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaSniffingEngine.kt")

        assertTrue(detector.contains("HARD_NON_MEDIA_MIME_RE"))
        assertTrue(sniffer.contains("HARD_NON_MEDIA_MIME_RE"))
        assertTrue(classifier.contains("hardNonMediaMime -> MediaSourceKind.Unknown"))
        assertTrue(classifier.contains("application/json"))
        assertTrue(classifier.contains("text/html"))
        assertTrue(classifier.contains("image/"))
        assertTrue(engine.contains("structuredMediaValuePattern"))
        assertTrue(engine.contains("mediaTagPattern"))
        assertFalse(engine.contains("cssUrlPattern.findAll"))
        assertFalse(engine.contains("htmlAttributePattern.findAll"))
    }

    @Test
    fun browserAndAppKeepWeakEvidenceInternalUntilCorroboratedAndNeverFallbackToGenericDownload() {
        val detector = source("browser-extension/src/main/extension/xdm-firefox/detector-core.js")
        val observer = source("browser-extension/src/main/extension/xdm-firefox/network-observer.js")
        val pageSniffer = source("browser-extension/src/main/extension/xdm-firefox/page-sniffer.js")
        val store = source("browser-extension/src/main/extension/xdm-firefox/candidate-store.js")
        val viewModel = source("app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt")
        val capturePath = viewModel.substringAfter("private suspend fun executeCaptureMediaCommand")
            .substringBefore("private suspend fun openExternalAddDraft")

        assertTrue(detector.contains("possible-manifest-extension"))
        assertTrue(observer.contains("visibleCandidateSnapshot"))
        assertTrue(observer.contains("analysis.manifestBody"))
        assertTrue(observer.contains("reason: analysis.hlsBody ? \"hls-body\" : \"dash-body\""))
        assertTrue(store.contains("mergedQuality"))
        assertTrue(observer.contains("accept-language"))
        assertTrue(pageSniffer.contains("accept-language"))
        assertTrue(capturePath.contains("AutomationRejectionReason.NoMediaDetected"))
        assertTrue(capturePath.contains("Non-media capture ignored"))
        assertFalse(capturePath.contains("openExternalAddDraft"))
    }

    @Test
    fun xpiV3IsNotBoundToPerInstallAndroidKeyMaterial() {
        val contract = source("browser-integration/src/main/kotlin/com/mikeyphw/xdm/android/browser/XdmBrowserDeepLinkContract.kt")
        val handoff = source("browser-extension/src/main/extension/xdm-firefox/handoff.js")
        val config = source("browser-extension/src/main/extension/xdm-firefox/generated-config.template.js")
        val exportModels = source("app/src/main/kotlin/com/mikeyphw/xdm/android/BrowserExtensionExportModels.kt")

        assertTrue(contract.contains("CurrentVersion = 3"))
        assertTrue(contract.contains("EncryptedCaptureVersion = 2"))
        assertTrue(handoff.contains("function buildXdmCapture"))
        assertTrue(handoff.contains("params.set(\"url\""))
        assertFalse(config.contains("captureKeyId"))
        assertFalse(config.contains("capturePublicKeySpki"))
        assertFalse(config.contains("captureOaepHash"))
        assertFalse(exportModels.contains("appVersion != metadata.appVersion"))
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
