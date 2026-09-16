package com.mikeyphw.xdm.android.ffmpeg

import android.content.Context
import com.mikeyphw.xdm.android.media.LogicalMediaGraphEngine
import com.mikeyphw.xdm.android.media.MediaQueuedDownloadSpec
import com.mikeyphw.xdm.android.media.MediaTrackSelection
import com.mikeyphw.xdm.android.media.NativeHlsExecutionEngine
import com.mikeyphw.xdm.android.media.NativeHlsExecutionStage
import com.mikeyphw.xdm.android.media.NativeHlsFinalizationState
import com.mikeyphw.xdm.android.media.NativeHlsManifestPlan
import com.mikeyphw.xdm.android.media.NativeHlsPart
import com.mikeyphw.xdm.android.media.NativeHlsPartState
import com.mikeyphw.xdm.android.media.NativeHlsSupportStatus
import com.mikeyphw.xdm.android.media.ffmpeg.EmbeddedFfmpegRuntime
import com.mikeyphw.xdm.android.model.BackendSelectionReason
import com.mikeyphw.xdm.android.model.BackendType
import com.mikeyphw.xdm.android.model.ChecksumAlgorithm
import com.mikeyphw.xdm.android.model.Download
import com.mikeyphw.xdm.android.model.DownloadState
import com.mikeyphw.xdm.android.model.ExternalUrlPolicy
import com.mikeyphw.xdm.android.model.FilenameConflictPolicy
import com.mikeyphw.xdm.android.model.FinalizationJournal
import com.mikeyphw.xdm.android.model.FinalizationJournalStage
import com.mikeyphw.xdm.android.model.MediaCaptureRecord
import com.mikeyphw.xdm.android.model.MediaOutputAdmissionMode
import com.mikeyphw.xdm.android.model.MediaOutputState
import com.mikeyphw.xdm.android.model.MediaTransferShape
import com.mikeyphw.xdm.android.model.MediaVariant
import com.mikeyphw.xdm.android.model.MediaVariantKind
import com.mikeyphw.xdm.android.persistence.AppDatabase
import com.mikeyphw.xdm.android.persistence.DownloadRepository
import com.mikeyphw.xdm.android.persistence.MediaDownloadAdmissionResult
import com.mikeyphw.xdm.android.persistence.NativeHlsJobEntity
import com.mikeyphw.xdm.android.persistence.NativeHlsPartEntity
import com.mikeyphw.xdm.android.scheduler.AndroidTransferRequestSecurityGuard
import com.mikeyphw.xdm.android.scheduler.MediaRequestHandoff
import com.mikeyphw.xdm.android.scheduler.TransferRequestSecurityException
import com.mikeyphw.xdm.android.scheduler.TransferSecurityFailureKind
import com.mikeyphw.xdm.android.scheduler.ValidatedTransferNetworkTarget
import com.mikeyphw.xdm.android.scheduler.MediaRequestHandoffStore
import com.mikeyphw.xdm.android.storage.AndroidDestinationWriter
import com.mikeyphw.xdm.android.storage.DestinationRequest
import com.mikeyphw.xdm.android.transfer.DownloadRequest
import com.mikeyphw.xdm.android.transfer.DownloadRequestApprovalScope
import com.mikeyphw.xdm.android.transfer.DownloadRequestKind
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Production owner for FF02's supported VOD native-HLS lane.
 *
 * Playlist policy/retries/AES-128/part durability stay Android-owned. Only complete local parts are
 * handed to [NativeHlsFfmpegFinalizer], which performs stream-copy finalization and FFprobe
 * verification before [AndroidDestinationWriter] publishes the committed artifact. Exact signed
 * URLs and credential headers are recovered from the encrypted handoff store and are never written
 * to the native-HLS Room ledger.
 */
