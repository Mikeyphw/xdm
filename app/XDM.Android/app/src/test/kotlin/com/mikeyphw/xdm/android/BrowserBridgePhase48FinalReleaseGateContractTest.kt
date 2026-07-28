package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserBridgePhase48FinalReleaseGateContractTest {
    private val root = androidRoot()

    @Test
    fun manifestRecordsFinalReleaseGateAndGreenBaseline() {
        val manifest = File(root, "PROJECT_MANIFEST.json").readText()
        assertTrue(manifest.contains("browser_bridge_phase48_final_ux_release_gate"))
        val phase48StillComplete = manifest.contains("\"next_phase\": \"complete\"")
        val debugRoadmapContinuesAfterReleaseGate = manifest.contains("\"next_phase\": \"debug_workbench_phase_d2_shell\"")
        assertTrue("Phase 48 must remain complete or explicitly hand off to the Debug Workbench roadmap", phase48StillComplete || debugRoadmapContinuesAfterReleaseGate)
        assertTrue(manifest.contains("\"final_phase_complete\": true"))
        assertTrue(manifest.contains("\"baseline_commit\": \"6e3ad8d\""))
        assertTrue(manifest.contains("\"tests_passed\": 358"))
        assertTrue(manifest.contains("\"diagnostic_warnings\": 0"))
        assertTrue(manifest.contains("\"diagnostic_errors\": 0"))
        assertTrue(manifest.contains("\"browser_runtime_added\": false"))
        assertTrue(manifest.contains("\"top_level_route_added\": false"))
        assertTrue(manifest.contains("\"room_schema_unchanged\": 14"))
    }

    @Test
    fun releaseGateScriptCarriesFullValidationMatrix() {
        val script = File(root, "tools/run-phase-48-final-release-gate.sh").readText()
        listOf(
            "assembleDebug",
            ":app:assembleDebugAndroidTest",
            ":browser-extension:packageFirefoxExtensionDark",
            ":browser-extension:packageFirefoxExtensionAmoled",
            ":browser-extension:verifyFirefoxExtensionReleaseArtifacts",
            ":browser-extension:test",
            ":browser-extension:jsTest",
            ":browser-extension:validateFirefoxExtension",
            ":app:checkBrowserIntegration",
            ":core-model:test",
            ":core-utils:test",
            ":transfer-api:test",
            ":browser-integration:testDebugUnitTest",
            ":storage:testDebugUnitTest",
            ":transfer-native:testDebugUnitTest",
            ":transfer-aria2:test",
            ":scheduler:testDebugUnitTest",
            ":media:test",
            ":persistence:testDebugUnitTest",
            ":app:testDebugUnitTest",
            "validate-phase-48-final-ux-release-gate.py",
        ).forEach { required -> assertTrue("Release gate script missing $required", script.contains(required)) }
    }

    @Test
    fun finalUxAndReleaseDocsArePresent() {
        val doc = File(root, "docs/architecture/PHASE-48-FINAL-UX-RELEASE-GATE.md").readText()
        assertTrue(doc.contains("review-first"))
        assertTrue(doc.contains("358 passed, 0 failed, 0 skipped"))
        assertTrue(doc.contains("0 warnings, 0 errors"))
        assertTrue(doc.contains("Browser runtime must not be reintroduced"))
        assertTrue(doc.contains("Generated XPI files must not be committed as source"))
        assertTrue(doc.contains("Ship / no-ship gate"))
    }

    @Test
    fun noPlaceholderClickHandlersRemainSealed() {
        val uiFiles = UiSourceTree.files(root) + File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApp.kt")
        uiFiles.forEach { file ->
            val text = file.readText()
            assertFalse("${file.name} contains placeholder click handlers", text.contains("onClick = {}"))
        }
    }

    @Test
    fun generatedExtensionArtifactsAreNotCommittedAsSource() {
        val sourceRoot = File(root, "browser-extension/src/main/extension/xdm-firefox")
        val xpis = sourceRoot.walkTopDown().filter { it.isFile && it.extension == "xpi" }.toList()
        assertTrue("Generated XPI files must not live in extension source", xpis.isEmpty())
    }

    @Test
    fun phase48CorrectionRecordsRealSharedSniffing() {
        val manifest = File(root, "PROJECT_MANIFEST.json").readText()
        val doc = File(root, "docs/architecture/PHASE-48-FINAL-UX-RELEASE-GATE.md").readText()
        val script = File(root, "tools/run-phase-48-final-release-gate.sh").readText()
        assertTrue(manifest.contains("browser_bridge_phase47_real_shared_media_sniffing_engine"))
        assertTrue(manifest.contains("phase48_corrected_after_audit"))
        assertTrue(doc.contains("browser_bridge_phase47_real_shared_media_sniffing_engine"))
        assertTrue(script.contains("validate-phase-47-real-shared-media-sniffing-engine.py"))
    }

    private fun androidRoot(): File {
        var cursor = File(System.getProperty("user.dir") ?: ".").canonicalFile
        repeat(8) {
            if (File(cursor, "settings.gradle.kts").isFile && File(cursor, "app/src/main").isDirectory) return cursor
            cursor = cursor.parentFile ?: return@repeat
        }
        error("Unable to locate XDM Android root")
    }
}
