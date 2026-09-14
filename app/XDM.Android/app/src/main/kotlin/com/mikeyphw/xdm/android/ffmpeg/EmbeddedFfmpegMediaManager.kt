package com.mikeyphw.xdm.android.ffmpeg

import com.mikeyphw.xdm.android.media.MediaDownloadIntent
import com.mikeyphw.xdm.android.media.MediaDownloadStrategy
import com.mikeyphw.xdm.android.media.MediaPostProcessingKind
import com.mikeyphw.xdm.android.media.MediaExecutionSecurityPolicy
import com.mikeyphw.xdm.android.media.MediaQueuedDownloadSpec
import com.mikeyphw.xdm.android.media.ffmpeg.EmbeddedFfmpegRuntime
import com.mikeyphw.xdm.android.media.ffmpeg.FfmpegFailureKind
import com.mikeyphw.xdm.android.media.ffmpeg.FfmpegInput
import com.mikeyphw.xdm.android.media.ffmpeg.FfmpegInputKind
import com.mikeyphw.xdm.android.media.ffmpeg.FfmpegMediaVerifier
import com.mikeyphw.xdm.android.media.ffmpeg.FfmpegOperation
import com.mikeyphw.xdm.android.media.ffmpeg.FfmpegProgressPhase
import com.mikeyphw.xdm.android.media.ffmpeg.FfmpegProgressSnapshot
import com.mikeyphw.xdm.android.media.ffmpeg.FfmpegVerificationExpectation
import com.mikeyphw.xdm.android.model.MediaCaptureRecord
import com.mikeyphw.xdm.android.model.MediaOutputAdmissionMode
import com.mikeyphw.xdm.android.model.MediaOutputOwnerKind
import com.mikeyphw.xdm.android.model.MediaOutputRecord
import com.mikeyphw.xdm.android.model.MediaOutputState
import com.mikeyphw.xdm.android.model.MediaVariantKind
import com.mikeyphw.xdm.android.persistence.DownloadRepository
import com.mikeyphw.xdm.android.storage.AndroidDestinationWriter
import com.mikeyphw.xdm.android.storage.DestinationRequest
import com.mikeyphw.xdm.android.storage.DestinationPromotionResult
import com.mikeyphw.xdm.android.storage.PublicationCommitBoundary
import com.mikeyphw.xdm.android.storage.PublicationCommitRecord
import com.mikeyphw.xdm.android.storage.PublicationJournalCodec
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class EmbeddedFfmpegEnqueueOutcome(
    val accepted: Boolean,
    val output: MediaOutputRecord,
    val existingOutput: MediaOutputRecord? = null,
    val message: String,
)

enum class EmbeddedFfmpegJobStage(val userLabel: String) {
    Queued("Queued"),
    Preparing("Preparing media"),
    Processing("Processing media"),
    Verifying("Verifying media"),
    Publishing("Publishing media"),
    Completed("Completed"),
    RecoveryRequired("Recovery required"),
    Failed("Failed"),
    Cancelled("Cancelled"),
}

data class EmbeddedFfmpegJobProgress(
    val ownerId: String,
    val stage: EmbeddedFfmpegJobStage,
    val percent: Int? = null,
    val processedDurationMs: Long? = null,
    val totalSizeBytes: Long? = null,
    val speed: String? = null,
    val detail: String = stage.userLabel,
)

/**
 * App-owned media execution for operations that genuinely need FFmpeg.
 *
 * FF02 extends FF01's live recorder into a complete adaptive/post-processing lane. Raw URLs and
 * request headers remain process-local. Durable Room state stores only redacted lineage/output
 * identity while DestinationWriter preserves stage -> verify -> atomic publication semantics.
 */