class NativeHlsMediaManager(
    context: Context,
    private val database: AppDatabase,
    private val repository: DownloadRepository,
    private val destinationWriter: AndroidDestinationWriter,
    runtime: EmbeddedFfmpegRuntime,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    data class EnqueueOutcome(
        val accepted: Boolean,
        val downloadId: String,
        val message: String,
    )

    private enum class RequestedControl { Pause, Cancel }
    private enum class ControlCommand { Pause, Resume, Cancel }
    private data class ControlIntent(val sequence: Long, val command: ControlCommand)
    private data class RunningHttpResponse(val call: Call, val response: Response)

    private val appContext = context.applicationContext
    private val dao = database.nativeHlsDao()
    private val engine = NativeHlsExecutionEngine()
    private val finalizer = NativeHlsFfmpegFinalizer(runtime, File(appContext.cacheDir, "native-hls-ffmpeg").apply(File::mkdirs))
    private val securityGuard = AndroidTransferRequestSecurityGuard(appContext)
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
    private val running = ConcurrentHashMap<String, Job>()
    private val requestedControl = ConcurrentHashMap<String, RequestedControl>()
    private val controlSequence = AtomicLong(0L)
    private val latestControl = ConcurrentHashMap<String, ControlIntent>()
    private val controlMutexes = ConcurrentHashMap<String, Mutex>()

    suspend fun enqueue(
        capture: MediaCaptureRecord,
        variants: List<MediaVariant>,
        selection: MediaTrackSelection,
        spec: MediaQueuedDownloadSpec,
        conflictPolicy: FilenameConflictPolicy,
        admissionMode: MediaOutputAdmissionMode,
        captureHandoff: MediaRequestHandoff?,
    ): EnqueueOutcome {
        val exactManifestUrl = captureHandoff?.exactUrl ?: capture.sourceUrl
        val headers = captureHandoff?.headers.orEmpty()
        val playlist = fetchText(exactManifestUrl, headers, captureHandoff)
        val executionCapture = capture.copy(sourceUrl = exactManifestUrl)
        val plan = engine.negotiate(executionCapture, variants, playlist, headers, selection)
        require(plan.supportStatus == NativeHlsSupportStatus.Supported && plan.nativeExecutable) {
            "Native HLS cannot execute this playlist: ${plan.fallbackReason}"
        }
        require(!plan.hasSeparateAudio) { "Native HLS finalization requires one media rendition; separate audio belongs to the embedded adaptive mux lane." }
        val finalFileName = nativeHlsFinalFileName(spec.fileName, executionCapture, variants, selection, plan)

        val admissionKey = engine.admissionKey(executionCapture, spec.destinationUri, selection).key
        val active = dao.findActiveByAdmissionKey(admissionKey)?.toAdmitted(plan)
        val latest = dao.findLatestByAdmissionKey(admissionKey)?.toAdmitted(plan)
        val admitted = engine.admit(
            capture = executionCapture,
            destinationUri = spec.destinationUri,
            fileName = finalFileName,
            plan = plan,
            existing = if (admissionMode == MediaOutputAdmissionMode.Primary) active else latest,
            selection = selection,
            addAgain = admissionMode == MediaOutputAdmissionMode.AdditionalGeneration,
        )
        if (!admitted.created) {
            return EnqueueOutcome(false, admitted.job.downloadId, admitted.reason)
        }

        val now = System.currentTimeMillis()
        val durableSource = persistableUrl(exactManifestUrl)
        val download = Download(
            id = admitted.job.downloadId,
            fileName = finalFileName,
            sourceUrl = durableSource,
            destinationUri = spec.destinationUri,
            state = DownloadState.Connecting,
            backend = BackendType.Native,
            bytesReceived = 0L,
            totalBytes = null,
            speedBytesPerSecond = 0L,
            queueId = null,
            priority = 0,
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
            userLabel = "Native HLS",
            conflictPolicy = conflictPolicy,
            mimeType = nativeHlsOutputMime(finalFileName, plan),
            requestedBackend = BackendType.Native,
            backendSelectionReason = BackendSelectionReason.MediaWorkflowRequiresNative,
            backendSelectionExplanation = "Supported VOD HLS is owned by XDM's durable native-HLS engine and embedded FFmpeg/FFprobe finalizer.",
            allowBackendFallback = false,
            attemptGeneration = admitted.job.attemptGeneration,
        )
        val admission = repository.createDownloadFromMediaCapture(
            captureId = capture.id,
            download = download,
            selectedTrackIds = spec.selectedTrackIds,
            admissionMode = admissionMode,
        ).getOrThrow()
        if (admission is MediaDownloadAdmissionResult.Existing) {
            return EnqueueOutcome(false, admission.output.downloadId ?: admitted.job.downloadId, "This media capture already owns an output generation.")
        }

        captureHandoff?.let { handoff ->
            MediaRequestHandoffStore.remember(
                downloadId = download.id,
                headers = handoff.headers,
                redactedSummary = handoff.redactedSummary,
                isExpiringUrl = handoff.isExpiringUrl,
                exactUrl = exactManifestUrl,
                pageUrl = handoff.pageUrl,
                requestKind = DownloadRequestKind.Direct,
                transferShape = MediaTransferShape.AdaptivePlaylist,
                expiresAtEpochMs = handoff.expiresAtEpochMs,
                attemptGeneration = download.attemptGeneration,
                privateNetworkApproved = handoff.privateNetworkApproved,
                cleartextCredentialsApproved = handoff.cleartextCredentialsApproved,
            )
        }
        dao.upsertJob(admitted.job.toEntity(capture, plan, now))
        dao.upsertParts(plan.parts.map { it.toEntity(admitted.job.jobId, now) })
        launch(admitted.job.jobId)
        return EnqueueOutcome(true, download.id, "Native HLS queued with embedded FFmpeg finalization and FFprobe verification.")
    }

    suspend fun ownsDownload(downloadId: String): Boolean = dao.findByDownloadId(downloadId) != null

    suspend fun pause(downloadId: String) {
        val intent = registerControlIntent(downloadId, ControlCommand.Pause)
        controlMutex(downloadId).withLock {
            if (!isCurrentControl(downloadId, intent)) return@withLock
            val job = dao.findByDownloadId(downloadId) ?: return@withLock
            requestedControl[downloadId] = RequestedControl.Pause
            val worker = running[downloadId]
            worker?.cancel(CancellationException("Native HLS paused by user"))
            worker?.join()
            if (!isCurrentControl(downloadId, intent)) return@withLock
            val settled = dao.findByDownloadId(downloadId) ?: job
            if (settled.stage !in TERMINAL_NATIVE_HLS_STAGES && settled.stage != NativeHlsExecutionStage.Paused.name) {
                persistStage(settled, NativeHlsExecutionStage.Paused, NativeHlsFinalizationState.None, "Paused; completed parts are preserved.")
            }
            repository.findDownload(downloadId)?.let { current ->
                if (current.state !in setOf(DownloadState.Completed, DownloadState.Cancelled)) {
                    repository.transitionDownloadStateIfCurrent(
                        observed = current,
                        state = DownloadState.Paused,
                        errorMessage = current.errorMessage,
                        speedBytesPerSecond = 0L,
                        allowedStates = setOf(DownloadState.Created, DownloadState.Queued, DownloadState.Connecting, DownloadState.Downloading, DownloadState.Verifying, DownloadState.Finalizing, DownloadState.RecoveryRequired, DownloadState.Failed),
                    )
                }
            }
        }
    }

    suspend fun resume(downloadId: String) {
        val intent = registerControlIntent(downloadId, ControlCommand.Resume)
        controlMutex(downloadId).withLock {
            if (!isCurrentControl(downloadId, intent)) return@withLock
            val job = dao.findByDownloadId(downloadId) ?: return@withLock
            requestedControl.remove(downloadId)
            if (!isCurrentControl(downloadId, intent)) return@withLock
            if (job.stage in setOf(NativeHlsExecutionStage.Paused.name, NativeHlsExecutionStage.Recovering.name, NativeHlsExecutionStage.Failed.name)) {
                persistStage(job, NativeHlsExecutionStage.Recovering, NativeHlsFinalizationState.None, "Resuming from the durable completed-part ledger.")
                if (isCurrentControl(downloadId, intent)) launch(job.id)
            } else if (job.stage !in TERMINAL_NATIVE_HLS_STAGES && running[downloadId]?.isCompleted != false) {
                launch(job.id)
            }
        }
    }

    suspend fun cancel(downloadId: String) {
        val intent = registerControlIntent(downloadId, ControlCommand.Cancel)
        controlMutex(downloadId).withLock {
            if (!isCurrentControl(downloadId, intent)) return@withLock
            val job = dao.findByDownloadId(downloadId) ?: return@withLock
            requestedControl[downloadId] = RequestedControl.Cancel
            val worker = running[downloadId]
            worker?.cancel(CancellationException("Native HLS cancelled by user"))
            worker?.join()
            if (!isCurrentControl(downloadId, intent)) return@withLock
            withContext(NonCancellable) {
                val settled = dao.findByDownloadId(downloadId) ?: job
                if (settled.stage !in TERMINAL_NATIVE_HLS_STAGES) markCancelled(settled)
            }
        }
    }

    suspend fun pauseAll() {
        dao.activeJobs().map(NativeHlsJobEntity::downloadId).distinct().forEach { pause(it) }
    }

    suspend fun resumeAll() {
        (dao.activeJobs() + dao.pausedJobs()).map(NativeHlsJobEntity::downloadId).distinct().forEach { resume(it) }
    }

    private fun registerControlIntent(downloadId: String, command: ControlCommand): ControlIntent =
        ControlIntent(controlSequence.incrementAndGet(), command).also { latestControl[downloadId] = it }

    private fun isCurrentControl(downloadId: String, intent: ControlIntent): Boolean = latestControl[downloadId] == intent

    private fun controlMutex(downloadId: String): Mutex = controlMutexes.computeIfAbsent(downloadId) { Mutex() }

    suspend fun recoverInterruptedJobs() {
        dao.activeJobs().forEach { job ->
            if (reconcileCommittedPublication(job)) return@forEach
            persistStage(job, NativeHlsExecutionStage.Recovering, NativeHlsFinalizationState.None, "Recovering interrupted native HLS work from durable parts.")
            launch(job.id)
        }
    }

    private fun launch(jobId: String) {
        scope.launch {
            val row = dao.findJob(jobId) ?: return@launch
            if (row.stage in setOf(NativeHlsExecutionStage.Paused.name, NativeHlsExecutionStage.Cancelled.name)) return@launch
            if (requestedControl[row.downloadId] != null) return@launch
            val worker = running.compute(row.downloadId) { _, existing ->
                // A LAZY coroutine is deliberately not active until start(), so `isActive` is not
                // a safe ownership predicate here. Treat every non-completed owner as reserved;
                // concurrent launch callers then receive the same Job and Job.start() is idempotent.
                if (existing != null && !existing.isCompleted) {
                    existing
                } else {
                    scope.launch(start = CoroutineStart.LAZY) { execute(jobId) }.also { created ->
                        created.invokeOnCompletion { running.remove(row.downloadId, created) }
                    }
                }
            }
            worker?.start()
        }
    }

    private suspend fun execute(jobId: String) {
        var prepared: com.mikeyphw.xdm.android.storage.PreparedDestination? = null
        val original = dao.findJob(jobId) ?: return
        try {
            val download = repository.findDownload(original.downloadId) ?: error("Native HLS download row is missing")
            val capture = repository.findMediaCapture(original.captureId) ?: error("Native HLS capture is missing")
            val variants = repository.variantsForMediaCapture(capture.id)
            val handoff = MediaRequestHandoffStore.forDownload(download.id) ?: MediaRequestHandoffStore.forCapture(capture.id)
            val manifestUrl = handoff?.exactUrl ?: original.manifestUrl
            val headers = handoff?.headers.orEmpty()
            val playlist = fetchText(manifestUrl, headers, handoff)
            val executionCapture = capture.copy(sourceUrl = manifestUrl)
            val selection = MediaTrackSelection(
                videoVariantId = original.selectedVideoVariantId,
                audioVariantId = original.selectedAudioVariantId,
                subtitleVariantId = original.selectedSubtitleVariantId,
            )
            val plan = engine.negotiate(executionCapture, variants, playlist, headers, selection)
            require(plan.nativeExecutable && !plan.hasSeparateAudio) { "Recovered playlist is no longer executable by native HLS: ${plan.fallbackReason}" }
            require(plan.parts.size == original.partCount) { "Recovered playlist changed part count from ${original.partCount} to ${plan.parts.size}; refresh/review is required." }

            val tempDir = safeTempDirectory(original.tempDirectoryKey)
            tempDir.mkdirs()
            persistStage(original, NativeHlsExecutionStage.Downloading, NativeHlsFinalizationState.None, "Downloading HLS parts with durable checkpoints.")
            repository.save(download.copy(state = DownloadState.Downloading, updatedAtEpochMs = System.currentTimeMillis()))

            val persistedParts = dao.partsForJob(jobId).associateBy { it.partIndex }
            for (part in plan.parts) {
                ensureNotControlled(download.id)
                var current = persistedParts[part.index]
                val target = partFile(tempDir, part.index)
                if (current != null && !partIdentityMatches(current, part)) {
                    // Same index is not sufficient after process death or a refreshed manifest.
                    // Never combine a completed part from another media sequence/key/map identity.
                    target.delete()
                    dao.upsertParts(listOf(part.toEntity(jobId, System.currentTimeMillis())))
                    current = null
                }
                if (current?.state == NativeHlsPartState.Complete.name && target.isFile && current.sha256Hex == sha256File(target)) continue
                downloadPart(jobId, part, target, headers, handoff, current?.retryCount ?: 0)
                updateAggregateProgress(jobId, plan, NativeHlsExecutionStage.Downloading)
            }

            val completeRows = dao.partsForJob(jobId)
            require(completeRows.size == plan.parts.size && completeRows.all { it.state == NativeHlsPartState.Complete.name || it.state == NativeHlsPartState.Skipped.name }) {
                "Native HLS finalization refused because not every part is durably complete or explicitly skipped."
            }
            val segmentFiles = plan.parts.filterNot { it.gap }.map { partFile(tempDir, it.index) }
            require(segmentFiles.all { it.isFile && it.length() > 0L }) { "A durably complete HLS part file is missing." }

            val outputRequest = DestinationRequest(
                downloadId = download.id,
                destinationUri = download.destinationUri,
                fileName = download.fileName,
                mimeType = download.mimeType,
                conflictPolicy = download.conflictPolicy,
                attemptGeneration = download.attemptGeneration,
            )
            prepared = destinationWriter.prepare(outputRequest)
            val inputBytes = segmentFiles.sumOf(File::length)
            val storagePlan = engine.storagePreflight(plan, prepared.availableSpace(), inputBytes)
            require(storagePlan.okToStart) { storagePlan.message }

            persistStage(dao.findJob(jobId) ?: original, NativeHlsExecutionStage.Finalizing, NativeHlsFinalizationState.Muxing, "Finalizing all completed parts with embedded FFmpeg.")
            repository.findDownload(download.id)?.let { repository.save(it.copy(state = DownloadState.Finalizing, updatedAtEpochMs = System.currentTimeMillis())) }
            val result = finalizer.finalize(plan, segmentFiles, prepared.artifacts.stagingFile) { progress ->
                scope.launch {
                    val row = dao.findJob(jobId) ?: return@launch
                    val percent = progress.percent?.let { 85 + (it.coerceIn(0, 100) * 5 / 100) } ?: row.progressPercent.coerceAtLeast(85)
                    dao.updateStage(jobId, NativeHlsExecutionStage.Finalizing.name, NativeHlsFinalizationState.Muxing.name, percent.coerceAtMost(90), progress.userLabel, System.currentTimeMillis())
                }
            }
            require(result.success) { result.execution.message }
            ensureNotControlled(download.id)

            persistStage(dao.findJob(jobId) ?: original, NativeHlsExecutionStage.Verifying, NativeHlsFinalizationState.Verifying, "Embedded FFprobe verification passed; checking final artifact integrity.")
            val staged = prepared.artifacts.stagingFile
            val executablePartCount = plan.parts.count { !it.gap }.toLong().coerceAtLeast(1L)
            require(staged.isFile && staged.length() >= executablePartCount * 188L) { "Final HLS artifact is implausibly small." }
            val prefix = staged.inputStream().use { input -> ByteArray(512).let { buffer -> buffer.copyOf(input.read(buffer).coerceAtLeast(0)) } }.toString(Charsets.UTF_8)
            require(!prefix.contains("#EXTM3U") && !prefix.contains("<html", true) && !prefix.contains("<!doctype html", true)) { "Finalized artifact looks like manifest/error text rather than media." }
            val digest = sha256File(staged)

            val publishingRow = (dao.findJob(jobId) ?: original).copy(
                stage = NativeHlsExecutionStage.Publishing.name,
                finalizationState = NativeHlsFinalizationState.Publishing.name,
                completedArtifactBytes = staged.length(),
                completedArtifactSha256 = digest,
                recoverable = true,
                message = "Publishing verified media to the selected Android destination.",
                updatedAtEpochMs = System.currentTimeMillis(),
            )
            dao.upsertJob(publishingRow)
            val promoted = prepared.promote()
            val committedAt = System.currentTimeMillis()
            repository.saveFinalizationJournal(
                FinalizationJournal(
                    id = nativeHlsFinalizationJournalId(download.id, download.attemptGeneration),
                    downloadId = download.id,
                    stage = FinalizationJournalStage.DestinationCommitted,
                    sourcePath = promoted.committedUri,
                    stagingPath = null,
                    destinationUri = promoted.committedUri,
                    bytesExpected = promoted.bytesCommitted,
                    bytesPromoted = promoted.bytesCommitted,
                    checksumAlgorithm = ChecksumAlgorithm.Sha256,
                    checksumHex = digest,
                    message = "Native HLS destination committed after embedded FFprobe verification; Room completion metadata is being reconciled.",
                    createdAtEpochMs = committedAt,
                    updatedAtEpochMs = committedAt,
                    attemptGeneration = download.attemptGeneration,
                ),
            )
            completeCommittedPublication(
                job = publishingRow,
                download = download,
                capture = capture,
                committedUri = promoted.committedUri,
                bytesCommitted = promoted.bytesCommitted,
                digest = digest,
                tempDir = tempDir,
            )
            runCatching { prepared.deleteArtifacts() }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                val row = dao.findJob(jobId) ?: original
                if (!reconcileCommittedPublication(row)) {
                    prepared?.deleteArtifacts()
                    when (requestedControl.remove(row.downloadId)) {
                        RequestedControl.Cancel -> markCancelled(row)
                        RequestedControl.Pause -> persistStage(row, NativeHlsExecutionStage.Paused, NativeHlsFinalizationState.None, "Paused; completed parts are preserved.")
                        null -> persistFailure(row, "Native HLS execution was interrupted; durable recovery is available.")
                    }
                }
            }
            throw cancelled
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                val row = dao.findJob(jobId) ?: original
                if (!reconcileCommittedPublication(row)) {
                    prepared?.deleteArtifacts()
                    persistFailure(row, error.message ?: error::class.java.simpleName)
                }
            }
        }
    }

    private suspend fun downloadPart(
        jobId: String,
        part: NativeHlsPart,
        target: File,
        headers: Map<String, String>,
        handoff: MediaRequestHandoff?,
        priorRetries: Int,
    ) {
        if (part.gap || part.state == NativeHlsPartState.Skipped) {
            target.delete()
            dao.updatePart(partEntityId(jobId, part.index), NativeHlsPartState.Skipped.name, 0L, 0L, null, priorRetries, null, System.currentTimeMillis())
            return
        }
        var lastError: Throwable? = null
        for (attempt in 0 until 3) {
            try {
                val now = System.currentTimeMillis()
                dao.updatePart(partEntityId(jobId, part.index), NativeHlsPartState.Downloading.name, 0L, part.expectedBytes, null, priorRetries + attempt, null, now)
                val mapBytes = part.initMap?.let { map ->
                    fetchBytes(map.uri, headers, handoff, map.byteRange?.headerValue, map.byteRange?.length ?: MAX_HLS_INIT_MAP_BYTES)
                } ?: ByteArray(0)
                val mediaLimit = part.byteRange?.length ?: MAX_HLS_SEGMENT_BYTES
                var media = fetchBytes(part.url, headers, handoff, part.byteRange?.headerValue, mediaLimit)
                part.key?.takeIf { it.isAes128 }?.let { key ->
                    val keyUrl = requireNotNull(key.uri) { "AES-128 HLS key URL is missing" }
                    val keyBytes = fetchBytes(keyUrl, headers, handoff, null, MAX_HLS_KEY_BYTES)
                    require(keyBytes.size == 16) { "AES-128 HLS key must be exactly 16 bytes" }
                    media = decryptAes128(media, keyBytes, requireNotNull(part.effectiveIvHex) { "AES-128 HLS IV is missing" })
                }
                target.parentFile?.mkdirs()
                val partial = File(target.parentFile, target.name + ".partial")
                FileOutputStream(partial).use { out ->
                    if (mapBytes.isNotEmpty()) out.write(mapBytes)
                    out.write(media)
                    out.flush()
                    out.fd.sync()
                }
                if (target.exists()) check(target.delete()) { "Unable to replace stale native HLS part ${part.index}" }
                check(partial.renameTo(target)) { "Unable to commit native HLS part ${part.index}" }
                val digest = sha256File(target)
                dao.updatePart(partEntityId(jobId, part.index), NativeHlsPartState.Complete.name, target.length(), target.length(), digest, priorRetries + attempt, null, System.currentTimeMillis())
                return
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                lastError = error
                dao.updatePart(partEntityId(jobId, part.index), NativeHlsPartState.Failed.name, 0L, part.expectedBytes, null, priorRetries + attempt + 1, error.message?.take(300), System.currentTimeMillis())
                if (attempt == 2) break
                // Do not hammer a temporarily unavailable resolver/CDN. DNS validation itself has a
                // short retry window; this outer part retry gives the whole manifest/key/segment
                // request another bounded chance without ever bypassing the security guard.
                val baseDelay = if (
                    error is TransferRequestSecurityException &&
                    error.kind == TransferSecurityFailureKind.DnsResolutionFailed
                ) HLS_DNS_RETRY_BACKOFF_MILLIS else HLS_PART_RETRY_BACKOFF_MILLIS
                delay(baseDelay * (attempt + 1L))
            }
        }
        throw IllegalStateException("HLS part ${part.index} failed after bounded retries: ${lastError?.message}", lastError)
    }

    private suspend fun fetchText(url: String, headers: Map<String, String>, handoff: MediaRequestHandoff?): String =
        fetchBytes(url, headers, handoff, null, MAX_HLS_MANIFEST_BYTES).toString(Charsets.UTF_8)

    private suspend fun fetchBytes(url: String, headers: Map<String, String>, handoff: MediaRequestHandoff?, range: String?, maxBytes: Long): ByteArray {
        var current = url
        repeat(6) { redirectCount ->
            // Resolve once inside the security decision, then pin OkHttp to exactly those approved
            // addresses. This closes the validate-then-resolve-again DNS race while retaining a
            // fresh security decision for every explicit redirect target.
            val validatedTarget = validateRequest(current, headers, handoff, range)
            val builder = Request.Builder().url(current)
            filteredHeaders(current, headers, handoff).forEach { (name, value) -> builder.header(name, value) }
            range?.let { builder.header("Range", it) }
            val runningResponse = executeCancellable(clientForValidatedTarget(validatedTarget), builder.build())
            val response = runningResponse.response
            try {
                if (response.code in 300..399) {
                    require(redirectCount < 5) { "Too many HLS redirects" }
                    val location = response.header("Location") ?: error("HLS redirect has no Location")
                    val next = URI(current).resolve(location).toString()
                    require(!(current.startsWith("https://", true) && next.startsWith("http://", true))) { "HTTPS-to-HTTP HLS redirect refused" }
                    current = next
                    return@repeat
                }
                if (range != null) {
                    require(response.code == 206) { "HLS byte-range request expected HTTP 206, got ${response.code}" }
                    validateContentRange(response.header("Content-Range"), requireNotNull(parseRangeHeader(range)), response.body?.contentLength())
                } else {
                    require(response.isSuccessful) { "HLS request failed with HTTP ${response.code}" }
                }
                val body = response.body ?: error("HLS response body is empty")
                return readBoundedBody(body, runningResponse.call, maxBytes)
            } finally {
                response.close()
            }
        }
        error("Too many HLS redirects")
    }

    private suspend fun readBoundedBody(body: ResponseBody, call: Call, maxBytes: Long): ByteArray {
        require(maxBytes in 1L..Int.MAX_VALUE.toLong()) { "Invalid HLS response bound: $maxBytes" }
        val context = currentCoroutineContext()
        val cancellationHandle = context[Job]?.invokeOnCompletion { cause ->
            if (cause is CancellationException) call.cancel()
        }
        return try {
            withContext(Dispatchers.IO) {
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(DEFAULT_HLS_READ_BUFFER_BYTES)
                var total = 0L
                body.byteStream().use { input ->
                    while (true) {
                        context.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        total += read.toLong()
                        require(total <= maxBytes) { "HLS response exceeded bounded cap of $maxBytes bytes" }
                        output.write(buffer, 0, read)
                    }
                }
                output.toByteArray()
            }
        } finally {
            cancellationHandle?.dispose()
        }
    }

    private suspend fun executeCancellable(requestClient: OkHttpClient, request: Request): RunningHttpResponse = suspendCancellableCoroutine { continuation ->
        val call = requestClient.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }

            override fun onResponse(call: Call, response: Response) {
                if (!continuation.isActive) {
                    response.close()
                    return
                }
                continuation.resume(RunningHttpResponse(call, response))
            }
        })
    }

    private suspend fun validateRequest(
        url: String,
        headers: Map<String, String>,
        handoff: MediaRequestHandoff?,
        range: String?,
    ): ValidatedTransferNetworkTarget {
        val scopedHeaders = filteredHeaders(url, headers, handoff).toMutableMap()
        range?.let { scopedHeaders["Range"] = it }
        val privateScope = DownloadRequestApprovalScope.forUrl(url)
        val request = DownloadRequest(
            id = "native-hls-security",
            sourceUrl = url,
            destinationUri = "xdm://private/native-hls",
            fileName = "part.bin",
            preferredBackend = BackendType.Native,
            requestKind = DownloadRequestKind.Direct,
            headers = scopedHeaders,
            transferShape = MediaTransferShape.DirectMedia,
            privateNetworkApproved = handoff?.privateNetworkApproved == true && privateScope != null && privateScope in handoff.privateNetworkApprovalScopes,
            cleartextCredentialsApproved = handoff?.cleartextCredentialsApproved == true && privateScope != null && privateScope in handoff.cleartextCredentialApprovalScopes,
            privateNetworkApprovalScopes = handoff?.privateNetworkApprovalScopes.orEmpty(),
            cleartextCredentialApprovalScopes = handoff?.cleartextCredentialApprovalScopes.orEmpty(),
        )
        return securityGuard.validateAndResolveTarget(request, url)
    }

    private fun clientForValidatedTarget(target: ValidatedTransferNetworkTarget): OkHttpClient =
        client.newBuilder()
            .dns(Dns { hostname ->
                if (!hostname.equals(target.host, ignoreCase = true)) {
                    throw UnknownHostException("HLS transport attempted an unvalidated hostname")
                }
                target.addresses
            })
            .build()

    private fun filteredHeaders(url: String, headers: Map<String, String>, handoff: MediaRequestHandoff?): Map<String, String> {
        val handoffOrigin = handoff?.exactUrl?.let(::originKeyForSecurity)
        val requestOrigin = originKeyForSecurity(url)
        if (handoffOrigin != null && handoffOrigin == requestOrigin) return headers
        return headers.filterKeys { name -> name.lowercase() !in SENSITIVE_NATIVE_HLS_HEADERS }
    }

    private suspend fun updateAggregateProgress(jobId: String, plan: NativeHlsManifestPlan, stage: NativeHlsExecutionStage) {
        val row = dao.findJob(jobId) ?: return
        val parts = dao.partsForJob(jobId).mapIndexed { index, entity ->
            plan.parts[index].copy(
                state = runCatching { NativeHlsPartState.valueOf(entity.state) }.getOrDefault(NativeHlsPartState.Pending),
                bytesReceived = entity.bytesReceived,
                expectedBytes = entity.expectedBytes,
                sha256Hex = entity.sha256Hex,
            )
        }
        val progress = engine.progress(parts, stage, row.progressPercent)
        val total = parts.mapNotNull { it.expectedBytes }.sum().takeIf { it > 0L }
        dao.upsertJob(row.copy(
            stage = stage.name,
            completedPartCount = progress.completedParts,
            bytesReceived = progress.downloadedBytes,
            totalBytes = total,
            progressPercent = progress.percent,
            message = progress.userMessage,
            updatedAtEpochMs = System.currentTimeMillis(),
        ))
        repository.findDownload(row.downloadId)?.let { download ->
            repository.save(download.copy(
                state = when (stage) {
                    NativeHlsExecutionStage.Finalizing, NativeHlsExecutionStage.Publishing -> DownloadState.Finalizing
                    NativeHlsExecutionStage.Verifying -> DownloadState.Verifying
                    NativeHlsExecutionStage.Paused -> DownloadState.Paused
                    else -> DownloadState.Downloading
                },
                bytesReceived = progress.downloadedBytes,
                totalBytes = total,
                updatedAtEpochMs = System.currentTimeMillis(),
            ))
        }
    }

    private suspend fun reconcileCommittedPublication(job: NativeHlsJobEntity): Boolean {
        val journal = repository.finalizationForDownload(job.downloadId) ?: return false
        if (journal.attemptGeneration != job.attemptGeneration) return false
        if (journal.stage !in setOf(FinalizationJournalStage.DestinationCommitted, FinalizationJournalStage.MetadataCommitted)) return false
        val digest = job.completedArtifactSha256?.takeIf(String::isNotBlank) ?: journal.checksumHex?.takeIf(String::isNotBlank) ?: return false
        val expectedBytes = job.completedArtifactBytes ?: journal.bytesExpected
        val bytesCommitted = journal.bytesPromoted.takeIf { it > 0L } ?: expectedBytes ?: return false
        if (expectedBytes != null && expectedBytes != bytesCommitted) return false
        val committedUri = journal.destinationUri.takeIf(String::isNotBlank) ?: return false
        val download = repository.findDownload(job.downloadId) ?: return false
        val capture = repository.findMediaCapture(job.captureId) ?: return false
        completeCommittedPublication(
            job = job,
            download = download,
            capture = capture,
            committedUri = committedUri,
            bytesCommitted = bytesCommitted,
            digest = digest,
            tempDir = safeTempDirectory(job.tempDirectoryKey),
            recoveryMessage = "Recovered an already committed native HLS destination from the canonical publication journal without remuxing or publishing a duplicate.",
        )
        return true
    }

    private suspend fun completeCommittedPublication(
        job: NativeHlsJobEntity,
        download: Download,
        capture: MediaCaptureRecord,
        committedUri: String,
        bytesCommitted: Long,
        digest: String,
        tempDir: File,
        recoveryMessage: String? = null,
    ) {
        val now = System.currentTimeMillis()
        val completed = (dao.findJob(job.id) ?: job).copy(
            stage = NativeHlsExecutionStage.Completed.name,
            finalizationState = NativeHlsFinalizationState.Completed.name,
            completedPartCount = job.partCount,
            bytesReceived = bytesCommitted,
            totalBytes = bytesCommitted,
            progressPercent = 100,
            completedArtifactUri = committedUri,
            completedArtifactBytes = bytesCommitted,
            completedArtifactSha256 = digest,
            recoverable = false,
            message = recoveryMessage ?: "Completed with embedded FFmpeg finalization and FFprobe verification.",
            updatedAtEpochMs = now,
        )
        dao.upsertJob(completed)
        repository.findDownload(download.id)?.let { current ->
            check(repository.save(current.copy(
                state = DownloadState.Completed,
                bytesReceived = bytesCommitted,
                totalBytes = bytesCommitted,
                speedBytesPerSecond = 0L,
                errorMessage = null,
                completedArtifactUri = committedUri,
                completedArtifactGeneration = current.attemptGeneration,
                completedArtifactBytes = bytesCommitted,
                updatedAtEpochMs = now,
            ))) { "Native HLS completed download state changed before completion commit" }
        }
        repository.mediaOutputsForCapture(capture.id)
            .firstOrNull { it.downloadId == download.id }
            ?.let { check(repository.saveMediaOutput(it.copy(
                state = MediaOutputState.Completed,
                completedArtifactUri = committedUri,
                completedArtifactGeneration = download.attemptGeneration,
                updatedAtEpochMs = now,
            ))) { "Native HLS media output changed before completion commit" } }
        val journal = repository.finalizationForDownload(download.id)
        repository.saveFinalizationJournal(
            (journal ?: FinalizationJournal(
                id = nativeHlsFinalizationJournalId(download.id, download.attemptGeneration),
                downloadId = download.id,
                stage = FinalizationJournalStage.DestinationCommitted,
                sourcePath = committedUri,
                stagingPath = null,
                destinationUri = committedUri,
                bytesExpected = bytesCommitted,
                bytesPromoted = bytesCommitted,
                checksumAlgorithm = ChecksumAlgorithm.Sha256,
                checksumHex = digest,
                message = "Recovered native HLS publication metadata.",
                createdAtEpochMs = now,
                updatedAtEpochMs = now,
                attemptGeneration = download.attemptGeneration,
            )).copy(
                stage = FinalizationJournalStage.Completed,
                sourcePath = committedUri,
                stagingPath = null,
                destinationUri = committedUri,
                bytesExpected = bytesCommitted,
                bytesPromoted = bytesCommitted,
                checksumAlgorithm = ChecksumAlgorithm.Sha256,
                checksumHex = digest,
                message = recoveryMessage ?: "Native HLS publication and Room completion metadata reconciled.",
                updatedAtEpochMs = now,
            ),
        )
        repository.deleteRecoveryForDownload(download.id)
        MediaRequestHandoffStore.forget(download.id)
        tempDir.deleteRecursively()
    }

    private fun nativeHlsFinalizationJournalId(downloadId: String, attemptGeneration: Long): String =
        "native-hls-finalize-$downloadId-$attemptGeneration"

    private suspend fun persistStage(row: NativeHlsJobEntity, stage: NativeHlsExecutionStage, finalization: NativeHlsFinalizationState, message: String) {
        dao.upsertJob(row.copy(stage = stage.name, finalizationState = finalization.name, recoverable = stage !in setOf(NativeHlsExecutionStage.Completed, NativeHlsExecutionStage.Cancelled), message = message, updatedAtEpochMs = System.currentTimeMillis()))
    }

    private suspend fun persistFailure(row: NativeHlsJobEntity, message: String) {
        val now = System.currentTimeMillis()
        dao.upsertJob(row.copy(stage = NativeHlsExecutionStage.Failed.name, finalizationState = NativeHlsFinalizationState.RecoveryRequired.name, recoverable = true, message = message.take(500), updatedAtEpochMs = now))
        repository.findDownload(row.downloadId)?.let { repository.save(it.copy(state = DownloadState.RecoveryRequired, errorMessage = message.take(500), speedBytesPerSecond = 0L, updatedAtEpochMs = now)) }
    }

    private suspend fun markCancelled(row: NativeHlsJobEntity) {
        if (reconcileCommittedPublication(row)) return
        val now = System.currentTimeMillis()
        safeTempDirectory(row.tempDirectoryKey).deleteRecursively()
        dao.upsertJob(row.copy(stage = NativeHlsExecutionStage.Cancelled.name, finalizationState = NativeHlsFinalizationState.Cancelled.name, recoverable = false, message = "Cancelled and owned temporary artifacts removed.", updatedAtEpochMs = now))
        repository.findDownload(row.downloadId)?.let { repository.save(it.copy(state = DownloadState.Cancelled, speedBytesPerSecond = 0L, updatedAtEpochMs = now)) }
        MediaRequestHandoffStore.forget(row.downloadId)
    }

    private fun ensureNotControlled(downloadId: String) {
        when (requestedControl[downloadId]) {
            RequestedControl.Pause -> throw CancellationException("Native HLS paused")
            RequestedControl.Cancel -> throw CancellationException("Native HLS cancelled")
            null -> Unit
        }
    }

    private fun nativeHlsFinalFileName(
        requestedName: String,
        capture: MediaCaptureRecord,
        variants: List<MediaVariant>,
        selection: MediaTrackSelection,
        plan: NativeHlsManifestPlan,
    ): String {
        val leaf = requestedName.substringAfterLast('/').substringAfterLast('\\').ifBlank { "xdm-media" }
        val base = leaf.substringBeforeLast('.', leaf).replace(Regex("[\r\n\t]"), " ").ifBlank { "xdm-media" }
        val audioOnly = plan.requireAudioStream && !plan.requireVideoStream
        val selectedIds = selection.selectedIds()
        val codecText = buildString {
            append(capture.codecs.orEmpty()).append(' ')
            variants.asSequence()
                .filter { it.id in selectedIds || (selectedIds.isEmpty() && it.kind == MediaVariantKind.Audio) }
                .mapNotNull(MediaVariant::codecs)
                .forEach { append(it).append(' ') }
        }.lowercase()
        val mimeText = buildString {
            append(capture.mimeType.orEmpty()).append(' ')
            variants.asSequence()
                .filter { it.id in selectedIds || (selectedIds.isEmpty() && it.kind == MediaVariantKind.Audio) }
                .mapNotNull(MediaVariant::mimeType)
                .forEach { append(it).append(' ') }
        }.lowercase()
        val aacCompatible = "mp4a" in codecText || Regex("(^|[^a-z])aac([^a-z]|$)").containsMatchIn(codecText) ||
            "audio/aac" in mimeText || "audio/mp4" in mimeText
        val extension = when {
            !audioOnly -> ".mkv"
            aacCompatible -> ".m4a"
            else -> ".mka"
        }
        return base.take((120 - extension.length).coerceAtLeast(1)) + extension
    }

    private fun nativeHlsOutputMime(fileName: String, plan: NativeHlsManifestPlan): String = when (
        fileName.substringAfterLast('.', "").lowercase()
    ) {
        "m4a" -> "audio/mp4"
        "aac" -> "audio/aac"
        "mp3" -> "audio/mpeg"
        "mkv" -> "video/x-matroska"
        "mka" -> "audio/x-matroska"
        "webm" -> if (plan.requireVideoStream) "video/webm" else "audio/webm"
        "mp4", "m4v" -> if (plan.requireVideoStream) "video/mp4" else "audio/mp4"
        else -> if (plan.requireAudioStream && !plan.requireVideoStream) "audio/mp4" else "video/x-matroska"
    }

    private fun safeTempDirectory(key: String): File {
        val root = File(appContext.filesDir, "native-hls").canonicalFile.apply(File::mkdirs)
        // Use the complete durable key. Keeping only the final `g1`/`g2` component lets unrelated
        // captures share one directory and race each other's `part-000000.media` files.
        val leaf = MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(32)
        val dir = File(root, leaf).canonicalFile
        require(dir.parentFile == root) { "Native HLS temp directory escaped app-private storage" }
        return dir
    }

    private fun partFile(dir: File, index: Int) = File(dir, "part-${index.toString().padStart(6, '0')}.media")

    private fun decryptAes128(ciphertext: ByteArray, key: ByteArray, ivHex: String): ByteArray {
        val normalizedIv = ivHex.removePrefix("0x").removePrefix("0X")
        require(normalizedIv.matches(Regex("^[0-9a-fA-F]{32}$"))) { "AES-128 IV must be exactly 16 bytes encoded as 32 hexadecimal characters" }
        val iv = normalizedIv.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        require(iv.size == 16) { "AES-128 IV must be 16 bytes" }
        return Cipher.getInstance("AES/CBC/PKCS5Padding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            doFinal(ciphertext)
        }
    }

    private fun sha256File(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun persistableUrl(url: String): String = ExternalUrlPolicy.persistableUrl(url) ?: url.substringBefore('?')

    /**
     * Durable init-map identity deliberately includes the byte range. Signed/query credentials are
     * stripped by persistableUrl(), while a refreshed playlist that reuses the same map URI with a
     * different slice cannot accidentally reuse a part assembled with stale initialization bytes.
     */
    private fun persistableMapIdentity(map: com.mikeyphw.xdm.android.media.NativeHlsMap?): String? = map?.let { value ->
        val range = value.byteRange?.let { byteRange -> "${byteRange.offset ?: 0L}:${byteRange.length}" } ?: "full"
        "${persistableUrl(value.uri)}#xdm-map-range=$range"
    }

    private fun partEntityId(jobId: String, partIndex: Int): String = "$jobId:part:$partIndex"

    private fun originKeyForSecurity(url: String): String? = runCatching {
        val uri = URI(url)
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host?.lowercase() ?: return null
        val port = when {
            uri.port >= 0 -> uri.port
            scheme == "http" -> 80
            scheme == "https" -> 443
            else -> -1
        }
        if (port >= 0) "$scheme://$host:$port" else "$scheme://$host"
    }.getOrNull()

    private fun parseRangeHeader(value: String): LongRange? {
        val match = Regex("^bytes=(\\d+)-(\\d+)$").matchEntire(value.trim()) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        return if (end >= start) start..end else null
    }

    private fun validateContentRange(contentRange: String?, requested: LongRange, contentLength: Long?) {
        val value = requireNotNull(contentRange?.trim()) { "HLS byte-range response is missing Content-Range" }
        val match = Regex("^bytes (\\d+)-(\\d+)/(\\d+|\\*)$", RegexOption.IGNORE_CASE).matchEntire(value)
            ?: error("Invalid HLS Content-Range: $value")
        val start = match.groupValues[1].toLong()
        val end = match.groupValues[2].toLong()
        require(start == requested.first && end == requested.last) { "HLS Content-Range does not match the requested range" }
        match.groupValues[3].takeUnless { it == "*" }?.toLongOrNull()?.let { total ->
            require(total > end) { "HLS Content-Range total is smaller than returned range" }
        }
        contentLength?.takeIf { it >= 0L }?.let { length ->
            require(length == (requested.last - requested.first + 1L)) { "HLS byte-range body length does not match Content-Range" }
        }
    }

    private fun partIdentityMatches(row: NativeHlsPartEntity, part: NativeHlsPart): Boolean =
        row.mediaSequence == part.mediaSequence &&
            row.url == persistableUrl(part.url) &&
            row.byteRangeOffset == part.byteRange?.offset &&
            row.byteRangeLength == part.byteRange?.length &&
            row.initMapUrl == persistableMapIdentity(part.initMap) &&
            row.keyUri == part.key?.uri?.let(::persistableUrl) &&
            row.keyMethod == part.key?.method &&
            row.keyIvHex == part.key?.ivHex &&
            row.discontinuitySequence == part.discontinuitySequence &&
            (if (part.gap) row.state == NativeHlsPartState.Skipped.name else true)

    private fun NativeHlsPart.toEntity(jobId: String, now: Long) = NativeHlsPartEntity(
        id = partEntityId(jobId, index),
        jobId = jobId,
        partIndex = index,
        url = persistableUrl(url),
        mediaSequence = mediaSequence,
        durationMs = durationMs,
        byteRangeOffset = byteRange?.offset,
        byteRangeLength = byteRange?.length,
        initMapUrl = persistableMapIdentity(initMap),
        keyUri = key?.uri?.let(::persistableUrl),
        keyMethod = key?.method,
        keyIvHex = key?.ivHex,
        discontinuitySequence = discontinuitySequence,
        state = state.name,
        bytesReceived = bytesReceived,
        expectedBytes = expectedBytes,
        sha256Hex = sha256Hex,
        retryCount = 0,
        lastError = null,
        updatedAtEpochMs = now,
    )

    private fun com.mikeyphw.xdm.android.media.NativeHlsAdmittedJob.toEntity(capture: MediaCaptureRecord, plan: NativeHlsManifestPlan, now: Long) = NativeHlsJobEntity(
        id = jobId,
        captureId = capture.id,
        downloadId = downloadId,
        logicalMediaId = capture.logicalMediaId ?: LogicalMediaGraphEngine.identityUrl(capture.canonicalMediaUrl ?: capture.sourceUrl),
        admissionKey = admissionKey,
        attemptGeneration = attemptGeneration,
        manifestUrl = persistableUrl(plan.manifestUrl),
        canonicalManifestUrl = plan.canonicalManifestUrl,
        selectedVideoVariantId = plan.selectedVideoVariantId,
        selectedAudioVariantId = plan.selectedAudioVariantId,
        selectedSubtitleVariantId = plan.selectedSubtitleVariantId,
        supportStatus = plan.supportStatus.name,
        unsupportedReasons = plan.unsupportedReasons.joinToString(",") { it.name },
        stage = stage.name,
        finalizationState = NativeHlsFinalizationState.None.name,
        partCount = plan.parts.size,
        completedPartCount = 0,
        bytesReceived = 0L,
        totalBytes = plan.parts.mapNotNull { it.expectedBytes }.sum().takeIf { it > 0L },
        progressPercent = 0,
        tempDirectoryKey = tempDirectoryKey,
        destinationUri = destinationUri,
        fileName = fileName,
        completedArtifactUri = null,
        completedArtifactBytes = null,
        completedArtifactSha256 = null,
        recoverable = true,
        cleanupPolicy = "preserve-on-pause-or-recovery;delete-on-cancel-or-success",
        message = "Native HLS admitted for Android-owned execution.",
        createdAtEpochMs = now,
        updatedAtEpochMs = now,
    )

    private fun NativeHlsJobEntity.toAdmitted(plan: NativeHlsManifestPlan) = com.mikeyphw.xdm.android.media.NativeHlsAdmittedJob(
        jobId = id,
        downloadId = downloadId,
        captureId = captureId,
        admissionKey = admissionKey,
        attemptGeneration = attemptGeneration,
        stage = runCatching { NativeHlsExecutionStage.valueOf(stage) }.getOrDefault(NativeHlsExecutionStage.Recovering),
        plan = plan,
        tempDirectoryKey = tempDirectoryKey,
        destinationUri = destinationUri,
        fileName = fileName,
        createdAtEpochMs = createdAtEpochMs,
        updatedAtEpochMs = updatedAtEpochMs,
    )

    private companion object {
        private const val MAX_HLS_MANIFEST_BYTES = 4L * 1024L * 1024L
        private const val MAX_HLS_INIT_MAP_BYTES = 64L * 1024L * 1024L
        private const val MAX_HLS_SEGMENT_BYTES = 512L * 1024L * 1024L
        private const val MAX_HLS_KEY_BYTES = 16L
        private const val HLS_PART_RETRY_BACKOFF_MILLIS = 250L
        private const val HLS_DNS_RETRY_BACKOFF_MILLIS = 500L
        private const val DEFAULT_HLS_READ_BUFFER_BYTES = 64 * 1024
        private val SENSITIVE_NATIVE_HLS_HEADERS = setOf(
            "authorization",
            "cookie",
            "proxy-authorization",
            "referer",
            "origin",
            "x-api-key",
            "x-auth-token",
            "x-access-token",
        )
        val TERMINAL_NATIVE_HLS_STAGES = setOf(NativeHlsExecutionStage.Completed.name, NativeHlsExecutionStage.Cancelled.name)
    }

}
