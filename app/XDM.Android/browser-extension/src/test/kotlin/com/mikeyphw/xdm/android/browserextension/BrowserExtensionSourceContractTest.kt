package com.mikeyphw.xdm.android.browserextension

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserExtensionSourceContractTest {
    private val extensionRoot: Path = Path.of("src/main/extension/xdm-firefox")

    @Test
    fun `source inventory is complete and contains no generated xpi`() {
        val required = setOf(
            "manifest.template.json",
            "bridge-selftest.js",
            "generated-config.template.js",
            "generated-theme.template.css",
            "detector-core.js",
            "candidate-store.js",
            "network-observer.js",
            "page-sniffer.js",
            "frame-bridge.js",
            "handoff.js",
            "fab.js",
            "popup.html",
            "popup.js",
            "extension.css",
        )
        assertTrue(required.all { Files.isRegularFile(extensionRoot.resolve(it)) })
        Files.walk(extensionRoot).use { files ->
            assertFalse(files.anyMatch { it.fileName.toString().endsWith(".xpi") })
        }
    }

    @Test
    fun `manifest owns stable xdm identity and layered detector`() {
        val manifest = extensionRoot.resolve("manifest.template.json").readText()
        assertTrue(manifest.contains(BrowserExtensionSourceContract.ExtensionId))
        assertTrue(manifest.contains("bridge-selftest.js"))
        assertTrue(manifest.contains("candidate-store.js"))
        assertTrue(manifest.contains("network-observer.js"))
        assertTrue(manifest.contains("all_frames"))
        assertFalse(manifest.contains("1dm-ironfox-media-bridge"))
    }

    @Test
    fun `popup contains no custom protocol anchors`() {
        val popup = extensionRoot.resolve("popup.html").readText()
        assertFalse(popup.contains("idmdownload:"))
        assertFalse(popup.contains("xdmdownload:"))
        assertFalse(popup.contains("intent:"))
        assertFalse(popup.contains("href=\"idmdownload"))
    }

    @Test
    fun `xdm handoff keeps credentials out of plaintext URI`() {
        val handoff = extensionRoot.resolve("handoff.js").readText()
        assertTrue(handoff.contains("//capture?"))
        assertTrue(handoff.contains("buildEncryptedCaptureSession"))
        assertTrue(handoff.contains("params.set(\"ct\""))
        assertTrue(handoff.contains("params.set(\"ek\""))
        assertTrue(handoff.contains("params.set(\"iv\""))
        assertFalse(handoff.contains("params.set(\"authorization\""))
        assertFalse(handoff.contains("params.set(\"cookie\""))
        assertFalse(handoff.contains("params.set(\"headers\""))
        assertFalse(handoff.contains("extra_headers"))
    }
    @Test
    fun `page hints require privileged request evidence and xdm plaintext fallback is disabled`() {
        val handoff = extensionRoot.resolve("handoff.js").readText()
        val observer = extensionRoot.resolve("network-observer.js").readText()
        val bridge = extensionRoot.resolve("frame-bridge.js").readText()
        val detector = extensionRoot.resolve("detector-core.js").readText()
        val store = extensionRoot.resolve("candidate-store.js").readText()

        assertTrue(handoff.contains("function buildXdmCapture"))
        assertTrue(handoff.contains("return "";"))
        assertTrue(handoff.contains("requestFingerprint"))
        assertTrue(observer.contains("findPrivilegedEvidence"))
        assertTrue(observer.contains("candidate.source !== "webRequest""))
        assertTrue(observer.contains("requestHeaders: {}") || bridge.contains("requestHeaders: {}"))
        assertTrue(observer.contains("plaintext fallback is disabled") || bridge.contains("plaintext fallback is disabled"))
        assertTrue(detector.contains("requestFingerprint"))
        assertTrue(store.contains("requestFingerprint"))
    }

    @Test
    fun `background loads encrypted handoff before network observer`() {
        val manifest = extensionRoot.resolve("manifest.template.json").readText()
        val handoffIndex = manifest.indexOf("handoff.js")
        val observerIndex = manifest.indexOf("network-observer.js")
        assertTrue(handoffIndex >= 0 && observerIndex > handoffIndex)
    }
}
