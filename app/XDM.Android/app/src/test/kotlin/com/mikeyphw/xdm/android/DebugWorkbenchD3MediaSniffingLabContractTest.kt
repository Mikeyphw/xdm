package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugWorkbenchD3MediaSniffingLabContractTest {
    private val root = androidRoot()

    @Test
    fun debugWorkbenchShellHostsMediaSniffingLabWithoutTopLevelRoute() {
        val shell = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/debug/DebugWorkbenchSettingsScreen.kt").readText()
        val lab = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/debug/MediaSniffingLabCard.kt").readText()
        val appRoute = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/AppRoute.kt").readText()

        assertTrue(shell.contains("MediaSniffingLabCard()"))
        assertTrue(lab.contains("Media Sniffing Lab"))
        assertTrue(lab.contains("Run static sniff"))
        assertTrue(lab.contains("Copy sanitized lab report"))
        assertTrue(lab.contains("MediaSniffingLab.inspect"))
        assertTrue(lab.contains("Manual page"))
        assertTrue(lab.contains("Browser extension"))
        assertFalse("D3 UI must not render raw enum names", lab.contains("option.name"))
        assertTrue(lab.contains("labStateKey"))
        assertTrue(lab.contains("labDisplayLabel"))
        assertFalse("D3 UI must not pass option.name directly into Text", lab.contains("Text(option.name)"))
        assertFalse("D3 must not add a top-level route", appRoute.contains("MediaSniffingLab"))
    }

    @Test
    fun labUsesSharedSniffingEngineWithSafeStaticBoundaries() {
        val model = File(root, "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaSniffingLab.kt").readText()
        val engine = File(root, "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaSniffingEngine.kt").readText()

        assertTrue(model.contains("MediaSniffingEngine"))
        assertTrue(model.contains("MediaSniffingInput"))
        assertTrue(model.contains("No network page probe"))
        assertTrue(model.contains("no arbitrary JavaScript execution"))
        assertTrue(model.contains("no DRM bypass"))
        assertTrue(model.contains("PrivacyDiagnosticsRedactor"))
        assertFalse("D3 lab must not start downloads", model.contains("repository.save"))
        assertFalse("D3 lab must not invoke the page probe", model.contains("MediaPageProbe("))
        assertTrue("Phase 47 engine must remain shared", engine.contains("class MediaSniffingEngine"))
    }

    @Test
    fun d3ManifestDocsValidatorAndMediaTestsAreRecorded() {
        val manifest = File(root, "PROJECT_MANIFEST.json").readText()
        val doc = File(root, "docs/architecture/DEBUG-WORKBENCH-D3-MEDIA-SNIFFING-LAB.md").readText()
        val validator = File(root, "tools/validate-debug-workbench-d3-media-sniffing-lab.py").readText()
        val mediaTest = File(root, "media/src/test/kotlin/com/mikeyphw/xdm/android/media/MediaSniffingLabTest.kt").readText()

        assertTrue(manifest.contains("debug_workbench_phase_d3_media_sniffing_lab"))
        assertTrue(manifest.contains("debug_workbench_phase_d4_browser_bridge_add_download_debugger"))
        assertTrue(doc.contains("Settings → Debug Workbench → Media Sniffing Lab"))
        assertTrue(doc.contains("static sniff only"))
        assertTrue(validator.contains("MediaSniffingLabCard.kt"))
        assertTrue(mediaTest.contains("labRunsSharedStaticSnifferAndRedactsCopyReport"))
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
