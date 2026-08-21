package com.mikeyphw.xdm.android.browserextension

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserExtensionSourceContractTest {
    private val extensionRoot:Path=Path.of("src/main/extension/xdm-firefox")
    @Test fun `source inventory is complete and contains no generated xpi`() {
        val required=setOf("manifest.template.json","bridge-selftest.js","generated-config.template.js","generated-theme.template.css","detector-core.js","candidate-store.js","network-observer.js","page-sniffer.js","frame-bridge.js","handoff.js","fab.js","popup.html","popup.js","extension.css")
        assertTrue(required.all{Files.isRegularFile(extensionRoot.resolve(it))}); Files.walk(extensionRoot).use { files -> assertFalse(files.anyMatch{it.fileName.toString().endsWith(".xpi")}) }
    }
    @Test fun `manifest owns stable xdm identity and layered detector`() {
        val manifest=extensionRoot.resolve("manifest.template.json").readText(); assertTrue(manifest.contains(BrowserExtensionSourceContract.ExtensionId)); assertTrue(manifest.contains("bridge-selftest.js")); assertTrue(manifest.contains("candidate-store.js")); assertTrue(manifest.contains("network-observer.js")); assertTrue(manifest.contains("all_frames"))
    }
    @Test fun `popup contains no custom protocol anchors`() {
        val popup=extensionRoot.resolve("popup.html").readText(); assertFalse(popup.contains("idmdownload:")); assertFalse(popup.contains("xdmdownload:")); assertFalse(popup.contains("intent:"))
    }
    @Test fun `xdm handoff is direct v3 and independent from android install keys`() {
        val handoff=extensionRoot.resolve("handoff.js").readText(); val config=extensionRoot.resolve("generated-config.template.js").readText()
        assertTrue(handoff.contains("function buildXdmCapture")); assertTrue(handoff.contains("params.set(\"url\", url)")); assertTrue(handoff.contains("proposedHeaders")); assertTrue(handoff.contains("finalHeaders"))
        assertFalse(config.contains("captureKeyId")); assertFalse(config.contains("capturePublicKeySpki")); assertFalse(config.contains("captureOaepHash"))
    }
    @Test fun `page hints require evidence and misleading non-media mime is rejected`() {
        val observer=extensionRoot.resolve("network-observer.js").readText(); val detector=extensionRoot.resolve("detector-core.js").readText(); val sniffer=extensionRoot.resolve("page-sniffer.js").readText()
        assertTrue(observer.contains("findPrivilegedEvidence")); assertTrue(observer.contains("candidate.source !== \"webRequest\"")); assertTrue(detector.contains("HARD_NON_MEDIA_MIME_RE")); assertTrue(detector.contains("possible-media-extension")); assertTrue(sniffer.contains("HARD_NON_MEDIA_MIME_RE")); assertTrue(detector.contains("requestFingerprint"))
    }
}
