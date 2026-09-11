package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveLocatorTitleNamingContractTest {
    private val root = androidRoot()

    @Test
    fun liveLocatorHasBrowserStatusRecoveryAndCollapsibleCaptureSurface() {
        val source = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/MediaLocatorActivity.kt").readText()
        val strings = File(root, "app/src/main/res/values/strings.xml").readText()

        assertTrue(source.contains("override fun onReceivedError"))
        assertTrue(source.contains("override fun onReceivedHttpError"))
        assertTrue(source.contains("override fun onReceivedSslError"))
        assertTrue(source.contains("handler.cancel()"))
        assertTrue(source.contains("showMainFrameError("))
        assertTrue(source.contains("updateNavigationState()"))
        assertTrue(source.contains("updatePageSummary()"))
        assertTrue(source.contains("showMediaBottomSheet()"))
        assertTrue(source.contains("setAcceptThirdPartyCookies(webView, true)"))
        assertTrue(source.contains("builtInZoomControls = true"))
        assertTrue(strings.contains("media_locator_retry"))
        assertTrue(strings.contains("media_locator_error_ssl_title"))
        assertTrue(strings.contains("media_locator_media_fab"))
    }

    @Test
    fun locatorAndExtensionArtworkMetadataReachMediaSniffing() {
        val locator = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/MediaLocatorActivity.kt").readText()
        val frameBridge = File(root, "browser-extension/src/main/extension/xdm-firefox/frame-bridge.js").readText()
        val candidateStore = File(root, "browser-extension/src/main/extension/xdm-firefox/candidate-store.js").readText()
        val observer = File(root, "browser-extension/src/main/extension/xdm-firefox/network-observer.js").readText()
        val handoff = File(root, "browser-extension/src/main/extension/xdm-firefox/handoff.js").readText()
        val envelope = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/BrowserCaptureEnvelopeManager.kt").readText()
        val viewModel = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt").readText()

        assertTrue(locator.contains("pageArtwork"))
        assertTrue(locator.contains("durationMs = durationMs"))
        assertTrue(locator.contains("thumbnailUrl = thumbnailUrl"))
        assertTrue(frameBridge.contains("pageArtworkUrl()"))
        assertTrue(frameBridge.contains("durationMs: Math.max(0, Number(candidate.durationMs || 0))"))
        assertTrue(candidateStore.contains("thumbnailUrl: candidate.thumbnailUrl || previous.thumbnailUrl"))
        assertTrue(candidateStore.contains("durationMs: Math.max(0, Number(candidate.durationMs || previous.durationMs || 0))"))
        assertTrue(observer.contains("thumbnailUrl: value.thumbnailUrl || evidence.thumbnailUrl"))
        assertTrue(handoff.contains("thumbnailUrl: safeHttpUrl(candidate.thumbnailUrl || \"\")"))
        assertTrue(handoff.contains("durationMs: Math.max(0, Math.trunc(Number(candidate.durationMs || 0)))"))
        assertTrue(envelope.contains("val durationMs: Long?"))
        assertTrue(envelope.contains("val thumbnailUrl: String?"))
        assertTrue(viewModel.contains("durationMs = candidate.durationMs"))
        assertTrue(viewModel.contains("thumbnailUrl = candidate.thumbnailUrl"))
    }

    @Test
    fun pageTitleIsCanonicalDefaultForCapturedMediaIncludingExtensionImports() {
        val capture = File(root, "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaInboxContract.kt").readText()
        val viewModel = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt").readText()

        assertTrue(capture.contains("fileNameFor(sourceUrl, safeTitle, kind, mimeType, variants, hasExplicitTitle = titleIsPageDerived)"))
        assertTrue(capture.contains("val discriminator = if (hasExplicitTitle) fileNameDiscriminator(kind, variants) else null"))
        assertTrue(capture.contains("preferredMediaExtension(pathName, kind, mimeType)"))
        assertTrue(capture.contains("\"video/mp4\", \"application/mp4\" -> \".mp4\""))
        assertTrue(viewModel.contains("pageTitle = candidate.title ?: decoded.pageTitle"))
        assertTrue(viewModel.contains("source = MediaSniffingSource.BrowserExtension"))
    }

    private fun androidRoot(): File {
        var cursor = File(System.getProperty("user.dir") ?: ".").canonicalFile
        while (true) {
            if (File(cursor, "settings.gradle.kts").isFile && File(cursor, "app").isDirectory) return cursor
            cursor = cursor.parentFile ?: error("Could not locate XDM.Android root from ${System.getProperty("user.dir")}")
        }
    }
}
