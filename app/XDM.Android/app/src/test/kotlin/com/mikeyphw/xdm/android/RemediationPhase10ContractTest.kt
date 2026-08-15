package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemediationPhase10ContractTest {
    private val root = androidRoot()

    @Test
    fun mediaResolverUsesExactVariantHeadersDestinationAndDispatchGateBeforeMutation() {
        val planner = source("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaDownloadPlanner.kt")
        val execution = source("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaExecutionLibrary.kt")
        val dispatcher = source("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaExecutionDispatcher.kt")
        val viewModel = source("app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt")
        val downloadFlow = viewModel.substringAfter("fun downloadMediaCapture(record: MediaCaptureRecord")
            .substringBefore("fun resolveMediaCapture")

        assertTrue(planner.contains("variantSessionHeaders"))
        assertTrue(planner.contains("selectedVariantHeaders"))
        assertTrue(planner.contains("mergeSessionHeaders(sessionHeaders, selectedVariantHeaders)"))
        assertTrue(execution.contains("val destinationUri: String"))
        assertTrue(execution.contains("spec.ytDlpFormatSelector"))
        assertTrue(dispatcher.contains("spec.ytDlpFormatSelector.isNullOrBlank()"))
        assertTrue(downloadFlow.contains("destinationUri = prefs.destinationUri"))
        assertTrue(downloadFlow.contains("destinationUri = spec.destinationUri"))
        assertFalse(downloadFlow.contains("DestinationUris.PUBLIC_DOWNLOADS"))
        assertTrue(downloadFlow.contains("mediaExecutionDispatcher.dispatchPlan"))
        assertTrue(downloadFlow.indexOf("mediaExecutionDispatcher.dispatchPlan") < downloadFlow.indexOf("enqueueYtDlpDownload"))
        assertTrue(downloadFlow.indexOf("mediaExecutionDispatcher.dispatchPlan") < downloadFlow.indexOf("createDownloadFromMediaCapture"))
    }

    @Test
    fun termuxMediaOwnsItsJobAndNeverCreatesASyntheticQueuedDownload() {
        val viewModel = source("app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt")
        val manager = source("app/src/main/kotlin/com/mikeyphw/xdm/android/termux/TermuxMediaPipelineManager.kt")
        val downloadFlow = viewModel.substringAfter("fun downloadMediaCapture(record: MediaCaptureRecord")
            .substringBefore("fun resolveMediaCapture")
        val termuxBranch = downloadFlow.substringAfter("if (spec.requiresTermuxYtDlp)")
            .substringBefore("if (!spec.canUseAppQueue)")

        assertTrue(termuxBranch.contains("enqueueYtDlpDownload"))
        assertFalse(termuxBranch.contains("val download = Download("))
        assertFalse(downloadFlow.contains("termux-media"))
        assertTrue(manager.contains("database.withTransaction"))
        assertTrue(manager.contains("dao.insertJob(entity)"))
        assertTrue(manager.contains("repository.saveMediaOutput(mediaOutputRecord(entity, spec, mediaOutputSeed))"))
        assertTrue(manager.contains("Authenticated media needs the secure transient Termux session bridge"))
        assertTrue(manager.contains("failed.kind == PostProcessingActionKind.YtDlpDownload.name"))
        assertTrue(manager.contains("repository.saveMediaOutput(mediaOutputRecord(retryEntity, spec, seed))"))
    }

    @Test
    fun roomAndLibraryRepresentOneCaptureWithManyOutputGenerations() {
        val model = source("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DownloadModels.kt")
        val entity = source("persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/Entities.kt")
        val database = source("persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/AppDatabase.kt")
        val migrations = source("persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/Migrations.kt")
        val dao = source("persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/MediaCaptureDao.kt")
        val repository = source("persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/DownloadRepository.kt")
        val library = source("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaExecutionLibrary.kt")
        val manager = source("app/src/main/kotlin/com/mikeyphw/xdm/android/termux/TermuxMediaPipelineManager.kt")
        val viewModel = source("app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt")
        val app = source("app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApp.kt")
        val screen = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/library/MediaLibraryScreen.kt")
        val schema = source("persistence/schemas/com.mikeyphw.xdm.android.persistence.AppDatabase/20.json")

        assertTrue(model.contains("data class MediaOutputRecord"))
        assertTrue(entity.contains("tableName = \"media_outputs\""))
        assertTrue(entity.contains("Index(value = [\"ownerKind\", \"ownerId\", \"attemptGeneration\"], unique = true)"))
        assertTrue(database.contains("version = 20"))
        assertTrue(migrations.contains("Migration19To20 = object : Migration(19, 20)"))
        assertTrue(migrations.contains("INSERT OR IGNORE INTO media_outputs"))
        assertTrue(repository.contains("createDownloadFromMediaCapture"))
        assertTrue(repository.contains("database.mediaCaptureDao().upsertOutput(output.toEntity())"))
        assertTrue(dao.contains("DELETE FROM media_outputs WHERE id = :id"))
        assertTrue(repository.contains("deleteMediaOutput"))
        assertTrue(library.contains("outputs.asSequence()"))
        assertTrue(library.contains("outputs.isEmpty() && allowLegacyFallback"))
        assertTrue(library.contains("outputId = output.id"))
        assertTrue(library.contains("val completed = download.state == DownloadState.Completed"))
        assertTrue(manager.contains("suspend fun removeLibraryOutput(jobId: String, outputId: String)"))
        assertTrue(manager.contains("dao.deleteJob(jobId)"))
        assertTrue(manager.contains("database.mediaCaptureDao().deleteOutput(outputId)"))
        assertTrue(manager.contains("val durableJob = dao.findJob(job.id) ?: return@withTransaction"))
        assertTrue(viewModel.contains("fun removeMediaLibraryRecord(item: OfflineMediaLibraryItem)"))
        assertTrue(app.contains("MediaLibraryScreen as OutputMediaLibraryScreen"))
        assertTrue(app.contains("onRemoveRecord = viewModel::removeMediaLibraryRecord"))
        assertTrue(screen.contains("key = OfflineMediaLibraryItem::outputId"))
        assertTrue(screen.contains("allowLegacyFallback = false"))
        assertTrue(screen.contains("onRemoveRecord(item)"))
        assertFalse(screen.contains("onRemoveRecord(record)"))
        assertTrue(schema.contains("\\\"tableName\\\": \\\"media_outputs\\\"") || schema.contains("\"tableName\": \"media_outputs\""))
    }

    @Test
    fun appMediaLineageSurvivesRepeatOutputsRetriesAndUserRemoval() {
        val model = source("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DownloadModels.kt")
        val dao = source("persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/MediaCaptureDao.kt")
        val graphDao = source("persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/DownloadGraphTransactionDao.kt")
        val repository = source("persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/DownloadRepository.kt")
        val viewModel = source("app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt")
        val inbox = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/media/MediaInboxScreen.kt")
        val workspace = source("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaConsumerWorkspace.kt")
        val library = source("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaExecutionLibrary.kt")
        val downloadFlow = viewModel.substringAfter("fun downloadMediaCapture(record: MediaCaptureRecord")
            .substringBefore("fun resolveMediaCapture")
        val removeCaptureFlow = viewModel.substringAfter("fun removeMediaCapture(record: MediaCaptureRecord)")
            .substringBefore("fun removeMediaLibraryRecord")

        assertTrue(model.contains("Hidden,"))
        assertTrue(dao.contains("appOutputsForDownload(downloadId: String)"))
        assertTrue(dao.contains("hideAppOutput"))
        assertTrue(repository.contains("synchronizeAppMediaOutputLocked(download)"))
        assertTrue(repository.contains("existing?.state == MediaOutputState.Hidden.name"))
        assertTrue(repository.contains("createReplacementDownloadPreservingMediaLineage"))
        assertTrue(viewModel.contains("createReplacementDownloadPreservingMediaLineage(current.id, retry, now)"))
        assertTrue(viewModel.contains("hideAppMediaOutput(item.outputId)"))
        assertFalse(downloadFlow.contains("forgetCapture(record.id)"))
        assertTrue(removeCaptureFlow.contains("variantsForMediaCapture(record.id)"))
        assertTrue(removeCaptureFlow.contains("forgetVariant"))
        assertFalse(inbox.contains("capture.status == MediaCaptureStatus.DownloadCreated"))
        assertTrue(workspace.contains("another output generation"))
        assertTrue(workspace.contains("Download again"))
        assertTrue(library.contains("filterNot { it.state == MediaOutputState.Hidden }"))
        assertTrue(library.contains("download.attemptGeneration == output.attemptGeneration"))
        assertTrue(library.contains("historicalAppOutputLibraryItem"))
        assertTrue(library.contains("val playback = download.takeIf { completed }?.let(::completedPlaybackUrl)"))
        assertFalse(library.contains("output.completedArtifactUri\n            ?.takeIf"))
        assertTrue(graphDao.contains("completedArtifactUri = :completedArtifactUri"))
        assertTrue(graphDao.contains("completedArtifactGeneration = :completedArtifactGeneration"))
        assertTrue(graphDao.contains("completedArtifactBytes = :completedArtifactBytes"))
    }

    @Test
    fun mediaFailureClassificationIsStructuredRatherThanMessageSubstringDriven() {
        val library = source("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaExecutionLibrary.kt")
        val classifier = library.substringAfter("fun classifyFailure")
            .substringBefore("fun failureReason")
        val externalStage = library.substringAfter("private fun executionJobForExternal")
            .substringBefore("private fun sidecar")

        assertTrue(classifier.contains("download.backend == BackendType.Aria2"))
        assertTrue(classifier.contains("download?.state == DownloadState.Failed"))
        assertFalse(classifier.contains("contains("))
        assertFalse(classifier.contains("lowercase("))
        assertTrue(externalStage.contains("external.metadataOnly"))
        assertFalse(externalStage.contains("kindLabel.contains"))
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
