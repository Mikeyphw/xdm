package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FfmpegRoadmapPostSealHotfixContractTest {
    private val root: File = run {
        val userDir = requireNotNull(System.getProperty("user.dir")) { "user.dir is required" }
        generateSequence(File(userDir).canonicalFile) { it.parentFile }
            .mapNotNull { candidate ->
                when {
                    File(candidate, "app/src/main").isDirectory -> candidate
                    File(candidate, "app/XDM.Android/app/src/main").isDirectory -> File(candidate, "app/XDM.Android")
                    else -> null
                }
            }
            .firstOrNull() ?: error("Unable to locate app/XDM.Android from user.dir=$userDir")
    }

    private fun text(path: String) = File(root, path).readText()

    @Test
    fun supportedNativeHlsHasARealAndroidExecutionOwner() {
        val manager = text("app/src/main/kotlin/com/mikeyphw/xdm/android/ffmpeg/NativeHlsMediaManager.kt")
        val vm = text("app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt")
        val app = text("app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApplication.kt")
        val dao = text("persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/NativeHlsDao.kt")

        assertTrue(manager.contains("NativeHlsExecutionEngine()"))
        assertTrue(manager.contains("NativeHlsFfmpegFinalizer"))
        assertTrue(manager.contains("finalizer.finalize"))
        assertTrue(manager.contains("prepared.promote()"))
        assertTrue(manager.contains("AndroidTransferRequestSecurityGuard"))
        assertTrue(manager.contains("MediaRequestHandoffStore.forDownload"))
        assertTrue(manager.contains("AES/CBC/PKCS5Padding"))
        assertTrue(manager.contains("response.code == 206"))
        assertTrue(manager.contains("for (attempt in 0 until 3)"))
        assertTrue(manager.contains("catch (cancelled: CancellationException)"))
        assertTrue(manager.contains("throw cancelled"))
        assertTrue(vm.contains("enginePlan.lane == MediaExecutionLane.NativeHlsSegmented"))
        assertTrue(vm.contains("nativeHlsMediaManager.enqueue"))
        assertTrue(vm.contains("nativeHlsMediaManager.pause"))
        assertTrue(vm.contains("nativeHlsMediaManager.resume"))
        assertTrue(vm.contains("nativeHlsMediaManager.cancel"))
        assertTrue(app.contains("NativeHlsMediaManager("))
        assertTrue(app.contains("val recovery = transferRuntime.recoverForStartup()"))
        assertTrue(app.contains("val nativeHlsRecovery = runCatching { nativeHlsMediaManager.recoverInterruptedJobs() }"))
        assertTrue(manager.contains("reconcileCommittedPublication(job)"))
        assertTrue(manager.contains("FinalizationJournalStage.DestinationCommitted"))
        assertTrue(manager.contains("repository.finalizationForDownload(job.downloadId)"))
        assertTrue(manager.contains("repository.saveFinalizationJournal"))
        assertTrue(manager.contains("completedArtifactSha256 = digest"))
        assertTrue(manager.contains("repository.deleteRecoveryForDownload(download.id)"))
        assertTrue(dao.contains("findByDownloadId"))
        assertTrue(dao.contains("activeJobs"))
    }

    @Test
    fun ordinaryLocalFfmpegWorkIsEmbeddedButExplicitFallbackStaysTermux() {
        val actions = text("app/src/main/kotlin/com/mikeyphw/xdm/android/termux/PostProcessingAutomationModels.kt")
        val spec = text("app/src/main/kotlin/com/mikeyphw/xdm/android/termux/PostProcessingExecutionModels.kt")
        val manager = text("app/src/main/kotlin/com/mikeyphw/xdm/android/termux/TermuxMediaPipelineManager.kt")
        val automation = text("app/src/main/kotlin/com/mikeyphw/xdm/android/termux/PostProcessingAutomationManager.kt")

        for (kind in listOf("FfprobeInspect", "RemuxFastStart", "ExtractAudio", "FfmpegRemux")) {
            val declaration = Regex("$kind\\(.*requiresTermux = false").containsMatchIn(actions)
            assertTrue("$kind should be Android-owned by default", declaration)
        }
        assertTrue(spec.contains("val externalFfmpegFallback: Boolean = false"))
        assertTrue(spec.contains("spec.kind.requiresTermux || spec.externalFfmpegFallback"))
        assertTrue(manager.contains("runEmbeddedMediaAction"))
        assertTrue(manager.contains("FfmpegPostProcessor(embeddedFfmpegRuntime"))
        assertTrue(manager.contains("embeddedFfmpegRuntime.probe"))
        assertTrue(manager.contains("private suspend fun embeddedToolVersionsJson(): String"))
        assertTrue(manager.contains("externalFfmpegFallback = true"))
        assertTrue(manager.contains("ffmpegFallbackReadinessIssue"))
        assertTrue(automation.contains("PostProcessingActionKind.FfprobeInspect, PostProcessingActionKind.RemuxFastStart, PostProcessingActionKind.ExtractAudio, PostProcessingActionKind.FfmpegRemux -> emptySet()"))
        assertFalse(manager.contains("Android embedded FFmpeg/FFprobe runtime owns this operation; Termux is not required.\"\n                externalFfmpegFallback = true"))
    }

    @Test
    fun nativeHlsRecoveryScopesPartsPerJobAndVerifiesActualStreamShape() {
        val manager = text("app/src/main/kotlin/com/mikeyphw/xdm/android/ffmpeg/NativeHlsMediaManager.kt")
        val finalizer = text("app/src/main/kotlin/com/mikeyphw/xdm/android/ffmpeg/NativeHlsFfmpegFinalizer.kt")
        val engine = text("media/src/main/kotlin/com/mikeyphw/xdm/android/media/NativeHlsExecutionEngine.kt")
        val planner = text("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaDownloadPlanner.kt")

        assertTrue(manager.contains("partEntityId(jobId, index)"))
        assertTrue(manager.contains("partIdentityMatches(current, part)"))
        assertTrue(manager.contains("digest(key.toByteArray(Charsets.UTF_8))"))
        assertFalse(manager.contains("key.substringAfterLast('/')"))
        assertTrue(manager.contains("row.mediaSequence == part.mediaSequence"))
        assertTrue(manager.contains("persistableMapIdentity(part.initMap)"))
        assertTrue(manager.contains("#xdm-map-range="))
        assertTrue(manager.contains("worker?.cancel"))
        assertTrue(manager.contains("worker?.join()"))
        assertTrue(manager.contains("running.compute(row.downloadId)"))
        assertTrue(manager.contains("existing != null && !existing.isCompleted"))
        assertTrue(manager.contains("CoroutineStart.LAZY"))
        assertTrue(manager.contains("requestedControl[row.downloadId] != null"))
        assertFalse(manager.contains("running.remove(downloadId)?.cancel"))
        assertTrue(manager.contains("suspendCancellableCoroutine"))
        assertTrue(manager.contains("invokeOnCancellation { call.cancel() }"))
        assertTrue(manager.contains("nativeHlsOutputMime(finalFileName, plan)"))
        assertTrue(finalizer.contains("requireVideo = plan.requireVideoStream"))
        assertTrue(finalizer.contains("requireAudio = plan.requireAudioStream"))
        assertTrue(engine.contains("val requireVideoStream: Boolean"))
        assertTrue(engine.contains("val requireAudioStream: Boolean"))
        assertTrue(engine.contains("MasterPlaylist"))
        assertTrue(engine.contains("SeparateRenditionMuxRequired"))
        assertTrue(engine.contains("EncryptedInitMap"))
        assertTrue(engine.contains("#EXT-X-STREAM-INF"))
        assertTrue(planner.contains("intent == MediaDownloadIntent.Subtitles -> MediaDownloadStrategy.YtDlp"))
        val library = text("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaExecutionLibrary.kt")
        assertTrue(library.contains("MediaDownloadStrategy.NativeHls"))
        assertTrue(library.contains("nativeHlsAudioOnly"))
        assertTrue(library.contains("nativeHlsAudioExtension"))
        assertTrue(library.contains(".mka"))
        assertFalse(finalizer.contains("requireVideo = true"))
        val processor = text("media-ffmpeg/src/main/kotlin/com/mikeyphw/xdm/android/media/ffmpeg/FfmpegPostProcessor.kt")
        assertTrue(processor.contains("requireAnyStream = true"))
    }

    @Test
    fun embeddedAdaptivePublicationIsCrashRecoverableAndAudioContainersAreCodecSafe() {
        val manager = text("app/src/main/kotlin/com/mikeyphw/xdm/android/ffmpeg/EmbeddedFfmpegMediaManager.kt")
        val writer = text("storage/src/main/kotlin/com/mikeyphw/xdm/android/storage/AndroidDestinationWriter.kt")
        val journal = text("storage/src/main/kotlin/com/mikeyphw/xdm/android/storage/PublicationSafety.kt")
        val library = text("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaExecutionLibrary.kt")
        val hlsManager = text("app/src/main/kotlin/com/mikeyphw/xdm/android/ffmpeg/NativeHlsMediaManager.kt")

        assertTrue(manager.contains("PublicationJournalCodec.read"))
        assertTrue(manager.contains("committedPublicationProven"))
        assertTrue(writer.contains("DestinationCommitInProgress"))
        assertTrue(manager.contains("withContext(NonCancellable)"))
        assertTrue(manager.contains("committedPromotion != null"))
        assertTrue(manager.contains("prepared.deleteArtifacts()"))
        assertTrue(writer.contains("suspend fun publicationCommitMatches"))
        assertTrue(writer.contains("querySize(Uri.parse(value))"))
        assertTrue(writer.contains("PublicationCommitBoundary.DestinationCommitInProgress"))
        assertTrue(writer.contains("sha256File"))
        assertTrue(writer.contains("sha256Content"))
        assertTrue(writer.contains("stagedDigest == committedDigest"))
        assertTrue(journal.contains("fun decode(text: String): PublicationCommitRecord"))
        assertTrue(journal.contains("fun read(file: File): PublicationCommitRecord"))
        assertTrue(library.contains("embeddedAudioExtension"))
        assertTrue(library.contains("return if (aacCompatible) \".m4a\" else \".mka\""))
        assertTrue(manager.contains("\"mka\" -> \"audio/x-matroska\""))
        assertTrue(hlsManager.contains("nativeHlsFinalFileName"))
        assertTrue(hlsManager.contains("val finalFileName = nativeHlsFinalFileName"))
    }

    @Test
    fun embeddedAdaptiveRecoveryHasAnExecutableRetryPathWithoutExternalizingSecrets() {
        val vm = text("app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt")
        val app = text("app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApp.kt")
        val libraryUi = text("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/library/MediaLibraryScreen.kt")
        val library = text("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaExecutionLibrary.kt")

        assertTrue(library.contains("output.ownerKind == MediaOutputOwnerKind.EmbeddedFfmpeg"))
        assertTrue(library.contains("MediaOutputState.RecoveryRequired"))
        assertTrue(library.contains("canRetry = retryable"))
        assertTrue(vm.contains("fun retryEmbeddedFfmpegOutput(output: MediaOutputRecord)"))
        assertTrue(vm.contains("MediaRequestHandoffStore.forCapture(capture.id)"))
        assertTrue(vm.contains("MediaRequestHandoffStore.forVariant(variant.id)"))
        assertTrue(vm.contains("destinationUri = output.destinationUri"))
        assertTrue(vm.contains(".copy(fileName = output.fileName)"))
        assertTrue(vm.contains("MediaOutputAdmissionMode.AdditionalGeneration"))
        assertTrue(vm.contains("MediaDownloadStrategy.FfmpegAdaptive"))
        assertTrue(vm.contains("MediaDownloadStrategy.FfmpegLive"))
        assertTrue(vm.contains("The capture no longer resolves to an embedded FFmpeg lane"))
        val retrySection = vm.substringAfter("fun retryEmbeddedFfmpegOutput(output: MediaOutputRecord)")
            .substringBefore("fun removeMediaCapture")
        assertFalse(retrySection.contains("termuxMediaPipelineManager"))
        assertFalse(retrySection.contains("enqueueFfmpegFallback"))
        assertTrue(retrySection.contains("catch (cancelled: CancellationException)"))
        assertTrue(retrySection.contains("throw cancelled"))
        assertTrue(app.contains("onRetryEmbeddedFfmpegOutput = viewModel::retryEmbeddedFfmpegOutput"))
        val retryMarker = "MediaOutputOwnerKind.EmbeddedFfmpeg -> outputs.firstOrNull { it.id == item.outputId }?.let(onRetryEmbeddedFfmpegOutput)"
        assertTrue(libraryUi.split(retryMarker).size - 1 >= 3)

        // Native-HLS RecoveryRequired rows still resume through their durable native owner rather
        // than falling into the ordinary backend retry path.
        assertTrue(vm.contains("DownloadState.Paused, DownloadState.RecoveryRequired, DownloadState.Failed -> nativeHlsMediaManager.resume(download.id)"))
    }

}
