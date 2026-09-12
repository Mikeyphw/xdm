package com.mikeyphw.xdm.android.ffmpeg

import com.mikeyphw.xdm.android.media.MediaQueuedDownloadSpec
import com.mikeyphw.xdm.android.media.ffmpeg.EmbeddedFfmpegRuntime
import com.mikeyphw.xdm.android.media.ffmpeg.FfmpegFailureKind
import com.mikeyphw.xdm.android.media.ffmpeg.FfmpegOperation
import com.mikeyphw.xdm.android.model.MediaCaptureRecord
import com.mikeyphw.xdm.android.model.MediaOutputAdmissionMode
import com.mikeyphw.xdm.android.model.MediaOutputOwnerKind
import com.mikeyphw.xdm.android.model.MediaOutputRecord
import com.mikeyphw.xdm.android.model.MediaOutputState
import com.mikeyphw.xdm.android.persistence.DownloadRepository
import com.mikeyphw.xdm.android.storage.AndroidDestinationWriter
import com.mikeyphw.xdm.android.storage.DestinationRequest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class EmbeddedFfmpegEnqueueOutcome(
    val accepted: Boolean,
    val output: MediaOutputRecord,
    val existingOutput: MediaOutputRecord? = null,
    val message: String,
)

/**
 * App-owned media execution for jobs that genuinely need FFmpeg rather than Termux/yt-dlp.
 *
 * Raw URLs and request headers live only in the launched coroutine closure. Durable Room state stores
 * redacted lineage and output identity, never Cookie/Authorization values or credential-bearing URLs.
 * Interrupted process-local jobs are marked RecoveryRequired on the next app startup instead of being
 * silently reported as completed.
 */
