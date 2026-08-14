package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BugHuntPhase5BrowserHandoffMediaContractTest {
    private val root = androidRoot()

    @Test fun pastePageUrlAndCheckAgainUseRealPageProbe() {
        val app = source("app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApp.kt")
        val vm = source("app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt")
        val resolver = vm
            .substringAfter("private suspend fun resolveCapturedPlaylistIfPossible(")
            .substringBefore("private fun MediaVariant.rekeyForCapture")
        val pageCapture = vm
            .substringAfter("fun capturePageUrl(pageUrl: String")
            .substringBefore("fun captureSharedText")

        assertTrue("Paste Page URL must call MainViewModel.capturePageUrl", app.contains("onPastePageUrl = viewModel::capturePageUrl"))
        assertTrue("MainViewModel must expose capturePageUrl", vm.contains("fun capturePageUrl(pageUrl: String"))
        assertTrue("Pasted page URLs must probe their normalized page URL", pageCapture.contains("mediaPageProbe.probePage(normalized"))
        assertTrue("Manifest refresh must accept the exact captured candidate URL", resolver.contains("exactUrl: String"))
        assertTrue("Manifest refresh must probe the exact captured candidate URL", resolver.contains("mediaPageProbe.probePage(") && resolver.contains("exactUrl,"))
        assertFalse("The stale probeUrl parameter must not return", resolver.contains("mediaPageProbe.probePage(probeUrl"))
        assertFalse("Selected variants must not synthesize a fake capture ID", vm.contains("record.id + \":selected\""))
    }

    @Test fun androidIntakeConsumesStableSessionFinalHeadersAndProof() {
        val vm = source("app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt")
        val activity = source("app/src/main/kotlin/com/mikeyphw/xdm/android/MainActivity.kt")
        assertTrue(vm.contains("private val browserHandoffMediaCoordinator: BrowserHandoffMediaCoordinator"))
        assertTrue(vm.contains("declaredStableMediaId = facts.stableMediaId"))
        assertTrue(vm.contains("finalHeaders = finalHeaders"))
        assertTrue(vm.contains("facts.requiresPageObservationProof"))
        assertFalse(vm.contains("facts.headers.takeIf { it.containsKey(\"X-XDM-Final-Headers\") }"))
        assertTrue(activity.contains("handoffStableMediaId(incoming)"))
        assertTrue(activity.contains("handoffSessionRevision(incoming)"))
        assertTrue(activity.contains("browserFinalHeaders(incoming)"))
    }

    @Test fun browserSessionsUseAppPrivateFileBackedStore() {
        val app = source("app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApplication.kt")
        assertTrue(app.contains("MediaRequestHandoffStore.initialize(AndroidSecureRequestEnvelopeStore(this))"))
        assertTrue(app.contains("BrowserHandoffMediaCoordinator()"))
        assertTrue(app.contains("browserHandoffMediaCoordinator = browserHandoffMediaCoordinator"))
    }

    private fun source(path: String): String = root.resolve(path).readText()

    private fun androidRoot(): File {
        var cursor = File(System.getProperty("user.dir") ?: ".").canonicalFile
        repeat(8) {
            if (File(cursor, "settings.gradle.kts").isFile && File(cursor, "app/src/main").isDirectory) return cursor
            cursor = cursor.parentFile ?: return@repeat
        }
        error("Unable to locate XDM Android root")
    }
}
