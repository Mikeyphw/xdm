package com.mikeyphw.xdm.android.termux

import android.content.Context
import com.mikeyphw.xdm.android.model.ConversionPreset
import com.mikeyphw.xdm.android.model.Download
import com.mikeyphw.xdm.android.model.MediaCaptureRecord
import com.mikeyphw.xdm.android.util.sanitizeFileName
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

class PostProcessingAutomationManager(
    context: Context,
    private val mediaPipeline: TermuxMediaPipelineManager,
    private val termuxBridge: TermuxBridgeManager,
) {
    private val appContext = context.applicationContext
    private val runner = TermuxCommandRunner(appContext)
    private val statusFlow = MutableStateFlow(PostProcessingAutomationStatus(updatedAtEpochMs = System.currentTimeMillis()))

    val status: StateFlow<PostProcessingAutomationStatus> = statusFlow

    fun refreshStatus() {
        statusFlow.update { it.copy(updatedAtEpochMs = System.currentTimeMillis()) }
    }

    fun setEnabled(enabled: Boolean) {
        statusFlow.update {
            it.copy(
                enabled = enabled,
                lastMessage = if (enabled) "Post-processing automation enabled." else "Post-processing automation disabled.",
                updatedAtEpochMs = System.currentTimeMillis(),
            )
        }
    }

    fun preview(download: Download) {
        val now = System.currentTimeMillis()
        val message = PostProcessingAutomationPolicy.preview(download, statusFlow.value)
        statusFlow.update { current ->
            current.copy(
                lastMessage = message,
                events = (listOf(event("preview-${UUID.randomUUID()}", current.rules.firstOrNull()?.id.orEmpty(), "Preview", PostProcessingAutomationTrigger.DownloadCompleted, PostProcessingAutomationEventStatus.Preview, download.id, download.fileName, message, now = now)) + current.events).take(MaxEvents),
                updatedAtEpochMs = now,
            )
        }
    }

    fun preview(capture: MediaCaptureRecord) {
        val now = System.currentTimeMillis()
        val message = PostProcessingAutomationPolicy.preview(capture, statusFlow.value)
        statusFlow.update { current ->
            current.copy(
                lastMessage = message,
                events = (listOf(event("preview-${UUID.randomUUID()}", current.rules.firstOrNull()?.id.orEmpty(), "Preview", PostProcessingAutomationTrigger.MediaCaptured, PostProcessingAutomationEventStatus.Preview, capture.id, capture.title, message, now = now)) + current.events).take(MaxEvents),
                updatedAtEpochMs = now,
            )
        }
    }

    fun runForDownload(download: Download) {
        val current = statusFlow.value
        if (!current.enabled) {
            recordSkipped(download.id, download.fileName, "Post-processing automation is disabled.", PostProcessingAutomationTrigger.DownloadCompleted)
            return
        }
        val rules = PostProcessingAutomationPolicy.matchingRules(current, download)
        if (rules.isEmpty()) {
            recordSkipped(download.id, download.fileName, "No matching post-processing rule.", PostProcessingAutomationTrigger.DownloadCompleted)
            return
        }
        rules.forEach { rule ->
            rule.actions.forEach { action -> runAction(rule, action, download) }
        }
    }

    fun runForMedia(capture: MediaCaptureRecord) {
        val current = statusFlow.value
        if (!current.enabled) {
            recordSkipped(capture.id, capture.title, "Post-processing automation is disabled.", PostProcessingAutomationTrigger.MediaCaptured)
            return
        }
        val rules = PostProcessingAutomationPolicy.matchingRules(current, capture)
        if (rules.isEmpty()) {
            recordSkipped(capture.id, capture.title, "No matching media post-processing rule.", PostProcessingAutomationTrigger.MediaCaptured)
            return
        }
        rules.forEach { rule ->
            rule.actions.forEach { action -> runAction(rule, action, capture) }
        }
    }

    fun retryLastFailed() {
        val failed = statusFlow.value.failedEvents.firstOrNull()
        if (failed == null) {
            statusFlow.update { it.copy(lastMessage = "No failed post-processing event to retry.", updatedAtEpochMs = System.currentTimeMillis()) }
        } else {
            statusFlow.update { it.copy(lastMessage = "Retry requested for ${failed.ruleName}; reopen the matching download or media capture to run it again.", updatedAtEpochMs = System.currentTimeMillis()) }
        }
    }

    fun clearEvents() {
        statusFlow.update { it.copy(events = emptyList(), lastMessage = "Post-processing event log cleared.", updatedAtEpochMs = System.currentTimeMillis()) }
    }

    private fun runAction(rule: PostProcessingAutomationRule, action: PostProcessingAutomationAction, download: Download) {
        val trigger = if (download.state.name == "Completed") PostProcessingAutomationTrigger.DownloadCompleted else PostProcessingAutomationTrigger.DownloadFailed
        if (action.kind.requiresRoot) {
            termuxBridge.fixTermuxDownloadPermissions(download.destinationUri)
            recordQueued(rule, trigger, download.id, download.fileName, "Queued typed root action: ${action.summary}", runId = "root-audit")
            return
        }
        val plan = planForDownload(action, download)
        val command = XdmTermuxCommand.PostProcess(plan)
        val launch = runner.run(command)
        if (!launch.started) TermuxRunStore.recordLaunchFailure(appContext, command.operation, launch.error)
        recordQueued(rule, trigger, download.id, download.fileName, if (launch.started) "Queued ${action.summary}." else launch.error, launch.runId, started = launch.started)
    }

    private fun runAction(rule: PostProcessingAutomationRule, action: PostProcessingAutomationAction, capture: MediaCaptureRecord) {
        val trigger = if (capture.downloadId != null) PostProcessingAutomationTrigger.MediaDownloadCreated else PostProcessingAutomationTrigger.MediaCaptured
        when (action.kind) {
            PostProcessingActionKind.FfprobeInspect -> mediaPipeline.inspectWithFfprobe(capture)
            PostProcessingActionKind.RemuxFastStart -> mediaPipeline.convert(capture, ConversionPreset.VideoFastStart)
            PostProcessingActionKind.ExtractAudio -> mediaPipeline.convert(capture, ConversionPreset.AudioExtract)
            PostProcessingActionKind.FixPermissionsWithRoot -> termuxBridge.fixTermuxDownloadPermissions("storage/downloads/XDM")
            else -> {
                val plan = planForMedia(action, capture)
                val command = XdmTermuxCommand.PostProcess(plan)
                val launch = runner.run(command)
                if (!launch.started) TermuxRunStore.recordLaunchFailure(appContext, command.operation, launch.error)
                recordQueued(rule, trigger, capture.id, capture.title, if (launch.started) "Queued ${action.summary}." else launch.error, launch.runId, started = launch.started)
                return
            }
        }
        recordQueued(rule, trigger, capture.id, capture.title, "Queued ${action.summary} through the Termux media pipeline.")
    }

    private fun planForDownload(action: PostProcessingAutomationAction, download: Download): TermuxPostProcessingPlan {
        val input = download.destinationUri.trim().ifBlank { "storage/downloads/XDM/${download.fileName}" }
        val safeBase = sanitizeFileName(download.fileName.substringBeforeLast('.', missingDelimiterValue = download.fileName), fallback = "xdm-download", maxLength = 96)
        val output = action.value
            .replace("{name}", safeBase)
            .replace("{file}", sanitizeFileName(download.fileName, fallback = "xdm-download", maxLength = 120))
            .ifBlank { "storage/downloads/XDM/$safeBase.processed" }
        return TermuxPostProcessingPlan(action.kind, inputPath = input, outputPath = output)
    }

    private fun planForMedia(action: PostProcessingAutomationAction, capture: MediaCaptureRecord): TermuxPostProcessingPlan {
        val input = capture.selectedVariantUrl ?: capture.sourceUrl
        val safeBase = sanitizeFileName(capture.title.ifBlank { capture.fileName }, fallback = "xdm-media", maxLength = 96)
        val output = action.value.replace("{name}", safeBase).ifBlank { "storage/downloads/XDM/Media/$safeBase.processed" }
        return TermuxPostProcessingPlan(action.kind, inputPath = input, outputPath = output)
    }

    private fun recordQueued(
        rule: PostProcessingAutomationRule,
        trigger: PostProcessingAutomationTrigger,
        subjectId: String,
        subjectLabel: String,
        message: String,
        runId: String = "",
        started: Boolean = true,
    ) {
        val now = System.currentTimeMillis()
        val status = if (started) PostProcessingAutomationEventStatus.Queued else PostProcessingAutomationEventStatus.Failed
        val next = event("post-${UUID.randomUUID()}", rule.id, rule.name, trigger, status, subjectId, subjectLabel, message, runId, now)
        statusFlow.update { current -> current.copy(events = (listOf(next) + current.events).take(MaxEvents), lastMessage = message, updatedAtEpochMs = now) }
    }

    private fun recordSkipped(subjectId: String, subjectLabel: String, message: String, trigger: PostProcessingAutomationTrigger) {
        val now = System.currentTimeMillis()
        val skipped = event("post-${UUID.randomUUID()}", "none", "No rule", trigger, PostProcessingAutomationEventStatus.Skipped, subjectId, subjectLabel, message, now = now)
        statusFlow.update { current -> current.copy(events = (listOf(skipped) + current.events).take(MaxEvents), lastMessage = message, updatedAtEpochMs = now) }
    }

    private fun event(
        id: String,
        ruleId: String,
        ruleName: String,
        trigger: PostProcessingAutomationTrigger,
        status: PostProcessingAutomationEventStatus,
        subjectId: String,
        subjectLabel: String,
        message: String,
        runId: String = "",
        now: Long,
    ) = PostProcessingAutomationEvent(
        id = id,
        ruleId = ruleId,
        ruleName = ruleName,
        trigger = trigger,
        status = status,
        subjectId = subjectId,
        subjectLabel = subjectLabel,
        message = message,
        runId = runId,
        createdAtEpochMs = now,
        updatedAtEpochMs = now,
    )

    companion object {
        private const val MaxEvents = 24
    }
}
