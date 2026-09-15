package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Xar15PrivacySecurityPerformanceContractTest {
    private val root = generateSequence(File(System.getProperty("user.dir") ?: ".").canonicalFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile && File(it, "app/src/main").isDirectory }
    private fun text(path: String) = File(root, path).readText()

    @Test
    fun xar15GuardsCrossCuttingPrivacySecurityAndPerformancePaths() {
        val activity = text("app/src/main/kotlin/com/mikeyphw/xdm/android/MainActivity.kt")
        val external = text("app/src/main/kotlin/com/mikeyphw/xdm/android/ExternalHandoffReviewActivity.kt")
        val externalSecurity = text("app/src/main/kotlin/com/mikeyphw/xdm/android/ExternalAutomationSecurity.kt")
        val artwork = text("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/media/XdmMediaArtwork.kt")
        val runtime = text("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferExecutionRuntime.kt")
        val handoff = text("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/MediaRequestHandoffStore.kt")
        val app = text("app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApplication.kt")
        val registry = text("media/src/main/kotlin/com/mikeyphw/xdm/android/media/BrowserCaptureSessionRegistry.kt")
        val releaseManifest = text("app/src/release/AndroidManifest.xml")

        assertTrue(activity.contains("InternalBrowserCaptureApprovalGate.consume"))
        assertTrue(external.contains("InternalBrowserCaptureApprovalGate.approve"))
        assertTrue(externalSecurity.contains("clipValuesWithoutCoercion"))
        assertFalse(externalSecurity.contains("coerceToText(activity)"))
        assertTrue(artwork.contains("ExternalUrlPolicy.classifyNetworkTarget"))
        assertTrue(artwork.contains("networkPermits.tryAcquire"))
        assertTrue(artwork.contains("decodePermits.tryAcquire"))
        assertTrue(artwork.contains("cleanupStaleTempFiles"))
        assertTrue(runtime.contains("catch (error: CancellationException)"))
        assertTrue(runtime.contains("LIVE_SUMMARY_PROJECTION_INTERVAL_MS"))
        assertTrue(handoff.contains("fun sweepExpired"))
        assertTrue(app.contains("MediaRequestHandoffStore.sweepExpired()"))
        assertTrue(registry.contains("MAX_SESSIONS"))
        assertTrue(releaseManifest.contains("MANAGE_EXTERNAL_STORAGE") && releaseManifest.contains("tools:node=\"remove\""))
    }
}
