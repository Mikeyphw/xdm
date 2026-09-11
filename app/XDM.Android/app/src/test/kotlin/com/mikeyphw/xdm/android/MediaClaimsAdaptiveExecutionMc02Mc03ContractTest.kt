package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaClaimsAdaptiveExecutionMc02Mc03ContractTest {
    private val root = androidRoot()

    @Test
    fun ordinaryAndRepeatMediaActionsHaveDistinctDurableAdmissionIntent() {
        val model = source("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DownloadModels.kt")
        val repository = source("persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/DownloadRepository.kt")
        val termux = source("app/src/main/kotlin/com/mikeyphw/xdm/android/termux/TermuxMediaPipelineManager.kt")
        val viewModel = source("app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt")
        val card = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/media/MediaCaptureCard.kt")

        assertTrue(model.contains("enum class MediaOutputAdmissionMode { Primary, AdditionalGeneration }"))
        assertTrue(repository.contains("admissionMode == MediaOutputAdmissionMode.Primary && existingOutput != null"))
        assertTrue(termux.contains("mediaOutputSeed.admissionMode == MediaOutputAdmissionMode.Primary && existing != null"))
        assertTrue(viewModel.contains("mediaOutputAdmissionClaims.add(record.id)"))
        assertTrue(viewModel.contains("admissionMode = admissionMode"))
        assertTrue(card.contains("MediaOutputAdmissionMode.AdditionalGeneration"))
        assertTrue(card.contains("MediaOutputAdmissionMode.Primary"))
        assertTrue(card.contains("\"Adding…\""))
        assertFalse(card.contains(") { Text(\"Download\") }"))
    }

    @Test
    fun removalPreservesLineageWhenOutputsExist() {
        val model = source("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DownloadModels.kt")
        val repository = source("persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/DownloadRepository.kt")
        val viewModel = source("app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt")
        val inbox = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/media/MediaInboxScreen.kt")

        assertTrue(model.contains("Expired, Archived"))
        assertTrue(repository.contains("archiveOrDeleteMediaCapture"))
        assertTrue(repository.contains("if (outputs.isEmpty())"))
        assertTrue(repository.contains("MediaCaptureStatus.Archived.name"))
        assertTrue(viewModel.contains("repository.archiveOrDeleteMediaCapture(record.id)"))
        assertTrue(viewModel.contains("MediaCaptureRemovalResult.Deleted ->"))
        assertTrue(viewModel.contains("MediaCaptureRemovalResult.Archived ->"))
        assertTrue(viewModel.contains("MediaRequestHandoffStore.forgetCapture(record.id)"))
        val archivedBranch = viewModel.substringAfter("MediaCaptureRemovalResult.Archived ->").substringBefore("browserCaptureSessionRegistry.removeCapture")
        assertFalse(archivedBranch.contains("forgetCapture"))
        assertFalse(archivedBranch.contains("forgetVariant"))
        assertTrue(inbox.contains("it.status != MediaCaptureStatus.Archived"))
    }

    @Test
    fun adaptiveExecutionUsesNativeHlsOrYtDlpWithAuthoritativeCaptureOrPage() {
        val planner = source("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaDownloadPlanner.kt")
        val models = source("app/src/main/kotlin/com/mikeyphw/xdm/android/termux/PostProcessingExecutionModels.kt")
        val manager = source("app/src/main/kotlin/com/mikeyphw/xdm/android/termux/TermuxMediaPipelineManager.kt")
        val transient = manager.substringAfter("private fun transientYtDlpSession")
            .substringBefore("private fun ytDlpConfigQuote")

        assertTrue(planner.contains("MediaDownloadStrategy.NativeHls") && planner.contains("MediaDownloadStrategy.YtDlp"))
        assertTrue(planner.contains("ytDlpExtraArguments"))
        assertTrue(planner.contains("--sub-langs"))
        assertTrue(planner.contains("[height=\$it]"))
        assertTrue(planner.contains("[language^=\$it]"))
        assertTrue(models.contains("val sessionUsePageUrl: Boolean = false"))
        assertTrue(models.contains(".put(\"sessionUsePageUrl\", sessionUsePageUrl)"))
        assertTrue(manager.contains("extraArguments = plan.ytDlpExtraArguments"))
        assertTrue(manager.contains("sessionUsePageUrl = plan.ytDlpUsePageUrl"))
        assertTrue(transient.contains("if (spec.sessionUsePageUrl)"))
        assertTrue(transient.contains("handoff.pageUrl"))
        assertTrue(transient.contains("handoff.exactUrl"))
        assertFalse(transient.contains("primary?.exactUrl"))
    }

    private fun source(relative: String): String = File(root, relative).readText()

    private fun androidRoot(): File {
        var cursor = File(System.getProperty("user.dir") ?: ".").canonicalFile
        repeat(8) {
            if (File(cursor, "settings.gradle.kts").isFile && File(cursor, "app/src/main").isDirectory) return cursor
            cursor = cursor.parentFile ?: return@repeat
        }
        error("Unable to locate XDM Android root")
    }
}
