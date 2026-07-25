package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserRemovalPhase2ContractTest {
    @Test
    fun neutralDownloadIntakeOwnsReviewMetadataWithoutExecution() {
        val root = androidRoot()
        val source = File(root, "core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DownloadIntake.kt").readText()

        listOf(
            "data class DownloadIntakeDraft",
            "enum class DownloadIntakeOrigin",
            "class DownloadIntakePlanner",
            "fromBuiltInBrowserPage",
            "fromBuiltInBrowserDownload",
            "fromExternal",
            "ExternalUrlPolicy.normalizedUrl",
        ).forEach { marker -> assertTrue("Neutral intake lost $marker", source.contains(marker)) }

        listOf("DownloadRepository", "data class Download(", "executionStarter", "TransferExecution", "WebView", "android.webkit", "android.content").forEach { forbidden ->
            assertFalse("Neutral intake must not depend on $forbidden", source.contains(forbidden))
        }
    }

    @Test
    fun neutralReviewAndMediaEntryPointsSurviveBrowserRemoval() {
        val root = androidRoot()
        val viewModel = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt").readText()
        val shell = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApp.kt").readText()
        val mediaIntake = File(root, "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaCaptureIntake.kt").readText()
        val mediaContract = File(root, "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaInboxContract.kt").readText()

        assertTrue(viewModel.contains("fun openDownloadReview(draft: DownloadIntakeDraft)"))
        assertTrue(viewModel.contains("fun captureMediaRequest(facts: MediaRequestFacts)"))
        assertTrue(mediaContract.contains("data class MediaRequestFacts"))
        assertTrue(mediaIntake.contains("facts: MediaRequestFacts"))
        assertTrue(shell.contains("state.externalAddDraft?.let(viewModel::inspectExternalMedia)"))
        assertFalse(viewModel.contains("fun openAddFromBrowser"))
        assertFalse(viewModel.contains("fun openBrowserDownload"))
        assertFalse(viewModel.contains("fun captureBrowserMediaUrl"))
    }

    @Test
    fun neutralReviewEntryPointCannotStartTransfers() {
        val viewModel = File(androidRoot(), "app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt").readText()
        val block = viewModel.substringAfter("fun openDownloadReview").substringBefore("fun ")
        assertTrue(block.contains("externalAddDraft.value = draft"))
        assertTrue(block.contains("navigate(AppRoute.Add)"))
        assertFalse(block.contains("executionStarter.start"))
        assertFalse(block.contains("repository.save("))
    }

    @Test
    fun phaseTwoNeutralContractsAndDownloaderEnginesRemainAfterRuntimeRemoval() {
        val root = androidRoot()
        val mediaInbox = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt").readText()

        assertFalse("Media screen must not retain unused browser callbacks", mediaInbox.contains("onBrowserMediaRequest") || mediaInbox.contains("onOpenAddForBrowserUrl"))
        assertTrue(File(root, "transfer-native/src/main/kotlin/com/mikeyphw/xdm/android/transfer/nativeengine/NativeHttpDownloadBackend.kt").isFile)
        assertTrue(File(root, "transfer-aria2/src/main/kotlin/com/mikeyphw/xdm/android/transfer/aria2/EmbeddedAria2Backend.kt").isFile)
        assertTrue(File(root, "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaExecutionLibrary.kt").isFile)
        assertFalse(File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/BrowserScreen.kt").exists())
    }

    private fun androidRoot(): File = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile && File(it, "app").isDirectory }
        ?: error("Unable to locate XDM Android root")
}
