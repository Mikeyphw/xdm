package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserRemovalPhase5ContractTest {
    @Test
    fun browserOnlyPersistenceAndContractsAreGoneWhileDownloaderIntegrationSurvives() {
        val root = androidRoot()
        val quality = File(root, "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaCaptureQuality.kt").readText()
        val privacy = File(root, "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaSessionPrivacyAudit.kt").readText()
        val mobile = File(root, "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaMobilePolish.kt").readText()
        val manifest = File(root, "app/src/main/AndroidManifest.xml").readText()
        val projectManifest = File(root, "PROJECT_MANIFEST.json").readText()
        val finalGate = File(root, "tools/run-final-release-gate.sh").readText()

        val retiredBrowserDocs = File(root, "docs/browser")
        assertTrue(!retiredBrowserDocs.exists() || retiredBrowserDocs.walkTopDown().none { it.isFile })
        assertFalse(File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/BrowserScreen.kt").exists())
        assertFalse(File(root, "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaBrowserCaptureQuality.kt").exists())
        assertTrue(File(root, "docs/archive/BUILT-IN-BROWSER-HISTORY.md").isFile)
        assertTrue(quality.contains("class MediaCaptureQualityPlanner"))
        assertTrue(quality.contains("data class MediaCaptureQualityDashboard"))
        assertFalse(quality.contains("WebView"))
        assertTrue(privacy.contains("ExternalPageContext"))
        assertFalse(privacy.contains("BrowserProfile"))
        assertFalse(mobile.contains("BrowserFocused"))
        assertFalse(mobile.contains("browserVisible"))
        assertTrue(manifest.contains(".ExternalAddDownloadActivity"))
        assertTrue(manifest.contains("com.android.browser.action.DOWNLOAD"))
        assertFalse(manifest.contains(".BrowserActivity"))
        assertTrue(projectManifest.contains("browser_removal_phase5"))
        assertFalse(projectManifest.contains("phase50_browser_downloader_ux_polish_seal"))
        assertTrue(finalGate.contains("validate-bug-hunt-phase11-validation-matrix.py"))
    }

    private fun androidRoot(): File {
        var current = File(System.getProperty("user.dir") ?: ".").canonicalFile
        repeat(8) {
            if (File(current, "PROJECT_MANIFEST.json").isFile && File(current, "app").isDirectory) return current
            current = current.parentFile ?: return@repeat
        }
        error("Could not locate XDM.Android root")
    }
}
