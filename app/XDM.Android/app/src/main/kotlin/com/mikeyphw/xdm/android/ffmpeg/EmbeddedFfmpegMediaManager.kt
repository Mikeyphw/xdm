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
import com.mikeyphw.xdm.android.media.ffmpeg.FfmpegInputFormat
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
import com.mikeyphw.xdm.android.model.DebugArea
import com.mikeyphw.xdm.android.model.DebugEventRecorder
import com.mikeyphw.xdm.android.model.DebugSeverity
import com.mikeyphw.xdm.android.model.ExternalUrlPolicy
import com.mikeyphw.xdm.android.model.NoOpDebugEventRecorder
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
    private val debugRecorder: DebugEventRecorder = NoOpDebugEventRecorder,
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
        val outputsForCapture = repository.mediaOutputsForCapture(capture.id)
        val blockingExisting = outputsForCapture.firstOrNull { it.blocksPrimaryAdmission() }
        if (admissionMode == MediaOutputAdmissionMode.Primary && blockingExisting != null) {
            recordEvent(
                severity = DebugSeverity.Info,
                action = "embedded-ffmpeg-admission",
                result = "existing-active-generation",
                seed = blockingExisting,
                details = mapOf("state" to blockingExisting.state.name, "reason" to "primary-admission-blocked"),
            )
            return@withLock EmbeddedFfmpegEnqueueOutcome(false, blockingExisting, blockingExisting, "This media capture already owns an active or completed output generation.")
        }
        val capability = runtime.capabilities()
        require(capability.ready) { capability.summary }

        val now = System.currentTimeMillis()
        val generation = (outputsForCapture.maxOfOrNull { it.attemptGeneration } ?: 0L) + 1L
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
        recordEvent(
            severity = DebugSeverity.Info,
            action = "embedded-ffmpeg-queued",
            result = "committed",
            seed = output,
            details = mapOf(
                "executionKind" to kind.name,
                "processingKind" to spec.postProcessing.kind.name,
                "selectedInputCount" to spec.selectedInputs.size.toString(),
                "sourceUrl" to safeUrl(spec.sourceUrl),
            ),
        )
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


    private fun MediaOutputRecord.blocksPrimaryAdmission(): Boolean = state in setOf(
        MediaOutputState.Queued,
        MediaOutputState.Active,
        MediaOutputState.Completed,
    )

    private suspend fun execute(
        seed: MediaOutputRecord,
        capture: MediaCaptureRecord,
        spec: MediaQueuedDownloadSpec,
        kind: ExecutionKind,
    ) {
        val request = destinationRequest(seed)
        recordEvent(
            severity = DebugSeverity.Trace,
            action = "embedded-ffmpeg-prepare",
            result = "started",
            seed = seed,
            details = mapOf("destinationKind" to request::class.java.simpleName, "fileName" to seed.fileName),
        )
        updateProgress(seed.ownerId, EmbeddedFfmpegJobStage.Preparing, percent = 0)
        val prepared = runCatching { destinationWriter.prepare(request) }.getOrElse { error ->
            recordEvent(DebugSeverity.Error, "embedded-ffmpeg-prepare", "failed", seed, mapOf("error" to safeMessage(error)))
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
            val executionUrls = buildList {
                add(spec.sourceUrl)
                spec.selectedInputs.forEach { add(it.url) }
            }
            val executionHeaders = spec.requestHeaders + spec.selectedInputs.flatMap { it.headers.entries }.associate { it.key to it.value }
            recordEvent(
                severity = DebugSeverity.Trace,
                action = "embedded-ffmpeg-request-context",
                result = "prepared",
                seed = seed,
                details = mapOf(
                    "inputUrls" to executionUrls.joinToString(" ; ") { safeUrl(it) },
                    "requestHeaderNames" to executionHeaders.keys.sortedBy { it.lowercase() }.joinToString(","),
                    "selectedKinds" to spec.selectedInputs.joinToString(",") { it.kind.name },
                    "inputFormats" to spec.selectedInputs.joinToString(",") { selected ->
                        ffmpegInputFormat(selected.mimeType, selected.url)?.argument ?: "auto"
                    },
                ),
            )
            MediaExecutionSecurityPolicy.requireEmbeddedExecutable(urls = executionUrls, headers = executionHeaders)
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
            recordEvent(
                severity = DebugSeverity.Trace,
                action = "embedded-ffmpeg-execute",
                result = "started",
                seed = seed,
                details = mapOf(
                    "operation" to operation::class.java.simpleName,
                    "stagingBytes" to prepared.artifacts.stagingFile.length().toString(),
                ),
            )
            val result = runtime.execute(operation) { snapshot -> updateFromRuntime(seed.ownerId, snapshot) }
            recordEvent(
                severity = if (result.success) DebugSeverity.Info else DebugSeverity.Error,
                action = "embedded-ffmpeg-execute",
                result = if (result.success) "completed" else "failed",
                seed = seed,
                details = mapOf(
                    "exitCode" to result.exitCode.toString(),
                    "failureKind" to result.failureKind.name,
                    "durationMs" to result.durationMs.toString(),
                    "message" to result.message,
                    "stderrTail" to result.redactedDiagnosticTail,
                    "stagingBytes" to prepared.artifacts.stagingFile.length().toString(),
                    "lastProgress" to (result.lastProgress?.userLabel ?: "none"),
                ),
            )
            if (!result.success) {
                val state = if (prepared.artifacts.stagingFile.length() > 0L) MediaOutputState.RecoveryRequired else MediaOutputState.Failed
                if (state == MediaOutputState.Failed) runCatching { prepared.deleteArtifacts() }
                fail(seed, state, result.redactedSummary)
                return
            }
            updateProgress(seed.ownerId, EmbeddedFfmpegJobStage.Verifying, percent = 96, detail = "FFprobe is validating the staged media.")
            recordEvent(DebugSeverity.Trace, "embedded-ffprobe-verify", "started", seed, mapOf("stagingBytes" to prepared.artifacts.stagingFile.length().toString()))
            val probe = runtime.probe(prepared.artifacts.stagingFile.absolutePath).getOrElse { error ->
                recordEvent(DebugSeverity.Error, "embedded-ffprobe-verify", "failed", seed, mapOf("error" to safeMessage(error)))
                fail(seed, MediaOutputState.RecoveryRequired, "FFprobe verification failed: ${safeMessage(error)}")
                return
            }
            val verification = FfmpegMediaVerifier.verify(
                file = prepared.artifacts.stagingFile,
                probe = probe,
                expectation = verificationExpectation(capture, spec),
            )
            if (!verification.valid) {
                recordEvent(DebugSeverity.Error, "embedded-ffprobe-verify", "rejected", seed, mapOf("verification" to verification.message))
                fail(seed, MediaOutputState.RecoveryRequired, "FFprobe rejected staged media: ${verification.message}")
                return
            }
            recordEvent(DebugSeverity.Info, "embedded-ffprobe-verify", "passed", seed, mapOf("verification" to verification.message))
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
                    recordEvent(DebugSeverity.Info, "embedded-ffmpeg-publish", "completed", seed, mapOf("verification" to verification.message))
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
            recordEvent(DebugSeverity.Error, "embedded-ffmpeg-execute", "exception", seed, mapOf("error" to safeMessage(error), "exceptionType" to error::class.java.simpleName))
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
                formatHint = ffmpegInputFormat(selected.mimeType, selected.url),
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


    private fun ffmpegInputFormat(mimeType: String?, url: String): FfmpegInputFormat? {
        val normalizedMime = mimeType?.substringBefore(';')?.trim()?.lowercase()
        return when {
            normalizedMime == "application/vnd.apple.mpegurl" || normalizedMime == "application/x-mpegurl" -> FfmpegInputFormat.Hls
            url.substringBefore('?').substringBefore('#').lowercase().endsWith(".m3u8") -> FfmpegInputFormat.Hls
            else -> null
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
        recordEvent(
            severity = if (state == MediaOutputState.RecoveryRequired) DebugSeverity.Warning else DebugSeverity.Error,
            action = "embedded-ffmpeg-terminal",
            result = state.name,
            seed = seed,
            details = mapOf("detail" to message),
        )
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

    private fun recordEvent(
        severity: DebugSeverity,
        action: String,
        result: String,
        seed: MediaOutputRecord,
        details: Map<String, String> = emptyMap(),
    ) {
        debugRecorder.record(
            area = DebugArea.MediaResolver,
            severity = severity,
            action = action,
            result = result,
            safeDetails = mapOf(
                "ownerId" to seed.ownerId,
                "captureId" to seed.captureId,
                "attemptGeneration" to seed.attemptGeneration.toString(),
            ) + details,
            operationId = "embedded-ffmpeg:${seed.ownerId}",
            parentOperationId = seed.captureId,
        )
    }

    private fun safeUrl(value: String): String = ExternalUrlPolicy.persistableUrl(value) ?: value.substringBefore('?').take(240)

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
