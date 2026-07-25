package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserRemovalPhase3ContractTest {
    @Test
    fun externalHandoffCarriesClassificationMetadataIntoReview() {
        val root = androidRoot()
        val activity = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/MainActivity.kt").readText()
        val intake = File(root, "core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DownloadIntake.kt").readText()
        val shell = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApp.kt").readText()
        val screens = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt").readText()

        listOf("handoffMimeType", "handoffContentLength", "handoffPageUrl", "mimeType = mimeType", "contentLength = contentLength")
            .forEach { marker -> assertTrue("External handoff lost $marker", activity.contains(marker)) }
        listOf("enum class DownloadIntakeKind", "object DownloadIntakeClassifier", "canInspectAsMedia")
            .forEach { marker -> assertTrue("Neutral intake lost $marker", intake.contains(marker)) }
        assertTrue(shell.contains("externalKind = state.externalAddDraft?.kind"))
        assertTrue(shell.contains("onInspectMedia = { state.externalAddDraft?.let(viewModel::inspectExternalMedia) }"))
        assertTrue(screens.contains("Inspect as media"))
        assertTrue(screens.contains("Start direct download"))
        assertTrue(screens.contains("XDM never auto-queues external handoffs"))
    }

    @Test
    fun explicitMediaInspectionSeedsExistingResolverWithoutAutoQueue() {
        val root = androidRoot()
        val planner = File(root, "media/src/main/kotlin/com/mikeyphw/xdm/android/media/ExternalMediaReviewIntake.kt").readText()
        val viewModel = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt").readText()

        listOf("class ExternalMediaReviewPlanner", "ExternalMediaReviewIntake", "MediaSourceKind.Unknown", "isPageProbe")
            .forEach { marker -> assertTrue("Media review planner lost $marker", planner.contains(marker)) }
        assertTrue(viewModel.contains("fun inspectExternalMedia(draft: DownloadIntakeDraft)"))
        val block = viewModel.substringAfter("fun inspectExternalMedia").substringBefore("fun ")
        assertTrue(block.contains("externalMediaReviewPlanner.plan(draft)"))
        assertTrue(block.contains("repository.saveMediaCapture"))
        assertTrue(block.contains("navigate(AppRoute.Media)"))
        assertFalse(block.contains("executionStarter.start"))
        assertFalse(block.contains("addDownload("))
    }

    @Test
    fun phaseThreeReplacementPathAndTransferEnginesRemainAfterRuntimeRemoval() {
        val root = androidRoot()
        val manifest = File(root, "app/src/main/AndroidManifest.xml").readText()

        assertTrue(manifest.contains("android:name=\".ExternalAddDownloadActivity\""))
        assertFalse(manifest.contains("android:name=\".BrowserActivity\""))
        assertTrue(File(root, "transfer-native/src/main/kotlin/com/mikeyphw/xdm/android/transfer/nativeengine/NativeHttpDownloadBackend.kt").isFile)
        assertTrue(File(root, "transfer-aria2/src/main/kotlin/com/mikeyphw/xdm/android/transfer/aria2/EmbeddedAria2Backend.kt").isFile)
        assertTrue(File(root, "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaExecutionLibrary.kt").isFile)
    }

    private fun androidRoot(): File = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile && File(it, "app").isDirectory }
        ?: error("Unable to locate XDM Android root")
}