class EmbeddedFfmpegMediaManager(
    private val repository: DownloadRepository,
    private val destinationWriter: AndroidDestinationWriter,
    val runtime: EmbeddedFfmpegRuntime,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val admissionMutex = Mutex()
    private val activeJobs = ConcurrentHashMap<String, Job>()

    suspend fun enqueueLiveRecording(
        capture: MediaCaptureRecord,
        spec: MediaQueuedDownloadSpec,
        admissionMode: MediaOutputAdmissionMode,
    ): EmbeddedFfmpegEnqueueOutcome = admissionMutex.withLock {
        require(spec.strategy.name == "FfmpegLive") { "Embedded FFmpeg manager only accepts FFmpeg execution plans" }
        val existing = repository.mediaOutputsForCapture(capture.id)
            .firstOrNull { it.state != MediaOutputState.Hidden }
        if (admissionMode == MediaOutputAdmissionMode.Primary && existing != null) {
            return@withLock EmbeddedFfmpegEnqueueOutcome(
                accepted = false,
                output = existing,
                existingOutput = existing,
                message = "This media capture already owns an output generation.",
            )
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
        repository.saveMediaOutput(output)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            executeRecording(output, spec)
        }
        activeJobs[ownerId] = job
        job.invokeOnCompletion { activeJobs.remove(ownerId, job) }
        job.start()
        EmbeddedFfmpegEnqueueOutcome(
            accepted = true,
            output = output,
            message = "Embedded FFmpeg recording queued without Termux.",
        )
    }

    fun cancel(ownerId: String): Boolean = activeJobs.remove(ownerId)?.let { job ->
        job.cancel(CancellationException("Embedded FFmpeg recording cancelled by user"))
        true
    } ?: false

    fun isRunning(ownerId: String): Boolean = activeJobs[ownerId]?.isActive == true

    suspend fun recoverInterruptedJobs() {
        val now = System.currentTimeMillis()
        repository.mediaOutputs.first()
            .filter { it.ownerKind == MediaOutputOwnerKind.EmbeddedFfmpeg && it.state in setOf(MediaOutputState.Queued, MediaOutputState.Active) }
            .forEach { output ->
                repository.saveMediaOutput(
                    output.copy(
                        state = MediaOutputState.RecoveryRequired,
                        updatedAtEpochMs = now,
                    ),
                )
            }
    }

    private suspend fun executeRecording(seed: MediaOutputRecord, spec: MediaQueuedDownloadSpec) {
        val request = DestinationRequest(
            downloadId = seed.ownerId,
            destinationUri = seed.destinationUri,
            fileName = seed.fileName,
            mimeType = seed.mimeType,
            stagingSuffix = stagingSuffix(seed.fileName),
            attemptGeneration = seed.attemptGeneration,
        )
        val prepared = runCatching { destinationWriter.prepare(request) }.getOrElse { error ->
            fail(seed, MediaOutputState.Failed, "Destination preparation failed: ${safeMessage(error)}")
            return
        }
        repository.saveMediaOutput(seed.copy(state = MediaOutputState.Active, updatedAtEpochMs = System.currentTimeMillis()))
        try {
            prepared.artifacts.stagingFile.parentFile?.mkdirs()
            val result = runtime.execute(
                FfmpegOperation.RecordStream(
                    inputUrl = spec.sourceUrl,
                    outputFile = prepared.artifacts.stagingFile,
                    headers = spec.requestHeaders,
                    overwrite = true,
                ),
            )
            if (!result.success) {
                val state = if (prepared.artifacts.stagingFile.length() > 0L) MediaOutputState.RecoveryRequired else MediaOutputState.Failed
                fail(seed, state, result.redactedSummary)
                return
            }
            if (!prepared.artifacts.stagingFile.isFile || prepared.artifacts.stagingFile.length() <= 0L) {
                fail(seed, MediaOutputState.Failed, "Embedded FFmpeg exited successfully without a usable output artifact.")
                return
            }
            val probe = runtime.probe(prepared.artifacts.stagingFile.absolutePath)
            if (probe.isFailure || probe.getOrNull()?.streams.isNullOrEmpty()) {
                fail(seed, MediaOutputState.RecoveryRequired, "FFprobe could not verify media streams in the staged recording.")
                return
            }
            val promotion = runCatching { prepared.promote() }.getOrElse { error ->
                fail(seed, MediaOutputState.RecoveryRequired, "Final publication failed: ${safeMessage(error)}")
                return
            }
            val now = System.currentTimeMillis()
            repository.saveMediaOutput(
                seed.copy(
                    state = MediaOutputState.Completed,
                    completedArtifactUri = promotion.committedUri,
                    completedArtifactGeneration = seed.attemptGeneration,
                    updatedAtEpochMs = now,
                ),
            )
        } catch (cancelled: CancellationException) {
            runCatching { prepared.deleteArtifacts() }
            repository.saveMediaOutput(seed.copy(state = MediaOutputState.Cancelled, updatedAtEpochMs = System.currentTimeMillis()))
            throw cancelled
        } catch (error: Throwable) {
            val failureKind = if (error is SecurityException) FfmpegFailureKind.PermissionDenied else FfmpegFailureKind.ProcessFailed
            val state = if (prepared.artifacts.stagingFile.length() > 0L) MediaOutputState.RecoveryRequired else MediaOutputState.Failed
            fail(seed, state, "$failureKind: ${safeMessage(error)}")
        }
    }

    private suspend fun fail(seed: MediaOutputRecord, state: MediaOutputState, message: String) {
        repository.saveMediaOutput(seed.copy(state = state, updatedAtEpochMs = System.currentTimeMillis()))
        // Failure text intentionally stays out of MediaOutputRecord because it may originate in remote media.
        // Debug/workbench reporting uses the runtime's redacted summaries instead.
        @Suppress("UNUSED_VARIABLE") val redactedFailure = message.take(240)
    }


    private fun outputMimeType(fileName: String, capturedMimeType: String?): String? = when (fileName.substringAfterLast('.', "").lowercase()) {
        "mkv" -> if (capturedMimeType?.contains("audio", ignoreCase = true) == true) "audio/x-matroska" else "video/x-matroska"
        "mp4", "m4v", "mov" -> "video/mp4"
        "m4a" -> "audio/mp4"
        else -> capturedMimeType
    }

    private fun stagingSuffix(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "").lowercase().takeIf { it.matches(Regex("[a-z0-9]{1,8}")) }
        return if (extension == null) ".xdm.part.mkv" else ".xdm.part.$extension"
    }

    private fun safeMessage(error: Throwable): String = (error.message ?: error::class.java.simpleName)
        .replace(Regex("(?i)(cookie|authorization|token|signature)=?[^\\s&;]*"), "$1=<redacted>")
        .take(240)
}