class EmbeddedFfmpegMediaManager(
    private val repository: DownloadRepository,
    private val destinationWriter: AndroidDestinationWriter,
    val runtime: EmbeddedFfmpegRuntime,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val admissionMutex = Mutex()
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val _progress = MutableStateFlow<Map<String, EmbeddedFfmpegJobProgress>>(emptyMap())
    val progress: StateFlow<Map<String, EmbeddedFfmpegJobProgress>> = _progress.asStateFlow()

    suspend fun enqueueLiveRecording(
        capture: MediaCaptureRecord,
        spec: MediaQueuedDownloadSpec,
        admissionMode: MediaOutputAdmissionMode,
    ): EmbeddedFfmpegEnqueueOutcome {
        require(spec.strategy == MediaDownloadStrategy.FfmpegLive) { "Live recording requires the FFmpeg live strategy" }
        return enqueue(capture, spec, admissionMode, ExecutionKind.Live)
    }

    suspend fun enqueueAdaptiveProcessing(
        capture: MediaCaptureRecord,
        spec: MediaQueuedDownloadSpec,
        admissionMode: MediaOutputAdmissionMode,
    ): EmbeddedFfmpegEnqueueOutcome {
        require(spec.strategy == MediaDownloadStrategy.FfmpegAdaptive) { "Adaptive processing requires the embedded FFmpeg adaptive strategy" }
        require(spec.selectedInputs.isNotEmpty()) { "Adaptive FFmpeg execution requires at least one resolved selected track" }
        return enqueue(capture, spec, admissionMode, ExecutionKind.Adaptive)
    }

    fun cancel(ownerId: String): Boolean = activeJobs.remove(ownerId)?.let { job ->
        job.cancel(CancellationException("Embedded FFmpeg media processing cancelled by user"))
        true
    } ?: false

    fun isRunning(ownerId: String): Boolean = activeJobs[ownerId]?.isActive == true
    fun progressFor(ownerId: String): EmbeddedFfmpegJobProgress? = progress.value[ownerId]

    suspend fun recoverInterruptedJobs() {
        val now = System.currentTimeMillis()
        repository.mediaOutputs.first()
            .filter {
                it.ownerKind == MediaOutputOwnerKind.EmbeddedFfmpeg &&
                    it.state in setOf(MediaOutputState.Queued, MediaOutputState.Active, MediaOutputState.RecoveryRequired)
            }
            .forEach { output ->
                val request = destinationRequest(output)
                val artifacts = destinationWriter.artifactPaths(request)
                val journal = artifacts.journalFile.takeIf(File::isFile)?.let { file ->
                    runCatching { PublicationJournalCodec.read(file) }.getOrNull()
                }
                if (journal != null && journalMatchesOutput(journal, output) && committedPublicationProven(journal)) {
                    val committedUri = requireNotNull(journal.committedUri)
                    repository.transitionMediaOutputOwned(
                        id = output.id,
                        ownerKind = output.ownerKind,
                        ownerId = output.ownerId,
                        attemptGeneration = output.attemptGeneration,
                        nextState = MediaOutputState.Completed,
                        updatedAtEpochMs = now,
                        completedArtifactUri = committedUri,
                        completedArtifactGeneration = output.attemptGeneration,
                    ) ?: return@forEach
                    updateProgress(
                        output.ownerId,
                        EmbeddedFfmpegJobStage.Completed,
                        percent = 100,
                        detail = "Recovered an already committed embedded FFmpeg artifact without reprocessing or publishing a duplicate.",
                    )
                    cleanupPublicationArtifacts(artifacts.stagingFile, artifacts.checkpointFile, artifacts.journalFile)
                } else {
                    repository.transitionMediaOutputOwned(
                        id = output.id,
                        ownerKind = output.ownerKind,
                        ownerId = output.ownerId,
                        attemptGeneration = output.attemptGeneration,
                        nextState = MediaOutputState.RecoveryRequired,
                        updatedAtEpochMs = now,
                    ) ?: return@forEach
                    updateProgress(output.ownerId, EmbeddedFfmpegJobStage.RecoveryRequired, detail = "Processing was interrupted; staged work is preserved for explicit retry/recovery.")
                }
            }
    }

    private suspend fun enqueue(
        capture: MediaCaptureRecord,
        spec: MediaQueuedDownloadSpec,
        admissionMode: MediaOutputAdmissionMode,
        kind: ExecutionKind,
    ): EmbeddedFfmpegEnqueueOutcome = admissionMutex.withLock {
        val existing = repository.mediaOutputsForCapture(capture.id).firstOrNull { it.state != MediaOutputState.Hidden }
        if (admissionMode == MediaOutputAdmissionMode.Primary && existing != null) {
            return@withLock EmbeddedFfmpegEnqueueOutcome(false, existing, existing, "This media capture already owns an output generation.")
        }
        val capability = runtime.capabilities()
        require(capability.ready) { capability.summary }

        val now = System.currentTimeMillis()
        val generation = (repository.mediaOutputsForCapture(capture.id).maxOfOrNull { it.attemptGeneration } ?: 0L) + 1L
        val ownerId = UUID.randomUUID().toString()
        val output = MediaOutputRecord(
            id = "EmbeddedFfmpeg:$ownerId:$generation",
            captureId = capture.id,
            ownerKind = MediaOutputOwnerKind.EmbeddedFfmpeg,
            ownerId = ownerId,
            downloadId = null,
            attemptGeneration = generation,
            destinationUri = spec.destinationUri,
            fileName = spec.fileName,
            mimeType = outputMimeType(spec.fileName, capture.mimeType),
            selectedTrackIds = spec.selectedTrackIds,
            state = MediaOutputState.Queued,
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
        )
        check(repository.saveMediaOutput(output)) { "Embedded FFmpeg output owner changed before queue commit" }
        updateProgress(ownerId, EmbeddedFfmpegJobStage.Queued, percent = 0, detail = spec.postProcessing.userLabel)
        val job = scope.launch(start = CoroutineStart.LAZY) { execute(output, capture, spec, kind) }
        activeJobs[ownerId] = job
        job.invokeOnCompletion { activeJobs.remove(ownerId, job) }
        job.start()
        EmbeddedFfmpegEnqueueOutcome(
            accepted = true,
            output = output,
            message = when (kind) {
                ExecutionKind.Live -> "Embedded FFmpeg recording queued without Termux."
                ExecutionKind.Adaptive -> "Selected adaptive tracks queued for embedded FFmpeg processing and FFprobe verification."
            },
        )
    }

    private suspend fun execute(
        seed: MediaOutputRecord,
        capture: MediaCaptureRecord,
        spec: MediaQueuedDownloadSpec,
        kind: ExecutionKind,
    ) {
        val request = destinationRequest(seed)
        updateProgress(seed.ownerId, EmbeddedFfmpegJobStage.Preparing, percent = 0)
        val prepared = runCatching { destinationWriter.prepare(request) }.getOrElse { error ->
            fail(seed, MediaOutputState.Failed, "Destination preparation failed: ${safeMessage(error)}")
            return
        }
        if (repository.transitionMediaOutputOwned(
                id = seed.id,
                ownerKind = seed.ownerKind,
                ownerId = seed.ownerId,
                attemptGeneration = seed.attemptGeneration,
                nextState = MediaOutputState.Active,
            ) == null
        ) {
            updateProgress(seed.ownerId, EmbeddedFfmpegJobStage.Cancelled, detail = "Output ownership changed before embedded FFmpeg could start.")
            return
        }
        var committedPromotion: DestinationPromotionResult? = null
        try {
            prepared.artifacts.stagingFile.parentFile?.mkdirs()
            MediaExecutionSecurityPolicy.requireEmbeddedExecutable(
                urls = buildList {
                    add(spec.sourceUrl)
                    spec.selectedInputs.forEach { add(it.url) }
                },
                headers = spec.requestHeaders + spec.selectedInputs.flatMap { it.headers.entries }.associate { it.key to it.value },
            )
            val operation = when (kind) {
                ExecutionKind.Live -> FfmpegOperation.RecordStream(
                    inputUrl = spec.sourceUrl,
                    outputFile = prepared.artifacts.stagingFile,
                    headers = spec.requestHeaders,
                    durationMs = capture.durationMs,
                    overwrite = true,
                )
                ExecutionKind.Adaptive -> adaptiveOperation(capture, spec, prepared.artifacts.stagingFile)
            }
            val result = runtime.execute(operation) { snapshot -> updateFromRuntime(seed.ownerId, snapshot) }
            if (!result.success) {
                val state = if (prepared.artifacts.stagingFile.length() > 0L) MediaOutputState.RecoveryRequired else MediaOutputState.Failed
                if (state == MediaOutputState.Failed) runCatching { prepared.deleteArtifacts() }
                fail(seed, state, result.redactedSummary)
                return
            }
            updateProgress(seed.ownerId, EmbeddedFfmpegJobStage.Verifying, percent = 96, detail = "FFprobe is validating the staged media.")
            val probe = runtime.probe(prepared.artifacts.stagingFile.absolutePath).getOrElse { error ->
                fail(seed, MediaOutputState.RecoveryRequired, "FFprobe verification failed: ${safeMessage(error)}")
                return
            }
            val verification = FfmpegMediaVerifier.verify(
                file = prepared.artifacts.stagingFile,
                probe = probe,
                expectation = verificationExpectation(capture, spec),
            )
            if (!verification.valid) {
                fail(seed, MediaOutputState.RecoveryRequired, "FFprobe rejected staged media: ${verification.message}")
                return
            }
            updateProgress(seed.ownerId, EmbeddedFfmpegJobStage.Publishing, percent = 99, detail = "Publishing verified media atomically.")
            val promotion = runCatching { prepared.promote() }.getOrElse { error ->
                fail(seed, MediaOutputState.RecoveryRequired, "Final publication failed: ${safeMessage(error)}")
                return
            }
            committedPromotion = promotion
            // Promotion is the point of no return. Once DestinationWriter has committed the final
            // artifact, cancellation/process-shutdown must not downgrade it to Cancelled or delete
            // the crash-recovery journal before Room completion metadata is durable.
            withContext(NonCancellable) {
                val now = System.currentTimeMillis()
                val completed = repository.transitionMediaOutputOwned(
                    id = seed.id,
                    ownerKind = seed.ownerKind,
                    ownerId = seed.ownerId,
                    attemptGeneration = seed.attemptGeneration,
                    nextState = MediaOutputState.Completed,
                    updatedAtEpochMs = now,
                    completedArtifactUri = promotion.committedUri,
                    completedArtifactGeneration = seed.attemptGeneration,
                )
                if (completed != null) {
                    updateProgress(seed.ownerId, EmbeddedFfmpegJobStage.Completed, percent = 100, detail = verification.message)
                    runCatching { prepared.deleteArtifacts() }
                } else {
                    updateProgress(seed.ownerId, EmbeddedFfmpegJobStage.RecoveryRequired, detail = "Publication committed but output ownership changed before metadata reconciliation; startup recovery will retain the journal.")
                }
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                if (committedPromotion != null) {
                    fail(seed, MediaOutputState.RecoveryRequired, "Publication committed while cancellation raced metadata reconciliation; startup recovery will adopt the committed artifact.")
                } else {
                    runCatching { prepared.deleteArtifacts() }
                    repository.transitionMediaOutputOwned(
                        id = seed.id,
                        ownerKind = seed.ownerKind,
                        ownerId = seed.ownerId,
                        attemptGeneration = seed.attemptGeneration,
                        nextState = MediaOutputState.Cancelled,
                    )
                    updateProgress(seed.ownerId, EmbeddedFfmpegJobStage.Cancelled, detail = "Processing cancelled; staged output removed.")
                }
            }
            throw cancelled
        } catch (error: Throwable) {
            val failureKind = if (error is SecurityException) FfmpegFailureKind.PermissionDenied else FfmpegFailureKind.ProcessFailed
            val state = when {
                committedPromotion != null -> MediaOutputState.RecoveryRequired
                prepared.artifacts.stagingFile.length() > 0L -> MediaOutputState.RecoveryRequired
                else -> MediaOutputState.Failed
            }
            // After destination commit the local publication journal is the authoritative recovery
            // evidence. Never delete it merely because Room metadata reconciliation failed.
            if (state == MediaOutputState.Failed) runCatching { prepared.deleteArtifacts() }
            fail(seed, state, "$failureKind: ${safeMessage(error)}")
        }
    }

    private fun adaptiveOperation(capture: MediaCaptureRecord, spec: MediaQueuedDownloadSpec, outputFile: java.io.File): FfmpegOperation {
        val inputs = spec.selectedInputs.map { selected ->
            FfmpegInput(
                source = selected.url,
                kind = when (selected.kind) {
                    MediaVariantKind.Video, MediaVariantKind.Primary -> FfmpegInputKind.Video
                    MediaVariantKind.Audio -> FfmpegInputKind.Audio
                    MediaVariantKind.Subtitle -> FfmpegInputKind.Subtitle
                    MediaVariantKind.Thumbnail -> FfmpegInputKind.Generic
                },
                headers = selected.headers,
            )
        }
        return if (inputs.size == 1 && spec.postProcessing.kind == MediaPostProcessingKind.AudioExtract) {
            FfmpegOperation.ExtractRemoteAudio(
                input = inputs.single(),
                outputFile = outputFile,
                expectedDurationMs = capture.durationMs,
                overwrite = true,
            )
        } else if (inputs.size == 1 && spec.postProcessing.kind == MediaPostProcessingKind.AdaptiveFinalize) {
            FfmpegOperation.FinalizeAdaptive(
                input = inputs.single(),
                outputFile = outputFile,
                expectedDurationMs = capture.durationMs,
                overwrite = true,
            )
        } else {
            FfmpegOperation.MuxRemoteTracks(
                inputs = inputs,
                outputFile = outputFile,
                expectedDurationMs = capture.durationMs,
                overwrite = true,
            )
        }
    }

    private fun verificationExpectation(capture: MediaCaptureRecord, spec: MediaQueuedDownloadSpec): FfmpegVerificationExpectation {
        val selectedKinds = spec.selectedInputs.map { it.kind }.toSet()
        val requireVideo = spec.intent in setOf(MediaDownloadIntent.BestVideo, MediaDownloadIntent.VideoOnly) &&
            selectedKinds.any { it == MediaVariantKind.Video || it == MediaVariantKind.Primary }
        val requireAudio = spec.intent == MediaDownloadIntent.AudioOnly ||
            (spec.intent == MediaDownloadIntent.BestVideo && MediaVariantKind.Audio in selectedKinds)
        val requireSubtitle = spec.postProcessing.kind == MediaPostProcessingKind.SubtitleMux && MediaVariantKind.Subtitle in selectedKinds
        return FfmpegVerificationExpectation(
            requireVideo = requireVideo,
            requireAudio = requireAudio,
            requireSubtitle = requireSubtitle,
            expectedDurationMs = capture.durationMs,
            minimumBytes = 1_024L,
        )
    }

    private suspend fun fail(seed: MediaOutputRecord, state: MediaOutputState, message: String) {
        repository.transitionMediaOutputOwned(
            id = seed.id,
            ownerKind = seed.ownerKind,
            ownerId = seed.ownerId,
            attemptGeneration = seed.attemptGeneration,
            nextState = state,
        )
        updateProgress(
            seed.ownerId,
            if (state == MediaOutputState.RecoveryRequired) EmbeddedFfmpegJobStage.RecoveryRequired else EmbeddedFfmpegJobStage.Failed,
            detail = message.take(240),
        )
    }

    private fun updateFromRuntime(ownerId: String, snapshot: FfmpegProgressSnapshot) {
        val stage = when (snapshot.phase) {
            FfmpegProgressPhase.Preparing -> EmbeddedFfmpegJobStage.Preparing
            FfmpegProgressPhase.Processing -> EmbeddedFfmpegJobStage.Processing
            FfmpegProgressPhase.Finalizing -> EmbeddedFfmpegJobStage.Processing
            FfmpegProgressPhase.Completed -> EmbeddedFfmpegJobStage.Verifying
        }
        updateProgress(
            ownerId = ownerId,
            stage = stage,
            percent = snapshot.percent?.coerceAtMost(95),
            processedDurationMs = snapshot.outTimeMs,
            totalSizeBytes = snapshot.totalSizeBytes,
            speed = snapshot.speed,
            detail = snapshot.userLabel,
        )
    }

    private fun updateProgress(
        ownerId: String,
        stage: EmbeddedFfmpegJobStage,
        percent: Int? = null,
        processedDurationMs: Long? = null,
        totalSizeBytes: Long? = null,
        speed: String? = null,
        detail: String = stage.userLabel,
    ) {
        val snapshot = EmbeddedFfmpegJobProgress(
            ownerId = ownerId,
            stage = stage,
            percent = percent,
            processedDurationMs = processedDurationMs,
            totalSizeBytes = totalSizeBytes,
            speed = speed,
            detail = detail,
        )
        synchronized(_progress) {
            _progress.value = _progress.value + (ownerId to snapshot)
        }
        if (stage in terminalProgressStages) {
            scope.launch {
                delay(30_000L)
                synchronized(_progress) {
                    if (_progress.value[ownerId] == snapshot) _progress.value = _progress.value - ownerId
                }
            }
        }
    }

    private fun destinationRequest(output: MediaOutputRecord): DestinationRequest = DestinationRequest(
        downloadId = output.ownerId,
        destinationUri = output.destinationUri,
        fileName = output.fileName,
        mimeType = output.mimeType,
        stagingSuffix = stagingSuffix(output.fileName),
        attemptGeneration = output.attemptGeneration,
    )

    private fun journalMatchesOutput(record: PublicationCommitRecord, output: MediaOutputRecord): Boolean =
        record.generation.downloadId == output.ownerId &&
            record.generation.attemptGeneration == output.attemptGeneration &&
            record.destinationSpec == output.destinationUri

    private suspend fun committedPublicationProven(record: PublicationCommitRecord): Boolean =
        destinationWriter.publicationCommitMatches(record)

    private fun cleanupPublicationArtifacts(staging: File, checkpoint: File, journal: File) {
        runCatching { staging.delete() }
        runCatching { checkpoint.delete() }
        runCatching { journal.delete() }
        runCatching { journal.parentFile?.takeIf { it.isDirectory && it.listFiles().isNullOrEmpty() }?.delete() }
    }

    private fun outputMimeType(fileName: String, capturedMimeType: String?): String? = when (fileName.substringAfterLast('.', "").lowercase()) {
        "mkv" -> if (capturedMimeType?.contains("audio", ignoreCase = true) == true) "audio/x-matroska" else "video/x-matroska"
        "mp4", "m4v", "mov" -> "video/mp4"
        "m4a" -> "audio/mp4"
        "mka" -> "audio/x-matroska"
        "webm" -> if (capturedMimeType?.contains("audio", ignoreCase = true) == true) "audio/webm" else "video/webm"
        else -> capturedMimeType
    }

    private fun stagingSuffix(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "").lowercase().takeIf { it.matches(Regex("[a-z0-9]{1,8}")) }
        return if (extension == null) ".xdm.part.mkv" else ".xdm.part.$extension"
    }

    private fun safeMessage(error: Throwable): String = (error.message ?: error::class.java.simpleName)
        .replace(Regex("(?i)(cookie|authorization|token|signature)=?[^\\s&;]*"), "$1=<redacted>")
        .take(240)

    private val terminalProgressStages = setOf(
        EmbeddedFfmpegJobStage.Completed,
        EmbeddedFfmpegJobStage.RecoveryRequired,
        EmbeddedFfmpegJobStage.Failed,
        EmbeddedFfmpegJobStage.Cancelled,
    )

    private enum class ExecutionKind { Live, Adaptive }
}
