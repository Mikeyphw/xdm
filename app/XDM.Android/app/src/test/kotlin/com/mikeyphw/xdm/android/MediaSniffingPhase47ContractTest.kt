package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSniffingPhase47ContractTest {
    private val root = androidRoot()

    @Test
    fun sharedEngineExistsWithBoundedProbeAndPrivacyContract() {
        val source = root.resolve("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaSniffingEngine.kt").readText()
        assertTrue(source.contains("data class MediaSniffingInput"))
        assertTrue(source.contains("data class MediaSniffingCandidate"))
        assertTrue(source.contains("class MediaSniffingEngine"))
        assertTrue(source.contains("class MediaPageProbe"))
        assertTrue(source.contains("bodyPrefixBytes: Int = 768 * 1024"))
        assertTrue(source.contains("connectTimeoutMillis: Int = 10_000"))
        assertTrue(source.contains("no arbitrary JavaScript execution"))
        assertTrue(source.contains("no DRM bypass"))
        assertTrue(source.contains("PrivacyDiagnosticsRedactor"))
    }

    @Test
    fun appRoutesSharesAutomationBatchAndExternalReviewThroughSharedSniffer() {
        val viewModel = root.resolve("app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt").readText()
        val batch = root.resolve("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaBatchIntake.kt").readText()
        val external = root.resolve("media/src/main/kotlin/com/mikeyphw/xdm/android/media/ExternalMediaReviewIntake.kt").readText()
        val usesOriginalSharedSniffer = viewModel.contains("private val mediaSniffingEngine = MediaSniffingEngine(mediaCaptureService)")
        val usesRecorderBackedSharedSniffer = viewModel.contains("private val mediaSniffingEngine = MediaSniffingEngine(") &&
            viewModel.contains("mediaCaptureService") &&
            viewModel.contains("debugRecorder = debugEventRecorder")
        assertTrue(
            "MainViewModel must keep routing shared/media handoffs through the shared sniffer when debug recording is wired",
            usesOriginalSharedSniffer || usesRecorderBackedSharedSniffer,
        )
        assertTrue(viewModel.contains("MediaSniffingSource.SharedText"))
        assertTrue(viewModel.contains("MediaSniffingSource.BrowserExtension"))
        assertTrue(viewModel.contains("sniffingPlan.records"))
        assertTrue(viewModel.contains("sniffingPlan.variants"))
        assertTrue(batch.contains("MediaSniffingSource.BatchInput"))
        assertTrue(batch.contains("sniffingDiagnostics"))
        assertTrue(external.contains("MediaSniffingSource.ManualPage"))
        assertTrue(external.contains("sniffingPlan.records.firstOrNull"))
    }

    @Test
    fun mediaBatchAddSelectedIsSelectionBasedAndFinalSealRecordsCorrection() {
        val screen = root.resolve("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/media/MediaInboxScreen.kt").readText()
        val manifest = root.resolve("PROJECT_MANIFEST.json").readText()
        assertTrue(screen.contains("Checkbox"))
        assertTrue(screen.contains("selectedUrls"))
        assertTrue(screen.contains("onAddSelected"))
        assertTrue(screen.contains("shared app-side media sniffing engine"))
        assertTrue(screen.contains("Static sniff does not execute page JavaScript"))
        assertTrue(screen.contains("Live media locator"))
        assertFalse(screen.contains("TextButton(onClick = onInspectAll, enabled = text.isNotBlank()) { Text(\"Add selected\") }"))
        assertTrue(manifest.contains("browser_bridge_phase47_real_shared_media_sniffing_engine"))
        assertTrue(manifest.contains("phase48_corrected_after_audit"))
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
