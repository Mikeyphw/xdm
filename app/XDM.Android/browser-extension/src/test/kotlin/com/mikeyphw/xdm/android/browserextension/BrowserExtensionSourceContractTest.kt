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
            "generated-config.template.js",
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
    fun `xdm handoff is credential thin`() {
        val handoff = extensionRoot.resolve("handoff.js").readText()
        assertTrue(handoff.contains("//capture?"))
        assertTrue(handoff.contains("params.set(\"v\""))
        assertTrue(handoff.contains("params.set(\"url\""))
        assertFalse(handoff.contains("authorization"))
        assertFalse(handoff.contains("cookie"))
        assertFalse(handoff.contains("extra_headers"))
    }
}
