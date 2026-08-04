package com.mikeyphw.xdm.android.termux

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.mikeyphw.xdm.android.media.MediaDownloadPlanner
import com.mikeyphw.xdm.android.media.MediaSessionHeader
import com.mikeyphw.xdm.android.media.MediaTrackSelection
import com.mikeyphw.xdm.android.model.PrivacyDiagnosticsRedactor
import com.mikeyphw.xdm.android.model.ConversionPreset
import com.mikeyphw.xdm.android.model.DownloadState
import com.mikeyphw.xdm.android.model.MediaCaptureRecord
import com.mikeyphw.xdm.android.model.MediaCaptureStatus
import com.mikeyphw.xdm.android.model.MediaResolutionStatus
import com.mikeyphw.xdm.android.model.MediaSourceKind
import com.mikeyphw.xdm.android.model.MediaVariant
import com.mikeyphw.xdm.android.model.MediaVariantKind
import com.mikeyphw.xdm.android.persistence.AppDatabase
import com.mikeyphw.xdm.android.persistence.DownloadRepository
import com.mikeyphw.xdm.android.persistence.PostProcessingClaimEntity
import com.mikeyphw.xdm.android.persistence.PostProcessingJobEntity
import com.mikeyphw.xdm.android.storage.AndroidDestinationWriter
import com.mikeyphw.xdm.android.util.sanitizeFileName
import java.net.URI
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class TermuxMediaPipelineManager(
    context: Context,
    private val database: AppDatabase,
    private val repository: DownloadRepository,
    destinationWriter: AndroidDestinationWriter,
) : TermuxResultRouter {
    data class EnqueueOutcome(val accepted: Boolean, val job: TermuxMediaPipelineJob, val message: String)

    private val appContext = context.applicationContext
    private val dao = database.postProcessingDao()
    private val runner = TermuxCommandRunner(appContext)
    private val artifactBridge = AndroidPostProcessingArtifactBridge(appContext, destinationWriter)
    private val planner = MediaDownloadPlanner()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val statusFlow = MutableStateFlow(TermuxMediaPipelineStatus(updatedAtEpochMs = System.currentTimeMillis()))
    private val monitoredJobIds = ConcurrentHashMap.newKeySet<String>()

    val status: StateFlow<TermuxMediaPipelineStatus> = statusFlow

    init {
        scope.launch {
            dao.observeJobs().collectLatest { rows ->
                statusFlow.value = TermuxMediaPipelineStatus(
                    enabled = true,
                    lastAction = rows.firstOrNull()?.message ?: "No durable Termux media job has run yet.",
                    jobs = rows.map(PostProcessingJobEntity::toPipelineJob),
                    updatedAtEpochMs = System.currentTimeMillis(),
                )
            }
        }
        scope.launch {
            TermuxRunStore.status.collectLatest {
                launchJobsWhosePrerequisitesAreReady()
            }
        }
    }

    fun refreshStatus() {
        runner.refreshStatus()
        scope.launch { dao.activeJobs().forEach { monitorJob(it.id) } }
    }

    fun recoverInterruptedJobs() {
        scope.launch {
            dao.activeJobs().forEach { job ->
                when (PostProcessingJobStatus.entries.firstOrNull { it.name == job.status }) {
                    PostProcessingJobStatus.Queued,
                    PostProcessingJobStatus.WaitingForPrerequisites -> launchJob(job.id)
                    PostProcessingJobStatus.Publishing -> recoverPublicationNow(job)
                    PostProcessingJobStatus.Preparing,
                    PostProcessingJobStatus.Running,
                    PostProcessingJobStatus.Paused,
                    PostProcessingJobStatus.Cancelling -> recoverActiveJob(job)
                    else -> Unit
                }
            }
        }
    }

    fun extractMetadata(
        record: MediaCaptureRecord,
        variants: List<MediaVariant> = emptyList(),
        selection: MediaTrackSelection = MediaTrackSelection(),
    ): TermuxMediaPipelineJob {
        val plan = planner.plan(record, variants, selection = selection, sessionHeaders = MediaDownloadPlanner.defaultSessionHeaders(record))
        val probeUrl = plan.metadataProbeUrl.takeIf(String::isNotBlank) ?: metadataProbeUrl(record)
        val spec = manualSpec(
            record = record,
            kind = PostProcessingActionKind.YtDlpMetadata,
            input = probeUrl,
            outputName = "${safeBase(record)}.metadata.json",
            mimeType = "application/json",
            requiredTools = setOf(ExternalTool.YtDlp),
            metadataOnly = true,
            extraArguments = plan.sessionHandoff.ytdlpArguments(),
        )
        return enqueueAsync(spec)
    }

    fun inspectWithFfprobe(record: MediaCaptureRecord): TermuxMediaPipelineJob = enqueueAsync(
        manualSpec(
            record = record,
            kind = PostProcessingActionKind.FfprobeInspect,
            input = record.selectedVariantUrl ?: record.sourceUrl,
            outputName = "${safeBase(record)}.ffprobe.json",
            mimeType = "application/json",
            requiredTools = setOf(ExternalTool.Ffprobe),
            metadataOnly = true,
        ),
    )

    fun downloadWithYtDlp(
        record: MediaCaptureRecord,
        variants: List<MediaVariant> = emptyList(),
        selection: MediaTrackSelection = MediaTrackSelection(videoVariantId = record.selectedVariantId),
        destination: String = "",
    ): TermuxMediaPipelineJob {
        val plan = planner.plan(
            capture = record,
            variants = variants,
            selection = selection,
            sessionHeaders = MediaDownloadPlanner.defaultSessionHeaders(record) + sessionHintHeaders(record),
        )
        val outputName = "${safeBase(record)}.mp4"
        val spec = manualSpec(
            record = record,
            kind = PostProcessingActionKind.YtDlpDownload,
            input = plan.metadataProbeUrl,
            outputName = outputName,
            mimeType = "video/mp4",
            requiredTools = setOf(ExternalTool.YtDlp, ExternalTool.Ffmpeg, ExternalTool.Ffprobe),
            formatSelector = plan.ytDlpFormatSelector ?: "bestvideo+bestaudio/best",
            extraArguments = plan.sessionHandoff.ytdlpArguments(),
            destinationUri = destination.takeIf { it.startsWith("content://") || it.startsWith("xdm://") },
        )
        return enqueueAsync(spec)
    }

    fun convert(record: MediaCaptureRecord, preset: ConversionPreset, destination: String = ""): TermuxMediaPipelineJob {
        val base = safeBase(record)
        val (kind, name, mime) = when (preset) {
            ConversionPreset.AudioExtract -> Triple(PostProcessingActionKind.ExtractAudio, "$base.m4a", "audio/mp4")
            ConversionPreset.VideoFastStart -> Triple(PostProcessingActionKind.RemuxFastStart, "$base.faststart.mp4", "video/mp4")
            ConversionPreset.ArchiveExtract -> Triple(PostProcessingActionKind.FfmpegRemux, "$base.remux.mkv", "video/x-matroska")
            ConversionPreset.CustomCommand, ConversionPreset.None -> Triple(PostProcessingActionKind.FfmpegRemux, "$base.remux.mp4", "video/mp4")
        }
        return enqueueAsync(
            manualSpec(
                record = record,
                kind = kind,
                input = record.selectedVariantUrl ?: record.sourceUrl,
                outputName = name,
                mimeType = mime,
                requiredTools = setOf(ExternalTool.Ffmpeg, ExternalTool.Ffprobe),
                destinationUri = destination.takeIf { it.startsWith("content://") || it.startsWith("xdm://") },
            ),
        )
    }

    suspend fun enqueue(spec: PostProcessingJobSpec, durableClaim: Boolean = spec.ruleId != null): EnqueueOutcome {
        val now = System.currentTimeMillis()
        val jobId = "post-${UUID.randomUUID()}"
        runner.refreshStatus()
        val preflightIssue = PostProcessingExecutionPolicy.preflightIssue(spec, TermuxRunStore.status.value)
        val claimKey = spec.ruleId?.let { PostProcessingExecutionPolicy.claimKey(spec) }
        val entity = newJobEntity(spec, jobId, jobId, null, 1, claimKey, now).let { queued ->
            when {
                preflightIssue == null -> queued
                spec.kind.requiresTermux && isMutablePrerequisiteIssue(preflightIssue) -> queued.copy(
                    status = PostProcessingJobStatus.WaitingForPrerequisites.name,
                    message = "Waiting without consuming the durable claim: $preflightIssue",
                    updatedAtEpochMs = now,
                )
                else -> queued.copy(
                    status = PostProcessingJobStatus.Failed.name,
                    message = "Post-processing preflight rejected this immutable attempt: $preflightIssue",
                    updatedAtEpochMs = now,
                    finishedAtEpochMs = now,
                )
            }
        }
        val accepted = if (durableClaim && claimKey != null) {
            dao.claimAndInsert(
                PostProcessingClaimEntity(
                    claimKey = claimKey,
                    subjectId = spec.subjectId,
                    subjectType = spec.subjectType.name,
                    subjectGeneration = spec.subjectGeneration,
                    trigger = spec.trigger.name,
                    ruleId = spec.ruleId.orEmpty(),
                    actionId = spec.actionId,
                    jobId = jobId,
                    createdAtEpochMs = now,
                ),
                entity,
            )
        } else {
            dao.insertJob(entity)
            true
        }
        if (!accepted) {
            return EnqueueOutcome(false, entity.toPipelineJob(), "An identical durable post-processing claim already exists.")
        }
        if (preflightIssue != null) {
            val waiting = entity.status == PostProcessingJobStatus.WaitingForPrerequisites.name
            return EnqueueOutcome(
                true,
                entity.toPipelineJob(),
                if (waiting) "Durable attempt is waiting for verified prerequisites: $preflightIssue" else "Durable attempt recorded as failed: $preflightIssue",
            )
        }
        scope.launch { launchJob(jobId) }
        return EnqueueOutcome(true, entity.toPipelineJob(), "Queued durable post-processing job.")
    }

    fun retryLastFailed() {
        scope.launch {
            val failed = dao.latestFailedJob()
            if (failed == null) {
                statusFlow.value = statusFlow.value.copy(lastAction = "No failed durable job to retry.", updatedAtEpochMs = System.currentTimeMillis())
                return@launch
            }
            retry(failed.id)
        }
    }

    fun retry(jobId: String) {
        scope.launch {
            val failed = dao.findJob(jobId) ?: return@launch
            val retryable = failed.status in setOf(
                PostProcessingJobStatus.Failed.name,
                PostProcessingJobStatus.Cancelled.name,
                PostProcessingJobStatus.TimedOut.name,
                PostProcessingJobStatus.RecoveryRequired.name,
            )
            if (!retryable) {
                statusFlow.value = statusFlow.value.copy(
                    lastAction = "${failed.title} is ${failed.status.lowercase()} and cannot be retried.",
                    updatedAtEpochMs = System.currentTimeMillis(),
                )
                return@launch
            }
            val spec = runCatching { PostProcessingJobSpec.fromJson(failed.immutableSpecJson) }.getOrElse {
                finishFailure(failed, "Immutable retry specification is invalid: ${it.message}")
                return@launch
            }
            val generation = (dao.maxAttemptGeneration(failed.rootJobId) ?: failed.attemptGeneration) + 1
            val now = System.currentTimeMillis()
            val retryId = "post-${UUID.randomUUID()}"
            dao.insertJob(newJobEntity(spec, retryId, failed.rootJobId, failed.id, generation, null, now))
            launchJob(retryId)
        }
    }

    fun recoverPublication(jobId: String) {
        scope.launch { dao.findJob(jobId)?.let { recoverPublicationNow(it) } }
    }

    private suspend fun recoverPublicationNow(job: PostProcessingJobEntity) {
        val spec = runCatching { PostProcessingJobSpec.fromJson(job.immutableSpecJson) }.getOrElse {
            finishFailure(job, "Publication recovery cannot parse the immutable specification: ${it.message}")
            return
        }
        when {
            !job.committedOutputUri.isNullOrBlank() && job.committedBytes != null && !job.committedSha256.isNullOrBlank() -> {
                reconcileCommittedPublication(
                    job,
                    spec,
                    AndroidPostProcessingArtifactBridge.ImportedOutput(job.committedOutputUri, job.publicationDisplayName ?: spec.output.displayName, job.committedBytes, job.committedSha256),
                    artifactBridge.readText(job.metadataBridgeUri),
                    job.toolVersionsJson,
                    job.resultStdoutLength,
                    job.resultStderrLength,
                )
            }
            !job.outputBridgeUri.isNullOrBlank() -> reconcileSuccessfulJob(
                job,
                spec,
                job.outputBridgeUri,
                artifactBridge.readText(job.metadataBridgeUri),
                job.toolVersionsJson,
                job.resultStdoutLength,
                job.resultStderrLength,
            )
            else -> finishFailure(job, "No committed URI or staged output remains for publication recovery.")
        }
    }

    fun pause(jobId: String) = requestControl(jobId, TermuxProcessControlAction.Pause)
    fun resume(jobId: String) = requestControl(jobId, TermuxProcessControlAction.Resume)
    fun cancel(jobId: String) = requestControl(jobId, TermuxProcessControlAction.Cancel)
    fun forceCancel(jobId: String) = requestControl(jobId, TermuxProcessControlAction.ForceCancel)

    fun clearCompleted() {
        scope.launch {
            val cleared = dao.clearManualTerminalJobs()
            statusFlow.value = statusFlow.value.copy(
                lastAction = if (cleared == 0) {
                    "No manual terminal attempts were cleared; automatic claim history is retained to prevent duplicate execution."
                } else {
                    "Cleared $cleared manual terminal attempt(s); automatic claim history remains durable."
                },
                updatedAtEpochMs = System.currentTimeMillis(),
            )
        }
    }

    override fun routeTermuxResult(result: TermuxResultPayload) {
        scope.launch { handleResult(result) }
    }

    private suspend fun launchJobsWhosePrerequisitesAreReady() {
        dao.waitingJobs().forEach { job ->
            val spec = runCatching { PostProcessingJobSpec.fromJson(job.immutableSpecJson) }.getOrNull() ?: return@forEach
            if (PostProcessingExecutionPolicy.preflightIssue(spec, TermuxRunStore.status.value) == null) {
                launchJob(job.id)
            }
        }
    }

    private fun isMutablePrerequisiteIssue(issue: String): Boolean = issue.startsWith("Termux is not installed") ||
        issue.startsWith("Termux RUN_COMMAND permission") ||
        issue.startsWith("This action requires a successful root probe") ||
        issue.startsWith("Run a successful Termux tool and capability probe") ||
        issue.startsWith("Required Termux tools are unavailable or unverified") ||
        issue.startsWith("The probed FFmpeg build does not advertise") ||
        issue.startsWith("Waiting for the redownloaded artifact")

    private fun enqueueAsync(spec: PostProcessingJobSpec): TermuxMediaPipelineJob {
        val now = System.currentTimeMillis()
        val provisional = TermuxMediaPipelineJob(
            id = "post-pending-${UUID.randomUUID()}",
            captureId = spec.captureId,
            downloadId = spec.downloadId,
            title = spec.title,
            kind = TermuxMediaJobKind.fromAction(spec.kind.name),
            status = TermuxMediaJobStatus.Queued,
            input = spec.inputUri,
            output = spec.output.displayName,
            message = "Creating durable ${spec.kind.label} job.",
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
        )
        scope.launch {
            runCatching { enqueue(spec, durableClaim = false) }.onFailure {
                statusFlow.value = statusFlow.value.copy(lastAction = it.message ?: "Unable to create post-processing job", updatedAtEpochMs = System.currentTimeMillis())
            }
        }
        return provisional
    }

    private suspend fun launchJob(jobId: String) {
        val job = dao.findJob(jobId) ?: return
        val spec = runCatching { PostProcessingJobSpec.fromJson(job.immutableSpecJson) }.getOrElse {
            finishFailure(job, "Invalid immutable post-processing specification: ${it.message}")
            return
        }
        runner.refreshStatus()
        PostProcessingExecutionPolicy.preflightIssue(spec, TermuxRunStore.status.value)?.let { issue ->
            if (spec.kind.requiresTermux && isMutablePrerequisiteIssue(issue)) {
                dao.acknowledgeControl(
                    job.id,
                    PostProcessingJobStatus.WaitingForPrerequisites.name,
                    "Waiting without consuming the durable claim: $issue",
                    System.currentTimeMillis(),
                )
            } else {
                finishFailure(job, "Post-processing preflight failed: $issue")
            }
            return
        }
        if (dao.reserveLaunch(
                jobId = job.id,
                message = "Launch ownership reserved atomically for this durable attempt.",
                updatedAtEpochMs = System.currentTimeMillis(),
            ) == 0
        ) return
        val reservedJob = dao.findJob(job.id) ?: return
        when (spec.kind) {
            PostProcessingActionKind.VerifySha256 -> {
                runAndroidChecksum(reservedJob, spec)
                return
            }
            PostProcessingActionKind.CleanupPartials -> {
                runAndroidCleanup(reservedJob, spec)
                return
            }
            PostProcessingActionKind.MoveToFolder -> {
                runAndroidCopyPublication(reservedJob, spec)
                return
            }
            PostProcessingActionKind.RenameByPattern -> {
                runAndroidRename(reservedJob, spec)
                return
            }
            else -> Unit
        }
        if (spec.kind == PostProcessingActionKind.FixPermissionsWithRoot) {
            runCatching { artifactBridge.verifiedOriginalPath(spec.inputUri) }.getOrElse {
                finishFailure(reservedJob, "Permission repair refused before launch: ${it.message}")
                return
            }
        }
        val prepared = runCatching { artifactBridge.prepare(spec, reservedJob.id) }.getOrElse {
            finishFailure(reservedJob, "Post-processing preflight failed: ${it.message}")
            return
        }
        val preparedAt = System.currentTimeMillis()
        if (dao.attachPreparedArtifacts(
                jobId = reservedJob.id,
                status = PostProcessingJobStatus.Preparing.name,
                stagedInputPath = prepared.inputPath,
                inputBridgeUri = prepared.inputBridgeUri,
                stagedOutputPath = prepared.outputPath,
                outputBridgeUri = prepared.outputBridgeUri,
                ownerBridgeUri = prepared.runtime.ownerBridgeUri,
                progressBridgeUri = prepared.runtime.progressBridgeUri,
                metadataBridgeUri = prepared.runtime.metadataBridgeUri,
                payloadBridgeUri = null,
                message = "Input, output, progress, metadata, and owner bridges prepared; managed script is delivered through Termux stdin.",
                updatedAtEpochMs = preparedAt,
            ) == 0
        ) {
            artifactBridge.cleanupUris(listOf(prepared.inputBridgeUri, prepared.outputBridgeUri, prepared.runtime.ownerBridgeUri, prepared.runtime.progressBridgeUri, prepared.runtime.metadataBridgeUri))
            dao.findJob(reservedJob.id)?.takeIf { it.status == PostProcessingJobStatus.Cancelling.name }?.let { finishLocalCancellation(it, spec.kind.label) }
            return
        }
        val beforeLaunch = dao.findJob(reservedJob.id) ?: return
        if (beforeLaunch.requestedControl in setOf("Timeout", TermuxProcessControlAction.Cancel.name, TermuxProcessControlAction.ForceCancel.name)) {
            finishLocalCancellation(beforeLaunch, spec.kind.label)
            return
        }
        val token = PostProcessingExecutionPolicy.processToken(reservedJob.id, reservedJob.attemptGeneration, UUID.randomUUID().toString())
        val timeoutAt = preparedAt + spec.timeoutSeconds * 1000L
        if (dao.reserveProcessOwnership(
                jobId = reservedJob.id,
                processToken = token,
                timeoutAtEpochMs = timeoutAt,
                startedAtEpochMs = preparedAt,
                message = "Exact process token reserved durably before asking Termux to launch.",
                updatedAtEpochMs = System.currentTimeMillis(),
            ) == 0
        ) {
            artifactBridge.cleanupUris(listOf(prepared.inputBridgeUri, prepared.outputBridgeUri, prepared.runtime.ownerBridgeUri, prepared.runtime.progressBridgeUri, prepared.runtime.metadataBridgeUri))
            return
        }
        val owner = TermuxRunOwner(reservedJob.id, token, timeoutAt, prepared.runtime)
        val command = commandFor(spec, prepared)
        val immediatelyBeforeLaunch = dao.findJob(reservedJob.id) ?: return
        if (immediatelyBeforeLaunch.requestedControl in setOf("Timeout", TermuxProcessControlAction.Cancel.name, TermuxProcessControlAction.ForceCancel.name)) {
            finishLocalCancellation(immediatelyBeforeLaunch, spec.kind.label)
            return
        }
        val launch = runner.run(command, owner = owner)
        if (!launch.started) {
            cleanupJobBridges(dao.findJob(reservedJob.id))
            finishFailure(reservedJob, "Termux launch failed: ${launch.error}")
            return
        }
        val afterLaunch = dao.findJob(reservedJob.id) ?: reservedJob
        val cancelAfterLaunch = afterLaunch.requestedControl in setOf("Timeout", TermuxProcessControlAction.Cancel.name, TermuxProcessControlAction.ForceCancel.name)
        val attached = dao.attachRun(
            jobId = reservedJob.id,
            status = if (cancelAfterLaunch) PostProcessingJobStatus.Cancelling.name else PostProcessingJobStatus.Running.name,
            runId = launch.runId,
            executionId = launch.executionId,
            processToken = token,
            timeoutAtEpochMs = timeoutAt,
            startedAtEpochMs = preparedAt,
            message = if (cancelAfterLaunch) "Launch completed after cancellation was recorded; signalling the exact new owner immediately." else "Managed Termux job started with durable owner token and timeout.",
            updatedAtEpochMs = System.currentTimeMillis(),
        )
        if (attached == 0) {
            dao.acknowledgeControl(reservedJob.id, PostProcessingJobStatus.RecoveryRequired.name, "Termux started, but durable run ownership could not be attached; recovery must validate the owner file.", System.currentTimeMillis())
            return
        }
        val attachedJob = dao.findJob(reservedJob.id) ?: reservedJob
        if (cancelAfterLaunch) {
            requestControlNow(attachedJob, owner, TermuxProcessControlAction.Cancel, timedOut = afterLaunch.requestedControl == "Timeout")
        } else {
            monitorJob(reservedJob.id)
        }
    }

    private suspend fun runAndroidCleanup(job: PostProcessingJobEntity, spec: PostProcessingJobSpec) {
        val now = System.currentTimeMillis()
        if (dao.startLocalJob(job.id, PostProcessingJobStatus.Running.name, now + spec.timeoutSeconds * 1000L, now, "Cleaning the exact verified XDM-owned partial artifact.") == 0) {
            dao.findJob(job.id)?.takeIf { it.status == PostProcessingJobStatus.Cancelling.name }?.let { finishLocalCancellation(it, "Owned-partial cleanup") }
            return
        }
        runCatching { artifactBridge.cleanupOwnedPartials(spec.inputUri) }
            .onSuccess { outcome ->
                dao.markPublicationReconciled(job.id, outcome.code, outcome.message, System.currentTimeMillis())
                dao.finishJob(
                    jobId = job.id,
                    status = PostProcessingJobStatus.Completed.name,
                    finalOutputUri = spec.inputUri,
                    actualSha256 = null,
                    metadataJson = JSONObject().put("outcome", outcome.code).put("affectedArtifacts", JSONArray(outcome.affectedArtifacts)).toString(),
                    toolVersionsJson = "{}",
                    resultStdoutLength = 0,
                    resultStderrLength = 0,
                    message = outcome.message,
                    finishedAtEpochMs = System.currentTimeMillis(),
                )
            }
            .onFailure { error -> finishFailure(dao.findJob(job.id) ?: job, "Owned-partial cleanup failed closed: ${error.message}") }
    }


    private suspend fun runAndroidRename(job: PostProcessingJobEntity, spec: PostProcessingJobSpec) {
        val now = System.currentTimeMillis()
        val timeoutAt = now + spec.timeoutSeconds * 1000L
        if (dao.startLocalJob(
                jobId = job.id,
                status = PostProcessingJobStatus.Running.name,
                timeoutAtEpochMs = timeoutAt,
                startedAtEpochMs = now,
                message = "Renaming the exact original artifact through its owning Android provider or filesystem directory.",
            ) == 0
        ) {
            dao.findJob(job.id)?.takeIf { it.status == PostProcessingJobStatus.Cancelling.name }?.let { finishLocalCancellation(it, "Rename") }
            return
        }
        val current = dao.findJob(job.id) ?: return
        if (current.requestedControl in setOf("Timeout", TermuxProcessControlAction.Cancel.name, TermuxProcessControlAction.ForceCancel.name)) {
            finishLocalCancellation(current, "Rename")
            return
        }
        runCatching { artifactBridge.renameOriginal(spec) }
            .onSuccess { renamed ->
                reconcileRenamedArtifact(spec, renamed)
                dao.markPublicationReconciled(job.id, "rename_reconciled", "Original artifact renamed and owner metadata reconciled.", System.currentTimeMillis())
                dao.finishJob(
                    jobId = job.id,
                    status = PostProcessingJobStatus.Completed.name,
                    finalOutputUri = renamed.finalUri,
                    actualSha256 = renamed.sha256,
                    metadataJson = JSONObject().put("renamedTo", renamed.displayName).put("bytes", renamed.bytes).toString(),
                    toolVersionsJson = "{}",
                    resultStdoutLength = 0,
                    resultStderrLength = 0,
                    message = "Renamed the exact original artifact to ${renamed.displayName} and verified its size and SHA-256.",
                    finishedAtEpochMs = System.currentTimeMillis(),
                )
            }
            .onFailure { error -> finishFailure(dao.findJob(job.id) ?: job, "Rename failed closed without publishing a copy: ${error.message}") }
    }

    private suspend fun reconcileRenamedArtifact(spec: PostProcessingJobSpec, renamed: AndroidPostProcessingArtifactBridge.ImportedOutput) {
        spec.downloadId?.let { downloadId ->
            repository.findDownload(downloadId)?.let { download ->
                repository.save(
                    download.copy(
                        destinationUri = renamed.finalUri,
                        fileName = renamed.displayName,
                        bytesReceived = renamed.bytes,
                        totalBytes = renamed.bytes,
                        updatedAtEpochMs = System.currentTimeMillis(),
                    ),
                )
            }
        }
        spec.captureId?.let { captureId ->
            repository.findMediaCapture(captureId)?.let { capture ->
                repository.saveMediaCapture(
                    capture.copy(
                        fileName = renamed.displayName,
                        selectedVariantUrl = renamed.finalUri,
                        updatedAtEpochMs = System.currentTimeMillis(),
                    ),
                )
            }
        }
    }

    private suspend fun runAndroidCopyPublication(job: PostProcessingJobEntity, spec: PostProcessingJobSpec) {
        val prepared = runCatching { artifactBridge.prepare(spec, job.id) }.getOrElse {
            finishFailure(job, "Android move/rename preflight failed: ${it.message}")
            return
        }
        val now = System.currentTimeMillis()
        val timeoutAt = now + spec.timeoutSeconds * 1000L
        if (dao.attachPreparedArtifacts(
                jobId = job.id,
                status = PostProcessingJobStatus.Preparing.name,
                stagedInputPath = prepared.inputPath,
                inputBridgeUri = prepared.inputBridgeUri,
                stagedOutputPath = prepared.outputPath,
                outputBridgeUri = prepared.outputBridgeUri,
                ownerBridgeUri = prepared.runtime.ownerBridgeUri,
                progressBridgeUri = prepared.runtime.progressBridgeUri,
                metadataBridgeUri = prepared.runtime.metadataBridgeUri,
                payloadBridgeUri = null,
                message = "Android is preparing a transactional publication bridge.",
                updatedAtEpochMs = now,
            ) == 0
        ) {
            artifactBridge.cleanupUris(listOf(prepared.inputBridgeUri, prepared.outputBridgeUri, prepared.runtime.ownerBridgeUri, prepared.runtime.progressBridgeUri, prepared.runtime.metadataBridgeUri))
            dao.findJob(job.id)?.takeIf { it.status == PostProcessingJobStatus.Cancelling.name }?.let { finishLocalCancellation(it, spec.kind.label) }
            return
        }
        if (dao.startLocalJob(
                jobId = job.id,
                status = PostProcessingJobStatus.Running.name,
                timeoutAtEpochMs = timeoutAt,
                startedAtEpochMs = now,
                message = "Android is copying the verified source into transactional staging.",
            ) == 0
        ) {
            dao.findJob(job.id)?.takeIf { it.status == PostProcessingJobStatus.Cancelling.name }?.let { finishLocalCancellation(it, spec.kind.label) }
            return
        }
        val startingGeneration = dao.findJob(job.id)?.controlGeneration ?: job.controlGeneration
        runCatching {
            artifactBridge.copyInputToOutput(
                prepared = prepared,
                onProgress = { bytes, total ->
                    val percent = if (total > 0L) ((bytes * 100L) / total).toInt().coerceIn(0, 100) else 0
                    dao.updateProgress(job.id, PostProcessingJobStatus.Running.name, percent, bytes, total, "Copying verified artifact into transactional staging.", System.currentTimeMillis())
                },
                shouldCancel = {
                    val current = dao.findJob(job.id)
                    current == null ||
                        System.currentTimeMillis() >= timeoutAt ||
                        current.controlGeneration != startingGeneration ||
                        current.requestedControl in setOf("Timeout", TermuxProcessControlAction.Cancel.name, TermuxProcessControlAction.ForceCancel.name)
                },
            )
            reconcileSuccessfulJob(
                dao.findJob(job.id) ?: job,
                spec,
                prepared.outputBridgeUri,
                "",
                "{}",
                0,
                0,
            )
        }.onFailure { error ->
            when (error) {
                is AndroidPostProcessingArtifactBridge.LocalOperationCancelledException -> dao.findJob(job.id)?.let { finishLocalCancellation(it, spec.kind.label) }
                else -> dao.acknowledgeControl(job.id, PostProcessingJobStatus.RecoveryRequired.name, "Android copy completed incompletely and requires publication recovery: ${error.message}", System.currentTimeMillis())
            }
        }
    }

    private suspend fun runAndroidChecksum(job: PostProcessingJobEntity, spec: PostProcessingJobSpec) {
        val started = System.currentTimeMillis()
        val timeoutAt = started + spec.timeoutSeconds * 1000L
        if (dao.startLocalJob(
                jobId = job.id,
                status = PostProcessingJobStatus.Running.name,
                timeoutAtEpochMs = timeoutAt,
                startedAtEpochMs = started,
                message = "Computing SHA-256 through ContentResolver with durable cancellation.",
            ) == 0
        ) {
            dao.findJob(job.id)?.takeIf { it.status == PostProcessingJobStatus.Cancelling.name }?.let { finishLocalCancellation(it, "SHA-256 verification") }
            return
        }
        val startingGeneration = dao.findJob(job.id)?.controlGeneration ?: job.controlGeneration
        try {
            val actual = artifactBridge.checksumInput(
                inputUri = spec.inputUri,
                onProgress = { bytes, total ->
                    val percent = total?.takeIf { it > 0L }?.let { ((bytes * 100L) / it).toInt().coerceIn(0, 100) } ?: 0
                    dao.updateProgress(
                        jobId = job.id,
                        status = PostProcessingJobStatus.Running.name,
                        progressPercent = percent,
                        progressBytes = bytes,
                        progressTotalBytes = total,
                        message = "Verifying SHA-256 • $percent%",
                        updatedAtEpochMs = System.currentTimeMillis(),
                    )
                },
                shouldCancel = {
                    val current = dao.findJob(job.id)
                    current == null ||
                        System.currentTimeMillis() >= timeoutAt ||
                        (current.controlGeneration > startingGeneration && current.requestedControl in setOf("Cancel", "Timeout"))
                },
            )
            val current = dao.findJob(job.id) ?: return
            if (current.controlGeneration > startingGeneration && current.requestedControl in setOf("Cancel", "Timeout")) {
                finishLocalCancellation(current, "SHA-256 verification")
                return
            }
            val expected = requireNotNull(PostProcessingExecutionPolicy.normalizedSha256(spec.expectedSha256))
            val status = if (actual == expected) PostProcessingJobStatus.Completed else PostProcessingJobStatus.Failed
            dao.finishJob(
                jobId = job.id,
                status = status.name,
                finalOutputUri = spec.inputUri,
                actualSha256 = actual,
                metadataJson = JSONObject().put("sha256", actual).toString(),
                toolVersionsJson = "{}",
                resultStdoutLength = 0,
                resultStderrLength = 0,
                message = if (actual == expected) "SHA-256 matched the explicit expectation." else "SHA-256 did not match the explicit expectation.",
                finishedAtEpochMs = System.currentTimeMillis(),
            )
        } catch (_: AndroidPostProcessingArtifactBridge.ChecksumCancelledException) {
            dao.findJob(job.id)?.let { finishLocalCancellation(it, "SHA-256 verification") }
        } catch (error: Throwable) {
            finishFailure(dao.findJob(job.id) ?: job, "SHA-256 verification failed: ${error.message}")
        }
    }

    private suspend fun finishLocalCancellation(job: PostProcessingJobEntity, operation: String) {
        val timedOut = job.requestedControl == "Timeout" || (job.timeoutAtEpochMs?.let { System.currentTimeMillis() >= it } == true)
        dao.finishJob(
            jobId = job.id,
            status = if (timedOut) PostProcessingJobStatus.TimedOut.name else PostProcessingJobStatus.Cancelled.name,
            finalOutputUri = null,
            actualSha256 = null,
            metadataJson = null,
            toolVersionsJson = "{}",
            resultStdoutLength = 0,
            resultStderrLength = 0,
            message = if (timedOut) "$operation timed out before publication." else "$operation cancelled before completion.",
            finishedAtEpochMs = System.currentTimeMillis(),
        )
        cleanupJobBridges(dao.findJob(job.id))
    }

    private suspend fun recoverActiveJob(job: PostProcessingJobEntity) {
        val owner = ownerFrom(job)
        if (owner == null) {
            dao.acknowledgeControl(job.id, PostProcessingJobStatus.RecoveryRequired.name, "Interrupted job has no durable process owner.", System.currentTimeMillis())
            return
        }
        if (job.timeoutAtEpochMs != null && System.currentTimeMillis() >= job.timeoutAtEpochMs) {
            requestControlNow(job, owner, TermuxProcessControlAction.Cancel, timedOut = true)
            return
        }
        runner.run(
            XdmTermuxCommand.OwnedProcessControl(owner.runtime.ownerShellPath, owner.jobId, owner.processToken, TermuxProcessControlAction.Probe),
            owner = owner,
        )
        monitorJob(job.id)
    }

    private fun requestControl(jobId: String, action: TermuxProcessControlAction) {
        scope.launch {
            val job = dao.findJob(jobId) ?: return@launch
            if (job.status == PostProcessingJobStatus.WaitingForPrerequisites.name) {
                if (action in setOf(TermuxProcessControlAction.Cancel, TermuxProcessControlAction.ForceCancel)) {
                    dao.finishJob(job.id, PostProcessingJobStatus.Cancelled.name, null, null, null, "{}", 0, 0, "Waiting post-processing attempt cancelled before launch.", System.currentTimeMillis())
                } else {
                    dao.acknowledgeControl(job.id, job.status, "The job is waiting for verified prerequisites and has no process to ${action.name.lowercase()}.", System.currentTimeMillis())
                }
                return@launch
            }
            if (job.status in setOf(PostProcessingJobStatus.Queued.name, PostProcessingJobStatus.Preparing.name) &&
                action in setOf(TermuxProcessControlAction.Cancel, TermuxProcessControlAction.ForceCancel)
            ) {
                dao.requestControl(
                    job.id,
                    PostProcessingJobStatus.Cancelling.name,
                    TermuxProcessControlAction.Cancel.name,
                    "Cancellation recorded before process ownership or local execution began; launch will abort or signal the exact owner immediately.",
                    System.currentTimeMillis(),
                )
                return@launch
            }
            val localKind = PostProcessingActionKind.entries.firstOrNull { it.name == job.kind }?.takeUnless { it.requiresTermux }
            if (localKind != null) {
                if (action != TermuxProcessControlAction.Cancel && action != TermuxProcessControlAction.ForceCancel) {
                    dao.acknowledgeControl(job.id, job.status, "${localKind.label} is Android-owned and supports Cancel, not ${action.name}.", System.currentTimeMillis())
                    return@launch
                }
                if (localKind in setOf(PostProcessingActionKind.CleanupPartials, PostProcessingActionKind.RenameByPattern) &&
                    job.status == PostProcessingJobStatus.Running.name
                ) {
                    dao.acknowledgeControl(
                        job.id,
                        job.status,
                        "${localKind.label} has crossed its atomic side-effect boundary and cannot be cancelled safely. The verified result will be reconciled before controls return.",
                        System.currentTimeMillis(),
                    )
                    return@launch
                }
                dao.requestControl(
                    job.id,
                    PostProcessingJobStatus.Cancelling.name,
                    TermuxProcessControlAction.Cancel.name,
                    "Cancellation recorded durably; the Android-owned operation will stop before completion or publication.",
                    System.currentTimeMillis(),
                )
                return@launch
            }
            val owner = ownerFrom(job)
            if (owner == null) {
                dao.acknowledgeControl(job.id, PostProcessingJobStatus.RecoveryRequired.name, "Cannot ${action.name.lowercase()} without a durable process owner.", System.currentTimeMillis())
                return@launch
            }
            requestControlNow(job, owner, action, timedOut = false)
        }
    }

    private suspend fun requestControlNow(job: PostProcessingJobEntity, owner: TermuxRunOwner, action: TermuxProcessControlAction, timedOut: Boolean) {
        val requestedStatus = when (action) {
            TermuxProcessControlAction.Pause -> PostProcessingJobStatus.Paused
            TermuxProcessControlAction.Resume, TermuxProcessControlAction.Probe -> PostProcessingJobStatus.Running
            TermuxProcessControlAction.Cancel, TermuxProcessControlAction.ForceCancel -> PostProcessingJobStatus.Cancelling
        }
        dao.requestControl(job.id, requestedStatus.name, if (timedOut) "Timeout" else action.name, "${action.name} requested durably before signalling the owned process group.", System.currentTimeMillis())
        val launch = runner.run(
            XdmTermuxCommand.OwnedProcessControl(owner.runtime.ownerShellPath, owner.jobId, owner.processToken, action),
            owner = owner,
        )
        if (!launch.started) {
            dao.acknowledgeControl(job.id, PostProcessingJobStatus.RecoveryRequired.name, "Control request could not reach Termux: ${launch.error}", System.currentTimeMillis())
        }
    }

    private suspend fun handleResult(result: TermuxResultPayload) {
        val job = result.jobId?.let { dao.findJob(it) } ?: dao.findJobByRunId(result.runId) ?: return
        if (result.processToken != null && job.processToken != null && result.processToken != job.processToken) {
            dao.acknowledgeControl(job.id, PostProcessingJobStatus.RecoveryRequired.name, "Rejected a Termux result with the wrong owner token.", System.currentTimeMillis())
            return
        }
        if (result.operation.startsWith("post_process_control_")) {
            handleControlResult(job, result)
            return
        }
        if (job.runId != result.runId) return
        val current = dao.findJob(job.id) ?: job
        val timedOut = current.requestedControl == "Timeout" || (current.timeoutAtEpochMs?.let { System.currentTimeMillis() >= it } == true)
        val cancelled = current.requestedControl in setOf(TermuxProcessControlAction.Cancel.name, TermuxProcessControlAction.ForceCancel.name)
        if (result.error.isNotBlank() || result.exitCode != 0) {
            val status = when {
                timedOut -> PostProcessingJobStatus.TimedOut
                cancelled -> PostProcessingJobStatus.Cancelled
                else -> PostProcessingJobStatus.Failed
            }
            dao.finishJob(
                jobId = current.id,
                status = status.name,
                finalOutputUri = null,
                actualSha256 = null,
                metadataJson = PostProcessingExecutionPolicy.sanitizeMetadataJson(artifactBridge.readText(current.metadataBridgeUri)).takeIf(String::isNotBlank),
                toolVersionsJson = parseToolVersions(result.stdout),
                resultStdoutLength = result.stdoutOriginalLength,
                resultStderrLength = result.stderrOriginalLength,
                message = buildFailureMessage(result, status),
                finishedAtEpochMs = System.currentTimeMillis(),
            )
            cleanupJobBridges(dao.findJob(current.id))
            return
        }
        val spec = PostProcessingJobSpec.fromJson(current.immutableSpecJson)
        val metadata = artifactBridge.readText(current.metadataBridgeUri)
        reconcileSuccessfulJob(
            current,
            spec,
            current.outputBridgeUri,
            metadata,
            parseToolVersions(result.stdout),
            result.stdoutOriginalLength,
            result.stderrOriginalLength,
        )
    }

    private suspend fun handleControlResult(job: PostProcessingJobEntity, result: TermuxResultPayload) {
        val action = result.operation.substringAfterLast('_').uppercase(Locale.US)
        val now = System.currentTimeMillis()
        val ownerSnapshot = TermuxOwnerSnapshot.parse(artifactBridge.readText(job.ownerBridgeUri))
        if (result.stdout.contains("\tprobe\tfinished\t")) {
            if (ownerSnapshot?.finished == true) {
                reconcileOwnerCompletion(job, ownerSnapshot)
            } else {
                dao.acknowledgeControl(job.id, PostProcessingJobStatus.RecoveryRequired.name, "Probe reported finished but the durable owner record was incomplete.", now)
            }
            return
        }
        if (result.exitCode != 0 || result.error.isNotBlank()) {
            val forceRequired = result.exitCode == 75 || result.stdout.contains("force_required")
            dao.acknowledgeControl(
                job.id,
                if (forceRequired) PostProcessingJobStatus.Cancelling.name else PostProcessingJobStatus.RecoveryRequired.name,
                if (forceRequired) "Graceful cancellation did not finish in ten seconds; force cancellation is available only for this validated owner token, PID, and process start time." else "Process control failed closed: ${safeResultDetail(result)}",
                now,
            )
            return
        }
        when {
            action.contains("PAUSE") -> dao.acknowledgeControl(job.id, PostProcessingJobStatus.Paused.name, "Validated owned process paused.", now)
            action.contains("RESUME") -> dao.acknowledgeControl(job.id, PostProcessingJobStatus.Running.name, "Validated owned process resumed.", now)
            action.contains("FORCE") -> {
                dao.finishJob(job.id, PostProcessingJobStatus.Cancelled.name, null, null, null, "{}", result.stdoutOriginalLength, result.stderrOriginalLength, "Exact owned process force-cancelled after bounded graceful cancellation.", now)
                cleanupJobBridges(dao.findJob(job.id))
            }
            action.contains("CANCEL") -> dao.acknowledgeControl(job.id, PostProcessingJobStatus.Cancelling.name, "Graceful cancellation accepted; awaiting the durable owner completion record.", now)
            action.contains("PROBE") && result.stdout.contains("\tprobe\talive\t") -> dao.acknowledgeControl(job.id, if (job.status == PostProcessingJobStatus.Paused.name) PostProcessingJobStatus.Paused.name else PostProcessingJobStatus.Running.name, "Recovered the exact owner token, PID, process start time, and command identity.", now)
            action.contains("PROBE") -> dao.acknowledgeControl(job.id, PostProcessingJobStatus.RecoveryRequired.name, "Probe returned no typed alive or finished outcome.", now)
        }
    }

    private suspend fun reconcileSuccessfulJob(
        job: PostProcessingJobEntity,
        spec: PostProcessingJobSpec,
        outputBridgeUri: String?,
        metadata: String,
        toolVersionsJson: String,
        stdoutLength: Int,
        stderrLength: Int,
    ) {
        val latest = dao.findJob(job.id) ?: return
        val latestStatus = PostProcessingJobStatus.entries.firstOrNull { it.name == latest.status }
        if (latestStatus?.terminal == true) return
        val cancellationRequested = latest.requestedControl in setOf(
            "Timeout",
            TermuxProcessControlAction.Cancel.name,
            TermuxProcessControlAction.ForceCancel.name,
        )
        if (cancellationRequested && latest.publicationState != PostProcessingPublicationState.Committed.name) {
            val timedOut = latest.requestedControl == "Timeout"
            dao.finishJob(
                jobId = latest.id,
                status = if (timedOut) PostProcessingJobStatus.TimedOut.name else PostProcessingJobStatus.Cancelled.name,
                finalOutputUri = null,
                actualSha256 = null,
                metadataJson = PostProcessingExecutionPolicy.sanitizeMetadataJson(metadata).takeIf(String::isNotBlank),
                toolVersionsJson = toolVersionsJson,
                resultStdoutLength = stdoutLength,
                resultStderrLength = stderrLength,
                message = if (timedOut) "Timeout won before destination commit; no output was published." else "Cancellation won before destination commit; no output was published.",
                finishedAtEpochMs = System.currentTimeMillis(),
            )
            cleanupJobBridges(dao.findJob(latest.id))
            return
        }
        val sanitizedMetadata = PostProcessingExecutionPolicy.sanitizeMetadataJson(metadata)
        runCatching {
            updateMediaMetadata(spec, sanitizedMetadata)
            when (spec.resultMode) {
                PostProcessingResultMode.MetadataOnly -> {
                    dao.markPublicationReconciled(job.id, "metadata_applied", "Metadata sanitized and applied to the owning record.", System.currentTimeMillis())
                    dao.finishJob(
                        jobId = job.id,
                        status = PostProcessingJobStatus.Completed.name,
                        finalOutputUri = spec.inputUri,
                        actualSha256 = null,
                        metadataJson = sanitizedMetadata.takeIf(String::isNotBlank),
                        toolVersionsJson = toolVersionsJson,
                        resultStdoutLength = stdoutLength,
                        resultStderrLength = stderrLength,
                        message = "Metadata sanitized and applied to the owning media record.",
                        finishedAtEpochMs = System.currentTimeMillis(),
                    )
                    cleanupJobBridges(dao.findJob(job.id))
                }
                PostProcessingResultMode.SideEffectOnly -> {
                    dao.markPublicationReconciled(job.id, "side_effect_completed", "Typed side effect completed against the verified original artifact.", System.currentTimeMillis())
                    dao.finishJob(
                        job.id,
                        PostProcessingJobStatus.Completed.name,
                        spec.inputUri,
                        null,
                        sanitizedMetadata.takeIf(String::isNotBlank),
                        toolVersionsJson,
                        stdoutLength,
                        stderrLength,
                        "Typed side effect completed against the verified original artifact; no output publication was attempted.",
                        System.currentTimeMillis(),
                    )
                    cleanupJobBridges(dao.findJob(job.id))
                }
                PostProcessingResultMode.InPlace -> error("In-place jobs must use their Android-owned execution path")
                PostProcessingResultMode.OutputArtifact -> {
                    val bridgeUri = outputBridgeUri?.takeIf(String::isNotBlank) ?: error("Output-producing job has no durable output bridge")
                    val current = dao.findJob(job.id) ?: job
                    val plan = if (
                        current.publicationState in setOf(PostProcessingPublicationState.Prepared.name, PostProcessingPublicationState.Committed.name) &&
                        !current.publicationDisplayName.isNullOrBlank() && current.publicationExpectedBytes != null && !current.publicationExpectedSha256.isNullOrBlank()
                    ) {
                        AndroidPostProcessingArtifactBridge.PublicationPlan(
                            current.publicationDisplayName,
                            current.publicationExpectedBytes,
                            current.publicationExpectedSha256,
                            bridgeUri,
                        )
                    } else {
                        artifactBridge.preparePublication(spec, job.id, bridgeUri).also { prepared ->
                            check(
                                dao.markPublicationPrepared(
                                    job.id,
                                    prepared.displayName,
                                    prepared.expectedBytes,
                                    prepared.expectedSha256,
                                    "Publication prepared with exact name, size, and digest before destination commit.",
                                    System.currentTimeMillis(),
                                ) > 0,
                            ) { "Unable to persist the publication preparation boundary" }
                        }
                    }
                    val afterPrepare = dao.findJob(job.id) ?: job
                    val imported = if (
                        afterPrepare.publicationState == PostProcessingPublicationState.Committed.name &&
                        !afterPrepare.committedOutputUri.isNullOrBlank() && afterPrepare.committedBytes != null && !afterPrepare.committedSha256.isNullOrBlank()
                    ) {
                        AndroidPostProcessingArtifactBridge.ImportedOutput(
                            afterPrepare.committedOutputUri,
                            afterPrepare.publicationDisplayName ?: plan.displayName,
                            afterPrepare.committedBytes,
                            afterPrepare.committedSha256,
                        )
                    } else {
                        artifactBridge.publishPrepared(spec, job.id, plan).also { committed ->
                            check(
                                dao.markPublicationCommitted(
                                    job.id,
                                    committed.finalUri,
                                    committed.bytes,
                                    committed.sha256,
                                    "Destination commit persisted before repository reconciliation and bridge cleanup.",
                                    System.currentTimeMillis(),
                                ) > 0,
                            ) { "Unable to persist the committed publication boundary" }
                        }
                    }
                    reconcileCommittedPublication(job, spec, imported, sanitizedMetadata, toolVersionsJson, stdoutLength, stderrLength)
                }
            }
        }.onFailure { error ->
            dao.acknowledgeControl(
                job.id,
                PostProcessingJobStatus.RecoveryRequired.name,
                "Execution succeeded, but durable Android reconciliation requires recovery: ${error.message}",
                System.currentTimeMillis(),
            )
            artifactBridge.cleanupUris(listOf(job.ownerBridgeUri, job.progressBridgeUri))
        }
    }

    private suspend fun reconcileCommittedPublication(
        job: PostProcessingJobEntity,
        spec: PostProcessingJobSpec,
        imported: AndroidPostProcessingArtifactBridge.ImportedOutput,
        metadata: String,
        toolVersionsJson: String,
        stdoutLength: Int,
        stderrLength: Int,
    ) {
        val sanitizedMetadata = PostProcessingExecutionPolicy.sanitizeDurableMetadata(metadata)
        reconcilePublishedOutput(spec, imported)
        if (spec.output.deleteOriginalAfterPublish) artifactBridge.deleteOriginalAfterPublish(spec)
        dao.markPublicationReconciled(job.id, "publication_reconciled", "Committed output reconciled with its download/capture owner.", System.currentTimeMillis())
        dao.finishJob(
            jobId = job.id,
            status = PostProcessingJobStatus.Completed.name,
            finalOutputUri = imported.finalUri,
            actualSha256 = imported.sha256,
            metadataJson = sanitizedMetadata.takeIf(String::isNotBlank),
            toolVersionsJson = toolVersionsJson,
            resultStdoutLength = stdoutLength,
            resultStderrLength = stderrLength,
            message = "Output verified, committed, durably recorded, and reconciled at ${imported.finalUri}.",
            finishedAtEpochMs = System.currentTimeMillis(),
        )
        val finished = dao.findJob(job.id)
        artifactBridge.deleteBridgeAfterReconciliation(finished?.outputBridgeUri)
        cleanupJobBridges(finished?.copy(outputBridgeUri = null))
    }

    private suspend fun reconcilePublishedOutput(spec: PostProcessingJobSpec, imported: AndroidPostProcessingArtifactBridge.ImportedOutput) {
        spec.downloadId?.let { downloadId ->
            if (spec.output.deleteOriginalAfterPublish) {
                repository.findDownload(downloadId)?.let { download ->
                    repository.save(
                        download.copy(
                            destinationUri = imported.finalUri,
                            fileName = imported.displayName,
                            bytesReceived = imported.bytes,
                            totalBytes = imported.bytes,
                            state = DownloadState.Completed,
                            updatedAtEpochMs = System.currentTimeMillis(),
                        ),
                    )
                }
            }
        }
        spec.captureId?.let { captureId ->
            val capture = repository.findMediaCapture(captureId) ?: return@let
            val variant = MediaVariant(
                id = "$captureId-post-${PostProcessingExecutionPolicy.sha256(imported.finalUri).take(12)}",
                captureId = captureId,
                url = imported.finalUri,
                kind = MediaVariantKind.Primary,
                mimeType = spec.output.mimeType,
                position = 0,
                displayLabel = "Processed • ${imported.displayName}",
            )
            repository.saveMediaVariants(listOf(variant))
            repository.saveMediaCapture(
                capture.copy(
                    status = MediaCaptureStatus.MetadataReady,
                    mimeType = spec.output.mimeType,
                    fileName = imported.displayName,
                    selectedVariantId = variant.id,
                    selectedVariantUrl = imported.finalUri,
                    variantCount = (capture.variantCount + 1).coerceAtLeast(1),
                    updatedAtEpochMs = System.currentTimeMillis(),
                    resolutionStatus = MediaResolutionStatus.Resolved,
                ),
            )
        }
    }

    private suspend fun updateMediaMetadata(spec: PostProcessingJobSpec, raw: String) {
        val captureId = spec.captureId ?: return
        if (raw.isBlank()) return
        val json = JSONObject(raw)
        val capture = repository.findMediaCapture(captureId) ?: return
        when (spec.kind) {
            PostProcessingActionKind.FfprobeInspect,
            PostProcessingActionKind.RemuxFastStart,
            PostProcessingActionKind.ExtractAudio,
            PostProcessingActionKind.FfmpegRemux -> updateFromFfprobe(capture, json)
            PostProcessingActionKind.YtDlpMetadata,
            PostProcessingActionKind.YtDlpDownload -> updateFromYtDlp(capture, json)
            else -> Unit
        }
    }

    private suspend fun updateFromFfprobe(capture: MediaCaptureRecord, json: JSONObject) {
        val format = json.optJSONObject("format")
        val streams = json.optJSONArray("streams") ?: JSONArray()
        val codecs = buildList {
            repeat(streams.length()) { index ->
                streams.optJSONObject(index)?.optString("codec_name")?.takeIf(String::isNotBlank)?.let(::add)
            }
        }.distinct().joinToString(", ").takeIf(String::isNotBlank)
        val durationMs = format?.optString("duration")?.toDoubleOrNull()?.times(1000.0)?.toLong()
        repository.saveMediaCapture(
            capture.copy(
                status = MediaCaptureStatus.MetadataReady,
                container = format?.optString("format_name")?.takeIf(String::isNotBlank) ?: capture.container,
                codecs = codecs ?: capture.codecs,
                durationMs = durationMs ?: capture.durationMs,
                updatedAtEpochMs = System.currentTimeMillis(),
            ),
        )
    }

    private suspend fun updateFromYtDlp(capture: MediaCaptureRecord, json: JSONObject) {
        val formats = json.optJSONArray("formats") ?: JSONArray()
        val variants = buildList {
            repeat(formats.length()) { position ->
                val format = formats.optJSONObject(position) ?: return@repeat
                val url = PostProcessingExecutionPolicy.sanitizeDurableRemoteUrl(format.optString("url"))
                    ?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
                    ?: return@repeat
                val vcodec = format.optString("vcodec")
                val acodec = format.optString("acodec")
                val kind = when {
                    vcodec.isNotBlank() && vcodec != "none" -> MediaVariantKind.Video
                    acodec.isNotBlank() && acodec != "none" -> MediaVariantKind.Audio
                    else -> MediaVariantKind.Primary
                }
                val formatId = format.optString("format_id", position.toString())
                add(
                    MediaVariant(
                        id = "${capture.id}-ytdlp-${sanitizeFileName(formatId, fallback = position.toString(), maxLength = 48)}",
                        captureId = capture.id,
                        url = url,
                        kind = kind,
                        mimeType = format.optString("mime_type").takeIf(String::isNotBlank),
                        width = format.optInt("width").takeIf { it > 0 },
                        height = format.optInt("height").takeIf { it > 0 },
                        bitrateBitsPerSecond = format.optDouble("tbr").takeIf { it > 0.0 }?.times(1000.0)?.toLong(),
                        codecs = listOf(vcodec, acodec).filter { it.isNotBlank() && it != "none" }.joinToString(",").takeIf(String::isNotBlank),
                        language = format.optString("language").takeIf(String::isNotBlank),
                        position = position,
                        displayLabel = format.optString("format_note").takeIf(String::isNotBlank) ?: formatId,
                    ),
                )
            }
        }
        if (variants.isNotEmpty()) repository.replaceMediaVariants(variants)
        val extension = json.optString("ext").takeIf(String::isNotBlank)
        repository.saveMediaCapture(
            capture.copy(
                title = json.optString("title").takeIf(String::isNotBlank) ?: capture.title,
                status = MediaCaptureStatus.MetadataReady,
                kind = if (json.optBoolean("is_live", false)) MediaSourceKind.VideoStream else capture.kind,
                container = extension ?: capture.container,
                codecs = listOf(json.optString("vcodec"), json.optString("acodec")).filter { it.isNotBlank() && it != "none" }.joinToString(",").takeIf(String::isNotBlank) ?: capture.codecs,
                durationMs = json.optDouble("duration").takeIf { it > 0.0 }?.times(1000.0)?.toLong() ?: capture.durationMs,
                thumbnailUrl = PostProcessingExecutionPolicy.sanitizeDurableRemoteUrl(json.optString("thumbnail")) ?: capture.thumbnailUrl,
                fileName = extension?.let { "${safeBase(capture)}.$it" } ?: capture.fileName,
                variantCount = variants.size.takeIf { it > 0 } ?: capture.variantCount,
                updatedAtEpochMs = System.currentTimeMillis(),
                lastResolvedAtEpochMs = System.currentTimeMillis(),
                resolutionStatus = MediaResolutionStatus.Resolved,
            ),
        )
    }

    private fun monitorJob(jobId: String) {
        if (!monitoredJobIds.add(jobId)) return
        scope.launch {
            try {
                while (true) {
                    val job = dao.findJob(jobId) ?: return@launch
                    val status = PostProcessingJobStatus.entries.firstOrNull { it.name == job.status } ?: PostProcessingJobStatus.RecoveryRequired
                    if (status.terminal || status == PostProcessingJobStatus.RecoveryRequired) return@launch
                    val ownerText = artifactBridge.readText(job.ownerBridgeUri)
                    val owner = TermuxOwnerSnapshot.parse(ownerText)
                    if (owner != null) {
                        if (owner.jobId != job.id || owner.token != job.processToken) {
                            dao.acknowledgeControl(job.id, PostProcessingJobStatus.RecoveryRequired.name, "Durable owner record does not match this job and token.", System.currentTimeMillis())
                            return@launch
                        }
                        if (owner.finished) {
                            reconcileOwnerCompletion(job, owner)
                            return@launch
                        }
                        owner.pid?.let { pid ->
                            if (job.processId != pid && !job.processToken.isNullOrBlank()) {
                                dao.recordProcessOwnership(job.id, job.processToken, pid, job.status, "Validated owner pid=$pid with durable process start identity ${owner.processStartTicks ?: -1L}.", System.currentTimeMillis())
                            }
                        }
                    }
                    val progress = parseProgress(artifactBridge.readText(job.progressBridgeUri), status)
                    dao.updateProgress(job.id, progress.status.name, progress.percent, progress.bytes, progress.totalBytes, progress.message, System.currentTimeMillis())
                    if (job.timeoutAtEpochMs != null && System.currentTimeMillis() >= job.timeoutAtEpochMs && job.requestedControl != "Timeout") {
                        ownerFrom(job)?.let { requestControlNow(job, it, TermuxProcessControlAction.Cancel, timedOut = true) }
                    }
                    delay(1_000L)
                }
            } finally {
                monitoredJobIds.remove(jobId)
            }
        }
    }

    private suspend fun reconcileOwnerCompletion(job: PostProcessingJobEntity, owner: TermuxOwnerSnapshot) {
        val current = dao.findJob(job.id) ?: return
        val status = PostProcessingJobStatus.entries.firstOrNull { it.name == current.status }
        if (status?.terminal == true) return
        val exitCode = owner.exitCode ?: run {
            dao.acknowledgeControl(job.id, PostProcessingJobStatus.RecoveryRequired.name, "Finished owner record has no exit code.", System.currentTimeMillis())
            return
        }
        if (exitCode != 0) {
            val timedOut = current.requestedControl == "Timeout"
            val cancelled = current.requestedControl in setOf(TermuxProcessControlAction.Cancel.name, TermuxProcessControlAction.ForceCancel.name)
            val finalStatus = when {
                timedOut -> PostProcessingJobStatus.TimedOut
                cancelled -> PostProcessingJobStatus.Cancelled
                else -> PostProcessingJobStatus.Failed
            }
            dao.finishJob(
                current.id,
                finalStatus.name,
                null,
                null,
                PostProcessingExecutionPolicy.sanitizeMetadataJson(artifactBridge.readText(current.metadataBridgeUri)).takeIf(String::isNotBlank),
                current.toolVersionsJson,
                current.resultStdoutLength,
                current.resultStderrLength,
                "Durable owner record completed with exit code $exitCode after the Termux callback was absent or delayed.",
                System.currentTimeMillis(),
            )
            cleanupJobBridges(dao.findJob(current.id))
            return
        }
        val spec = runCatching { PostProcessingJobSpec.fromJson(current.immutableSpecJson) }.getOrElse {
            finishFailure(current, "Owner completion could not parse immutable specification: ${it.message}")
            return
        }
        reconcileSuccessfulJob(
            current,
            spec,
            current.outputBridgeUri,
            artifactBridge.readText(current.metadataBridgeUri),
            current.toolVersionsJson,
            current.resultStdoutLength,
            current.resultStderrLength,
        )
    }

    private data class ParsedProgress(val status: PostProcessingJobStatus, val percent: Int, val bytes: Long, val totalBytes: Long?, val message: String)

    private fun parseProgress(raw: String, current: PostProcessingJobStatus): ParsedProgress {
        if (raw.isBlank()) return ParsedProgress(current, 0, 0L, null, current.label)
        val ytdlp = raw.lineSequence().lastOrNull { it.startsWith("XDM_YTDLP\t") }
        if (ytdlp != null) {
            val parts = ytdlp.split('\t')
            val percent = parts.getOrNull(1)?.replace("%", "")?.trim()?.toDoubleOrNull()?.toInt()?.coerceIn(0, 100) ?: 0
            val bytes = parts.getOrNull(2)?.toLongOrNull() ?: 0L
            val total = parts.getOrNull(3)?.toLongOrNull()?.takeIf { it > 0 }
            return ParsedProgress(current, percent, bytes, total, "yt-dlp download $percent%")
        }
        val values = parseKeyValues(raw)
        val totalBytes = values["total_size"]?.toLongOrNull() ?: values["total_bytes"]?.toLongOrNull()
        val bytes = values["bytes"]?.toLongOrNull() ?: totalBytes ?: 0L
        val percent = values["percent"]?.toIntOrNull()?.coerceIn(0, 100) ?: if (values["progress"] == "end") 100 else 0
        val message = values["message"] ?: values["phase"]?.let { "$it • ${current.label}" } ?: current.label
        return ParsedProgress(current, percent, bytes, totalBytes, message)
    }

    private fun parseKeyValues(raw: String): Map<String, String> = raw.lineSequence()
        .mapNotNull { line -> line.substringBefore('=', missingDelimiterValue = "").takeIf(String::isNotBlank)?.let { it to line.substringAfter('=') } }
        .toMap()

    private fun ownerFrom(job: PostProcessingJobEntity): TermuxRunOwner? {
        val token = job.processToken?.takeIf(String::isNotBlank) ?: return null
        val ownerUri = job.ownerBridgeUri?.takeIf(String::isNotBlank) ?: return null
        val progressUri = job.progressBridgeUri?.takeIf(String::isNotBlank) ?: return null
        val metadataUri = job.metadataBridgeUri?.takeIf(String::isNotBlank) ?: return null
        val ownerPath = shellPathFor(ownerUri) ?: return null
        val progressPath = shellPathFor(progressUri) ?: return null
        val metadataPath = shellPathFor(metadataUri) ?: return null
        return TermuxRunOwner(
            jobId = job.id,
            processToken = token,
            timeoutAtEpochMs = job.timeoutAtEpochMs,
            runtime = TermuxRuntimeArtifacts(ownerPath, ownerUri, progressPath, progressUri, metadataPath, metadataUri),
        )
    }

    @Suppress("DEPRECATION")
    private fun shellPathFor(contentUri: String): String? {
        val uri = Uri.parse(contentUri)
        if (uri.scheme == ContentResolver.SCHEME_FILE) return uri.path
        return appContext.contentResolver.query(
            uri,
            arrayOf(android.provider.MediaStore.MediaColumns.DATA),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) null
            else cursor.getColumnIndex(android.provider.MediaStore.MediaColumns.DATA)
                .takeIf { it >= 0 && !cursor.isNull(it) }
                ?.let(cursor::getString)
        }
    }

    private fun commandFor(spec: PostProcessingJobSpec, prepared: AndroidPostProcessingArtifactBridge.PreparedExecution): XdmTermuxCommand {
        if (spec.kind == PostProcessingActionKind.CleanupPartials && Uri.parse(spec.inputUri).scheme == ContentResolver.SCHEME_CONTENT) {
            error("Cleanup partials requires the exact local partial artifact path; a destination content URI is not sufficient.")
        }
        val plan = TermuxPostProcessingPlan(
            kind = spec.kind,
            inputPath = prepared.inputPath,
            outputPath = prepared.outputPath.orEmpty(),
            expectedSha256 = spec.expectedSha256.orEmpty(),
            formatSelector = spec.formatSelector.orEmpty(),
            extraArguments = spec.extraArguments,
        )
        return when (spec.kind) {
            PostProcessingActionKind.FixPermissionsWithRoot -> XdmTermuxCommand.RootAction(XdmRootAction.FixFilePermissions(artifactBridge.verifiedOriginalPath(spec.inputUri)))
            else -> XdmTermuxCommand.PostProcess(plan)
        }
    }

    private fun manualSpec(
        record: MediaCaptureRecord,
        kind: PostProcessingActionKind,
        input: String,
        outputName: String,
        mimeType: String,
        requiredTools: Set<ExternalTool>,
        metadataOnly: Boolean = false,
        formatSelector: String? = null,
        extraArguments: List<String> = emptyList(),
        destinationUri: String? = null,
    ) = PostProcessingJobSpec(
        subjectId = record.id,
        subjectType = PostProcessingSubjectType.MediaCapture,
        subjectGeneration = PostProcessingExecutionPolicy.mediaSubjectGeneration(
            captureId = record.id,
            linkedDownloadId = record.downloadId,
            resolvedAtEpochMs = record.lastResolvedAtEpochMs,
            createdAtEpochMs = record.createdAtEpochMs,
        ),
        captureId = record.id,
        actionId = "manual-${kind.name.lowercase(Locale.US)}",
        trigger = PostProcessingAutomationTrigger.MediaCaptured,
        kind = kind,
        title = record.title.ifBlank { record.fileName },
        inputUri = input,
        inputMimeType = record.mimeType,
        inputContainer = record.container,
        inputCodecs = record.codecs,
        output = PostProcessingOutputSpec(outputName, mimeType, destinationUri),
        requiredTools = requiredTools,
        resultMode = if (metadataOnly) PostProcessingResultMode.MetadataOnly else PostProcessingResultMode.OutputArtifact,
        metadataOnly = metadataOnly,
        formatSelector = formatSelector,
        extraArguments = extraArguments,
    )

    private fun newJobEntity(
        spec: PostProcessingJobSpec,
        id: String,
        rootJobId: String,
        parentJobId: String?,
        generation: Int,
        claimKey: String?,
        now: Long,
    ) = PostProcessingJobEntity(
        id = id,
        rootJobId = rootJobId,
        parentJobId = parentJobId,
        attemptGeneration = generation,
        claimKey = claimKey,
        subjectId = spec.subjectId,
        subjectType = spec.subjectType.name,
        subjectGeneration = spec.subjectGeneration,
        downloadId = spec.downloadId,
        captureId = spec.captureId,
        ruleId = spec.ruleId,
        actionId = spec.actionId,
        trigger = spec.trigger.name,
        kind = spec.kind.name,
        status = PostProcessingJobStatus.Queued.name,
        title = spec.title,
        inputUri = spec.inputUri,
        stagedInputPath = null,
        inputBridgeUri = null,
        outputDisplayName = spec.output.displayName,
        outputMimeType = spec.output.mimeType,
        outputDestinationUri = spec.output.destinationUri,
        stagedOutputPath = null,
        outputBridgeUri = null,
        ownerBridgeUri = null,
        progressBridgeUri = null,
        metadataBridgeUri = null,
        payloadBridgeUri = null,
        finalOutputUri = null,
        publicationState = PostProcessingPublicationState.None.name,
        publicationDisplayName = null,
        publicationExpectedBytes = null,
        publicationExpectedSha256 = null,
        committedOutputUri = null,
        committedBytes = null,
        committedSha256 = null,
        sideEffectOutcome = null,
        immutableSpecJson = spec.toJson(),
        expectedSha256 = PostProcessingExecutionPolicy.normalizedSha256(spec.expectedSha256),
        actualSha256 = null,
        requiredTools = spec.requiredTools.joinToString(",") { it.name },
        toolVersionsJson = "{}",
        runId = null,
        executionId = null,
        processToken = null,
        processId = null,
        controlGeneration = 0L,
        requestedControl = null,
        progressPercent = 0,
        progressBytes = 0L,
        progressTotalBytes = null,
        timeoutAtEpochMs = null,
        resultStdoutLength = 0,
        resultStderrLength = 0,
        metadataJson = null,
        message = "Durable immutable post-processing attempt queued.",
        createdAtEpochMs = now,
        updatedAtEpochMs = now,
        startedAtEpochMs = null,
        finishedAtEpochMs = null,
    )

    private fun parseToolVersions(stdout: String): String {
        val json = JSONObject()
        stdout.lineSequence().filter { it.startsWith("XDM_TOOL_VERSION\t") }.forEach { line ->
            val fields = line.split('\t', limit = 3)
            if (fields.size == 3) json.put(fields[1], fields[2])
        }
        return json.toString()
    }

    private fun safeResultDetail(result: TermuxResultPayload): String =
        PrivacyDiagnosticsRedactor.redactText(
            result.error.ifBlank { result.stderr.ifBlank { result.stdout } }
                .lineSequence()
                .take(4)
                .joinToString(" "),
        ).orEmpty().take(800)

    private fun buildFailureMessage(result: TermuxResultPayload, status: PostProcessingJobStatus): String {
        val truncation = buildList {
            if (result.stdoutOriginalLength > result.stdout.length) add("stdout truncated ${result.stdout.length}/${result.stdoutOriginalLength}")
            if (result.stderrOriginalLength > result.stderr.length) add("stderr truncated ${result.stderr.length}/${result.stderrOriginalLength}")
        }.joinToString()
        val detail = safeResultDetail(result)
        return listOf(status.label, detail, truncation).filter(String::isNotBlank).joinToString(" • ")
    }

    private suspend fun finishFailure(job: PostProcessingJobEntity, message: String) {
        dao.finishJob(job.id, PostProcessingJobStatus.Failed.name, null, null, null, "{}", 0, 0, message, System.currentTimeMillis())
    }

    private fun cleanupJobBridges(job: PostProcessingJobEntity?) {
        if (job == null) return
        artifactBridge.cleanupUris(
            listOf(job.inputBridgeUri, job.outputBridgeUri, job.ownerBridgeUri, job.progressBridgeUri, job.metadataBridgeUri),
        )
    }

    private fun metadataProbeUrl(record: MediaCaptureRecord): String = planner.plan(record, emptyList()).metadataProbeUrl

    private fun sessionHintHeaders(record: MediaCaptureRecord): List<MediaSessionHeader> = buildList {
        record.pageUrl?.takeIf(String::isNotBlank)?.let { page ->
            runCatching { URI(page) }.getOrNull()?.let { uri ->
                if (!uri.scheme.isNullOrBlank() && !uri.host.isNullOrBlank()) add(MediaSessionHeader("Origin", "${uri.scheme}://${uri.host}"))
            }
        }
    }

    private fun safeBase(record: MediaCaptureRecord): String = sanitizeFileName(record.title.ifBlank { record.fileName }.substringBeforeLast('.', record.title.ifBlank { record.fileName }), fallback = "xdm-media", maxLength = 96)
}
