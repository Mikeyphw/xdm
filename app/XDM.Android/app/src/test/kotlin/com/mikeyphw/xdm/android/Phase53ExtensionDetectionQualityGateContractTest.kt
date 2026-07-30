package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase53ExtensionDetectionQualityGateContractTest {
    private val root = androidRoot()

    @Test
    fun detectorUsesQualityBucketsInsteadOfGenericStreamTrust() {
        val core = source("browser-extension/src/main/extension/xdm-firefox/detector-core.js")
        assertTrue(core.contains("QUALITY_STRONG"))
        assertTrue(core.contains("QUALITY_POSSIBLE"))
        assertTrue(core.contains("possible-octet"))
        assertTrue(core.contains("media-disposition"))
        assertTrue(core.contains("candidateSignal"))
        assertFalse("Generic JSON src/url keys must not become direct candidates", core.contains("posterUrl"))
    }

    @Test
    fun possibleMediaIsBehindAnAdvancedToggle() {
        val observer = source("browser-extension/src/main/extension/xdm-firefox/network-observer.js")
        val frame = source("browser-extension/src/main/extension/xdm-firefox/frame-bridge.js")
        val popup = source("browser-extension/src/main/extension/xdm-firefox/popup.html")
        val popupJs = source("browser-extension/src/main/extension/xdm-firefox/popup.js")
        assertTrue(observer.contains("showPossibleMediaCandidates: false"))
        assertTrue(observer.contains("function allowsQuality"))
        assertTrue(observer.contains("quality !== \"possible\" || settings.showPossibleMediaCandidates === true"))
        assertTrue(frame.contains("showPossibleMediaCandidates: false"))
        assertTrue(frame.contains("Possible media found"))
        assertTrue(popup.contains("Show possible media after rescan"))
        assertTrue(popup.contains("fake video offers"))
        assertTrue(popupJs.contains("showPossibleMediaCandidates: document.getElementById(\"showPossible\").checked"))
        assertTrue(popupJs.contains("Possible media"))
        assertTrue(popupJs.contains("High confidence"))
    }

    @Test
    fun phase53KeepsPrivacyAndDebugWorkbenchBoundary() {
        val manifest = source("PROJECT_MANIFEST.json")
        val doc = source("docs/architecture/PHASE-53-EXTENSION-DETECTION-QUALITY-GATE.md")
        val phase52 = source("app/src/test/kotlin/com/mikeyphw/xdm/android/Phase52BrowserSessionHealthContractTest.kt")
        assertTrue(manifest.contains("\"field_bugfix_phase_53\""))
        assertTrue(manifest.contains("\"room_schema_unchanged\": 14"))
        assertTrue(manifest.contains("\"top_level_route_added\": false"))
        assertTrue(manifest.contains("\"debug_workbench_reopened\": false"))
        assertTrue(doc.contains("Strong media is offered by default"))
        assertTrue(doc.contains("Possible media stays behind an explicit advanced toggle"))
        assertTrue(doc.contains("No cookies, Authorization values, bearer tokens, or full URLs are added to normal UI"))
        assertTrue("Phase52 warning fix should use a non-null repo root helper", phase52.contains("private fun repoRoot(): File"))
        assertFalse("Phase52 warning fix must not use nullable parentFile chaining at the read site", phase52.contains("root.parentFile.parentFile"))
    }

    private fun source(relative: String): String = File(root, relative).readText()

    private fun androidRoot(): File {
        var cursor = File(System.getProperty("user.dir") ?: ".").canonicalFile
        repeat(8) {
            if (File(cursor, "settings.gradle.kts").isFile && File(cursor, "app/src/main").isDirectory) return cursor
            cursor = cursor.parentFile ?: cursor
        }
        error("Android root not found")
    }
}
