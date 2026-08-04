package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BugHuntPhase5BrowserHandoffMediaContractTest {
    private val root = File(System.getProperty("user.dir"))

    @Test fun pastePageUrlAndCheckAgainUseRealPageProbe() {
        val app = File(root, "src/main/kotlin/com/mikeyphw/xdm/android/XdmApp.kt").readText()
        val vm = File(root, "src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt").readText()
        assertTrue(app.contains("onPastePageUrl = viewModel::capturePageUrl"))
        assertTrue(vm.contains("fun capturePageUrl(pageUrl: String"))
        assertTrue(vm.contains("mediaPageProbe.probePage(normalized"))
        assertTrue(vm.contains("mediaPageProbe.probePage(probeUrl"))
        assertFalse(vm.contains("record.id + \":selected\""))
    }

    @Test fun androidIntakeConsumesStableSessionFinalHeadersAndProof() {
        val vm = File(root, "src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt").readText()
        val activity = File(root, "src/main/kotlin/com/mikeyphw/xdm/android/MainActivity.kt").readText()
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
        val app = File(root, "src/main/kotlin/com/mikeyphw/xdm/android/XdmApplication.kt").readText()
        assertTrue(app.contains("FileBackedBrowserHandoffMediaSessionStore(File(filesDir, \"browser-handoff-media-sessions\")"))
        assertTrue(app.contains("browserHandoffMediaCoordinator = browserHandoffMediaCoordinator"))
    }
}
