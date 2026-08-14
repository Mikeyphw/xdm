package com.mikeyphw.xdm.android.termux

import com.mikeyphw.xdm.android.UserPreferencesStore
import com.mikeyphw.xdm.android.model.ConversionPreset
import com.mikeyphw.xdm.android.model.Download
import com.mikeyphw.xdm.android.model.DownloadState
import com.mikeyphw.xdm.android.model.FilenameConflictPolicy
import com.mikeyphw.xdm.android.model.MediaCaptureRecord
import com.mikeyphw.xdm.android.model.MediaCaptureStatus
import com.mikeyphw.xdm.android.model.PostProcessingSettings
import com.mikeyphw.xdm.android.persistence.DownloadRepository
import com.mikeyphw.xdm.android.scheduler.TransferTerminalEvent
import com.mikeyphw.xdm.android.util.sanitizeFileName
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Connects repository terminal events to the durable post-processing pipeline.
 *
 * DataStore is the single settings authority. Room claim keys make each
 * subject-generation/rule/action tuple idempotent across process death.
 */
class PostProcessingAutomationManager(
    private val preferences: UserPreferencesStore,
    private val repository: DownloadRepository,
    private val mediaPipeline: TermuxMediaPipelineManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val statusFlow = MutableStateFlow(
        PostProcessingAutomationStatus(
            enabled = false,
            rules = emptyList(),
            updatedAtEpochMs = System.currentTimeMillis(),
        ),
    )
    private var automaticCollectorsStarted = false
    private val observedCaptureStates = mutableMapOf<String, Pair<MediaCaptureStatus, String?>>()
    private val previewEvents = ArrayDeque<PostProcessingAutomationEvent>()

    val status: StateFlow<PostProcessingAutomationStatus> = statusFlow

    init {
        scope.launch {
            combine(preferences.values, mediaPipeline.status) { prefs, pipeline -> prefs.postProcessingSettings to pipeline }
                .collectLatest { (settings, pipeline) ->
                    val rules = rulesFor(settings)
                    val durableEvents = pipeline.recentJobs.map { job -> job.toAutomationEvent(rules) }
                    statusFlow.value = PostProcessingAutomationStatus(
                        enabled = settings.enabled,
                        rules = rules,
                        events = (previewEvents.toList() + durableEvents)
                            .distinctBy(PostProcessingAutomationEvent::id)
                            .sortedByDescending(PostProcessingAutomationEvent::updatedAtEpochMs)
                            .take(MaxEvents),
                        lastMessage = pipeline.lastAction,
                        updatedAtEpochMs = maxOf(pipeline.updatedAtEpochMs, System.currentTimeMillis()),
                    )
                }
        }
    }

    fun startAutomaticProcessing() {
        if (automaticCollectorsStarted) return
        automaticCollectorsStarted = true
        scope.launch {
            reconcileMissedTerminalEvents()
            combine(preferences.values, repository.mediaCaptures) { prefs, captures -> prefs to captures }
                .collectLatest { (prefs, captures) ->
                    val settings = prefs.postProcessingSettings
                    val previous = observedCaptureStates.toMap()
                    observedCaptureStates.clear()
                    captures.forEach { observedCaptureStates[it.id] = it.status to it.downloadId }
                    if (!settings.enabled) return@collectLatest
                    captures.forEach { capture ->
                        val old = previous[capture.id]
                        val current = capture.status to capture.downloadId
                        val newlyCaptured = old != current && capture.status in AutomaticCaptureStates
                        if (newlyCaptured) enqueueForMedia(capture, settings, prefs.destinationUri, prefs.conflictPolicy, automatic = true)
                    }
                }
        }
    }

    private suspend fun reconcileMissedTerminalEvents() {
        val prefs = preferences.values.firstValue()
        if (!prefs.postProcessingSettings.enabled) return
        repository.findDownloadsByStates(AutomaticDownloadStates).forEach { download ->
            val generation = repository.attemptGenerationForDownload(download.id)
                ?: PostProcessingExecutionPolicy.fallbackSubjectGeneration(download.id, download.createdAtEpochMs)
            enqueueForDownload(download, prefs.postProcessingSettings, prefs.destinationUri, automatic = true, attemptGeneration = generation)
        }
        repository.mediaCaptures.first().filter { it.status in AutomaticCaptureStates }.forEach { capture ->
            enqueueForMedia(capture, prefs.postProcessingSettings, prefs.destinationUri, prefs.conflictPolicy, automatic = true)
        }
    }

    suspend fun handleTransferTerminalEvent(event: TransferTerminalEvent) {
        if (event.state !in AutomaticDownloadStates) return
        val prefs = preferences.values.firstValue()
        if (!prefs.postProcessingSettings.enabled) return
        val download = repository.findDownload(event.downloadId) ?: return
        enqueueForDownload(
            download = download,
            settings = prefs.postProcessingSettings,
            destinationUri = prefs.destinationUri,
            automatic = true,
            attemptGeneration = event.attemptGeneration.takeIf { it > 0L }
                ?: repository.attemptGenerationForDownload(download.id)
                ?: PostProcessingExecutionPolicy.fallbackSubjectGeneration(download.id, download.createdAtEpochMs),
        )
    }

    fun refreshStatus() {
        mediaPipeline.refreshStatus()
    }

    fun setEnabled(enabled: Boolean) {
        scope.launch {
            val current = preferences.values.firstValue().postProcessingSettings
            preferences.setPostProcessingSettings(current.copy(enabled = enabled))
        }
    }

    fun preview(download: Download) {
        addPreview(
            subjectId = download.id,
            subjectLabel = download.fileName,
            trigger = triggerFor(download) ?: PostProcessingAutomationTrigger.DownloadCompleted,
            message = PostProcessingAutomationPolicy.preview(download, statusFlow.value),
        )
    }

    fun preview(capture: MediaCaptureRecord) {
        addPreview(
            subjectId = capture.id,
            subjectLabel = capture.title.ifBlank { capture.fileName },
            trigger = triggerFor(capture),
            message = PostProcessingAutomationPolicy.preview(capture, statusFlow.value),
        )
    }

    fun runForDownload(download: Download) {
        scope.launch {
            preferences.values.firstValue().let { prefs -> enqueueForDownload(download, prefs.postProcessingSettings.copy(enabled = true), prefs.destinationUri, automatic = false) }
        }
    }

    fun runForMedia(capture: MediaCaptureRecord) {
        scope.launch {
            preferences.values.firstValue().let { prefs -> enqueueForMedia(capture, prefs.postProcessingSettings.copy(enabled = true), prefs.destinationUri, prefs.conflictPolicy, automatic = false) }
        }
    }

    fun retryLastFailed() {
        mediaPipeline.retryLastFailed()
    }

    fun clearEvents() {
        previewEvents.clear()
        mediaPipeline.clearCompleted()
        statusFlow.update {
            it.copy(
                events = emptyList(),
                lastMessage = "Manual terminal history cleared; durable automatic claims, active jobs, and recovery jobs were retained.",
                updatedAtEpochMs = System.currentTimeMillis(),
            )
        }
    }

    private suspend fun enqueueForDownload(
        download: Download,
        settings: PostProcessingSettings,
        destinationUri: String,
        automatic: Boolean,
        attemptGeneration: Long = PostProcessingExecutionPolicy.fallbackSubjectGeneration(download.id, download.createdAtEpochMs),
    ) {
        if (!settings.enabled && automatic) return
        val currentStatus = statusFlow.value.copy(enabled = settings.enabled || !automatic, rules = rulesFor(settings))
        val rules = PostProcessingAutomationPolicy.matchingRules(currentStatus, download)
        if (rules.isEmpty()) {
            recordSkipped(download.id, download.fileName, "No enabled post-processing rule matches this download.", triggerFor(download) ?: PostProcessingAutomationTrigger.DownloadCompleted)
            return
        }
        rules.forEach { rule ->
            rule.actions.forEachIndexed { index, action ->
                val inputOverride = if (action.kind == PostProcessingActionKind.CleanupPartials) repository.recoveryArtifactForDownload(download.id) else null
                val spec = runCatching { specForDownload(rule, action, index, download, destinationUri, attemptGeneration, inputOverride) }.getOrElse { error ->
                    recordFailed(download.id, download.fileName, rule, triggerFor(download) ?: rule.trigger, error.message ?: "Invalid post-processing specification.")
                    return@forEachIndexed
                }
                val outcome = mediaPipeline.enqueue(spec, durableClaim = true)
                if (!outcome.accepted && !automatic) {
                    recordSkipped(download.id, download.fileName, outcome.message, rule.trigger)
                }
            }
        }
    }

    private suspend fun enqueueForMedia(
        capture: MediaCaptureRecord,
        settings: PostProcessingSettings,
        destinationUri: String,
        conflictPolicy: FilenameConflictPolicy,
        automatic: Boolean,
    ) {
        if (!settings.enabled && automatic) return
        val currentStatus = statusFlow.value.copy(enabled = settings.enabled || !automatic, rules = rulesFor(settings))
        val rules = PostProcessingAutomationPolicy.matchingRules(currentStatus, capture)
        if (rules.isEmpty()) {
            recordSkipped(capture.id, capture.title.ifBlank { capture.fileName }, "No enabled media post-processing rule matches this capture.", triggerFor(capture))
            return
        }
        rules.forEach { rule ->
            rule.actions.forEachIndexed { index, action ->
                val spec = runCatching { specForMedia(rule, action, index, capture, destinationUri, conflictPolicy) }.getOrElse { error ->
                    recordFailed(capture.id, capture.title.ifBlank { capture.fileName }, rule, triggerFor(capture), error.message ?: "Invalid post-processing specification.")
                    return@forEachIndexed
                }
                val outcome = mediaPipeline.enqueue(spec, durableClaim = true)
                if (!outcome.accepted && !automatic) {
                    recordSkipped(capture.id, capture.title.ifBlank { capture.fileName }, outcome.message, rule.trigger)
                }
            }
        }
    }

    private fun specForDownload(
        rule: PostProcessingAutomationRule,
        action: PostProcessingAutomationAction,
        actionIndex: Int,
        download: Download,
        destinationUri: String,
        attemptGeneration: Long,
        inputOverride: String? = null,
    ): PostProcessingJobSpec {
        val committedInput = download.completedArtifactUri
            ?.takeIf { download.completedArtifactGeneration == attemptGeneration }
            ?.trim()
            ?.takeIf(String::isNotBlank)
        val input = inputOverride?.trim().takeUnless { it.isNullOrBlank() } ?: committedInput
            ?: error("Completed download has no generation-bound committed artifact URI. Locate or re-grant the file before post-processing.")
        require(input.startsWith("content://") || input.startsWith("file://") || input.startsWith("/")) {
            "Completed download has no Android-readable committed artifact URI. Locate or re-grant the file before post-processing."
        }
        val base = sanitizeFileName(download.fileName.substringBeforeLast('.', download.fileName), "xdm-download", 96)
        val outputName = outputNameFor(action, base, download.fileName)
        return PostProcessingJobSpec(
            subjectId = download.id,
            subjectType = PostProcessingSubjectType.Download,
            subjectGeneration = attemptGeneration,
            downloadId = download.id,
            ruleId = rule.id,
            actionId = "${rule.id}:$actionIndex:${action.kind.name}",
            trigger = triggerFor(download) ?: rule.trigger,
            kind = action.kind,
            title = "${action.kind.label}: ${download.fileName}",
            inputUri = input,
            inputMimeType = download.mimeType,
            inputContainer = download.fileName.substringAfterLast('.', missingDelimiterValue = "").takeIf(String::isNotBlank),
            output = PostProcessingOutputSpec(
                displayName = outputName,
                mimeType = outputMimeType(action.kind, download.mimeType),
                destinationUri = action.value.trim().takeIf { action.kind == PostProcessingActionKind.MoveToFolder && (it.startsWith("content://") || it.startsWith("xdm://")) } ?: destinationUri,
                conflictPolicy = download.conflictPolicy.toPostProcessingConflictPolicy(),
                deleteOriginalAfterPublish = action.kind == PostProcessingActionKind.MoveToFolder,
            ),
            expectedSha256 = action.value.trim().takeIf { action.kind == PostProcessingActionKind.VerifySha256 },
            requiredTools = requiredTools(action.kind),
            timeoutSeconds = timeoutFor(action.kind),
            estimatedOutputBytes = download.totalBytes ?: download.bytesReceived.takeIf { it > 0L },
            resultMode = resultModeFor(action.kind),
            metadataOnly = resultModeFor(action.kind) == PostProcessingResultMode.MetadataOnly,
        )
    }

    private fun specForMedia(
        rule: PostProcessingAutomationRule,
        action: PostProcessingAutomationAction,
        actionIndex: Int,
        capture: MediaCaptureRecord,
        destinationUri: String,
        conflictPolicy: FilenameConflictPolicy,
    ): PostProcessingJobSpec {
        val input = capture.selectedVariantUrl ?: capture.sourceUrl
        val base = sanitizeFileName(capture.title.ifBlank { capture.fileName }, "xdm-media", 96)
        return PostProcessingJobSpec(
            subjectId = capture.id,
            subjectType = PostProcessingSubjectType.MediaCapture,
            subjectGeneration = PostProcessingExecutionPolicy.mediaSubjectGeneration(
                captureId = capture.id,
                linkedDownloadId = capture.downloadId,
                resolvedAtEpochMs = capture.lastResolvedAtEpochMs,
                createdAtEpochMs = capture.createdAtEpochMs,
            ),
            downloadId = capture.downloadId,
            captureId = capture.id,
            ruleId = rule.id,
            actionId = "${rule.id}:$actionIndex:${action.kind.name}",
            trigger = triggerFor(capture),
            kind = action.kind,
            title = "${action.kind.label}: ${capture.title.ifBlank { capture.fileName }}",
            inputUri = input,
            inputMimeType = capture.mimeType,
            inputContainer = capture.container,
            inputCodecs = capture.codecs,
            output = PostProcessingOutputSpec(
                displayName = outputNameFor(action, base, capture.fileName),
                mimeType = outputMimeType(action.kind, capture.mimeType),
                destinationUri = action.value.trim().takeIf { action.kind == PostProcessingActionKind.MoveToFolder && (it.startsWith("content://") || it.startsWith("xdm://")) } ?: destinationUri,
                conflictPolicy = conflictPolicy.toPostProcessingConflictPolicy(),
            ),
            expectedSha256 = action.value.trim().takeIf { action.kind == PostProcessingActionKind.VerifySha256 },
            requiredTools = requiredTools(action.kind),
            timeoutSeconds = timeoutFor(action.kind),
            resultMode = resultModeFor(action.kind),
            metadataOnly = resultModeFor(action.kind) == PostProcessingResultMode.MetadataOnly,
        )
    }

    private fun rulesFor(settings: PostProcessingSettings): List<PostProcessingAutomationRule> {
        if (!settings.enabled) return emptyList()
        val conversionAction = when (settings.preset) {
            ConversionPreset.AudioExtract -> PostProcessingAutomationAction(PostProcessingActionKind.ExtractAudio, "{name}.m4a")
            ConversionPreset.VideoFastStart -> PostProcessingAutomationAction(PostProcessingActionKind.RemuxFastStart, "{name}.faststart.mp4")
            ConversionPreset.ArchiveExtract -> PostProcessingAutomationAction(PostProcessingActionKind.FfmpegRemux, "{name}.remux.mkv")
            ConversionPreset.CustomCommand, ConversionPreset.None -> null
        }
        return buildList {
            conversionAction?.let { action ->
                add(
                    PostProcessingAutomationRule(
                        id = "settings-download-conversion",
                        name = "Saved post-processing preset",
                        enabled = true,
                        trigger = PostProcessingAutomationTrigger.DownloadCompleted,
                        actions = listOf(action),
                    ),
                )
            }
            add(
                PostProcessingAutomationRule(
                    id = "settings-media-inspection",
                    name = "Inspect completed media",
                    enabled = true,
                    trigger = PostProcessingAutomationTrigger.MediaDownloadCreated,
                    conditions = listOf(PostProcessingAutomationCondition(PostProcessingConditionKind.MimeType, "video/*")),
                    actions = listOf(PostProcessingAutomationAction(PostProcessingActionKind.FfprobeInspect)),
                ),
            )
        }
    }

    private fun outputNameFor(action: PostProcessingAutomationAction, base: String, originalName: String): String {
        val requested = if (action.kind == PostProcessingActionKind.MoveToFolder) {
            ""
        } else {
            action.value
                .replace("{name}", base)
                .replace("{file}", sanitizeFileName(originalName, "xdm-output", 120))
                .trim()
        }
        val fallback = when (action.kind) {
            PostProcessingActionKind.FfprobeInspect -> "$base.ffprobe.json"
            PostProcessingActionKind.YtDlpMetadata -> "$base.metadata.json"
            PostProcessingActionKind.YtDlpDownload -> "$base.mp4"
            PostProcessingActionKind.RemuxFastStart -> "$base.faststart.mp4"
            PostProcessingActionKind.ExtractAudio -> "$base.m4a"
            PostProcessingActionKind.FfmpegRemux -> "$base.remux.mkv"
            PostProcessingActionKind.VerifySha256 -> "$base.sha256.txt"
            PostProcessingActionKind.CleanupPartials -> "$base.cleanup.json"
            PostProcessingActionKind.FixPermissionsWithRoot -> "$base.permissions.json"
            PostProcessingActionKind.MoveToFolder, PostProcessingActionKind.RenameByPattern -> originalName
        }
        return (requested.ifBlank { fallback }).also { name ->
            PostProcessingExecutionPolicy.validateOutputName(name)?.let { throw IllegalArgumentException(it) }
        }
    }

    private fun requiredTools(kind: PostProcessingActionKind): Set<ExternalTool> = when (kind) {
        PostProcessingActionKind.FfprobeInspect -> setOf(ExternalTool.Ffprobe)
        PostProcessingActionKind.RemuxFastStart, PostProcessingActionKind.ExtractAudio, PostProcessingActionKind.FfmpegRemux -> setOf(ExternalTool.Ffmpeg, ExternalTool.Ffprobe)
        PostProcessingActionKind.YtDlpMetadata -> setOf(ExternalTool.YtDlp)
        PostProcessingActionKind.YtDlpDownload -> setOf(ExternalTool.YtDlp, ExternalTool.Ffmpeg, ExternalTool.Ffprobe)
        else -> emptySet()
    }

    private fun outputMimeType(kind: PostProcessingActionKind, inputMime: String?): String = when (kind) {
        PostProcessingActionKind.FfprobeInspect, PostProcessingActionKind.YtDlpMetadata, PostProcessingActionKind.CleanupPartials, PostProcessingActionKind.FixPermissionsWithRoot -> "application/json"
        PostProcessingActionKind.VerifySha256 -> "text/plain"
        PostProcessingActionKind.ExtractAudio -> "audio/mp4"
        PostProcessingActionKind.RemuxFastStart, PostProcessingActionKind.YtDlpDownload -> "video/mp4"
        PostProcessingActionKind.FfmpegRemux -> "video/x-matroska"
        else -> inputMime ?: "application/octet-stream"
    }

    private fun resultModeFor(kind: PostProcessingActionKind): PostProcessingResultMode = when (kind) {
        PostProcessingActionKind.FfprobeInspect, PostProcessingActionKind.YtDlpMetadata -> PostProcessingResultMode.MetadataOnly
        PostProcessingActionKind.CleanupPartials, PostProcessingActionKind.FixPermissionsWithRoot -> PostProcessingResultMode.SideEffectOnly
        PostProcessingActionKind.VerifySha256, PostProcessingActionKind.RenameByPattern -> PostProcessingResultMode.InPlace
        else -> PostProcessingResultMode.OutputArtifact
    }

    private fun FilenameConflictPolicy.toPostProcessingConflictPolicy(): PostProcessingConflictPolicy = when (this) {
        FilenameConflictPolicy.Overwrite -> PostProcessingConflictPolicy.Replace
        FilenameConflictPolicy.Rename -> PostProcessingConflictPolicy.Rename
        FilenameConflictPolicy.Resume, FilenameConflictPolicy.Skip, FilenameConflictPolicy.Compare -> PostProcessingConflictPolicy.Fail
    }

    private fun timeoutFor(kind: PostProcessingActionKind): Long = when (kind) {
        PostProcessingActionKind.FfprobeInspect, PostProcessingActionKind.YtDlpMetadata -> 5 * 60L
        PostProcessingActionKind.VerifySha256 -> 60 * 60L
        else -> 6 * 60 * 60L
    }

    private fun triggerFor(download: Download): PostProcessingAutomationTrigger? = when (download.state) {
        DownloadState.Completed -> PostProcessingAutomationTrigger.DownloadCompleted
        DownloadState.Failed, DownloadState.Cancelled, DownloadState.RecoveryRequired -> PostProcessingAutomationTrigger.DownloadFailed
        else -> null
    }

    private fun triggerFor(capture: MediaCaptureRecord): PostProcessingAutomationTrigger =
        if (capture.downloadId != null) PostProcessingAutomationTrigger.MediaDownloadCreated else PostProcessingAutomationTrigger.MediaCaptured

    private fun addPreview(subjectId: String, subjectLabel: String, trigger: PostProcessingAutomationTrigger, message: String) {
        val now = System.currentTimeMillis()
        previewEvents.addFirst(
            PostProcessingAutomationEvent(
                id = "preview-${UUID.randomUUID()}",
                ruleId = "preview",
                ruleName = "Preview",
                trigger = trigger,
                status = PostProcessingAutomationEventStatus.Preview,
                subjectId = subjectId,
                subjectLabel = subjectLabel,
                message = message,
                createdAtEpochMs = now,
            ),
        )
        while (previewEvents.size > 6) previewEvents.removeLast()
        statusFlow.update { it.copy(events = (previewEvents.toList() + it.events).distinctBy(PostProcessingAutomationEvent::id).take(MaxEvents), lastMessage = message, updatedAtEpochMs = now) }
    }

    private fun recordSkipped(subjectId: String, subjectLabel: String, message: String, trigger: PostProcessingAutomationTrigger) =
        recordEphemeral(subjectId, subjectLabel, "No rule", "none", trigger, PostProcessingAutomationEventStatus.Skipped, message)

    private fun recordFailed(subjectId: String, subjectLabel: String, rule: PostProcessingAutomationRule, trigger: PostProcessingAutomationTrigger, message: String) =
        recordEphemeral(subjectId, subjectLabel, rule.name, rule.id, trigger, PostProcessingAutomationEventStatus.Failed, message)

    private fun recordEphemeral(
        subjectId: String,
        subjectLabel: String,
        ruleName: String,
        ruleId: String,
        trigger: PostProcessingAutomationTrigger,
        eventStatus: PostProcessingAutomationEventStatus,
        message: String,
    ) {
        val now = System.currentTimeMillis()
        val event = PostProcessingAutomationEvent(
            id = "post-${UUID.randomUUID()}",
            ruleId = ruleId,
            ruleName = ruleName,
            trigger = trigger,
            status = eventStatus,
            subjectId = subjectId,
            subjectLabel = subjectLabel,
            message = message,
            createdAtEpochMs = now,
        )
        previewEvents.addFirst(event)
        while (previewEvents.size > 6) previewEvents.removeLast()
        statusFlow.update { it.copy(events = (listOf(event) + it.events).distinctBy(PostProcessingAutomationEvent::id).take(MaxEvents), lastMessage = message, updatedAtEpochMs = now) }
    }

    private fun TermuxMediaPipelineJob.toAutomationEvent(rules: List<PostProcessingAutomationRule>): PostProcessingAutomationEvent {
        val rule = rules.firstOrNull { title.contains(it.name, ignoreCase = true) }
        val eventStatus = when (status) {
            TermuxMediaJobStatus.Completed -> PostProcessingAutomationEventStatus.Completed
            TermuxMediaJobStatus.Failed, TermuxMediaJobStatus.Cancelled, TermuxMediaJobStatus.TimedOut, TermuxMediaJobStatus.RecoveryRequired -> PostProcessingAutomationEventStatus.Failed
            else -> PostProcessingAutomationEventStatus.Queued
        }
        return PostProcessingAutomationEvent(
            id = id,
            ruleId = rule?.id ?: "durable-job",
            ruleName = rule?.name ?: kind.label,
            trigger = if (captureId != null) PostProcessingAutomationTrigger.MediaDownloadCreated else PostProcessingAutomationTrigger.DownloadCompleted,
            status = eventStatus,
            subjectId = captureId ?: downloadId ?: id,
            subjectLabel = title,
            message = message.ifBlank { progressLabel },
            runId = runId,
            createdAtEpochMs = createdAtEpochMs,
            updatedAtEpochMs = updatedAtEpochMs,
        )
    }

    companion object {
        private const val MaxEvents = 24
        private val AutomaticDownloadStates = setOf(DownloadState.Completed, DownloadState.Failed, DownloadState.Cancelled, DownloadState.RecoveryRequired)
        private val AutomaticCaptureStates = setOf(MediaCaptureStatus.Captured, MediaCaptureStatus.MetadataReady, MediaCaptureStatus.DownloadCreated)
    }
}

private suspend fun <T> kotlinx.coroutines.flow.Flow<T>.firstValue(): T = first()
