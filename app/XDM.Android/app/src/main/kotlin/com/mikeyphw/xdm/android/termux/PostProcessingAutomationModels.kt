package com.mikeyphw.xdm.android.termux

import com.mikeyphw.xdm.android.model.BackendType
import com.mikeyphw.xdm.android.model.Download
import com.mikeyphw.xdm.android.model.DownloadState
import com.mikeyphw.xdm.android.model.MediaCaptureRecord
import java.net.URI
import java.util.Locale

enum class PostProcessingAutomationTrigger(val label: String) {
    DownloadCompleted("Download completed"),
    DownloadFailed("Download failed"),
    MediaCaptured("Media captured"),
    MediaDownloadCreated("Media download created"),
}

enum class PostProcessingConditionKind(val label: String) {
    FileExtension("File extension"),
    MimeType("MIME type"),
    SourceHost("Source host"),
    Backend("Backend"),
    MinimumSizeBytes("Minimum size"),
}

enum class PostProcessingActionKind(val label: String, val requiresTermux: Boolean = true, val requiresRoot: Boolean = false) {
    MoveToFolder("Move to folder", requiresTermux = false),
    RenameByPattern("Rename by pattern", requiresTermux = false),
    VerifySha256("Verify SHA-256", requiresTermux = false),
    FfprobeInspect("FFprobe inspect"),
    RemuxFastStart("Fast-start MP4"),
    ExtractAudio("Extract audio"),
    CleanupPartials("Clean partials", requiresTermux = false),
    FixPermissionsWithRoot("Fix permissions", requiresRoot = true),
    YtDlpMetadata("yt-dlp metadata"),
    YtDlpDownload("yt-dlp download"),
    FfmpegRemux("FFmpeg remux"),
}

enum class PostProcessingAutomationEventStatus(val label: String) {
    Preview("Preview"),
    Queued("Queued"),
    Completed("Completed"),
    Failed("Failed"),
    Skipped("Skipped"),
}

data class PostProcessingAutomationCondition(
    val kind: PostProcessingConditionKind,
    val value: String,
) {
    val summary: String get() = "${kind.label}: $value"
}

data class PostProcessingAutomationAction(
    val kind: PostProcessingActionKind,
    val value: String = "",
) {
    val summary: String get() = if (value.isBlank()) kind.label else "${kind.label}: $value"
}

data class PostProcessingAutomationRule(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val trigger: PostProcessingAutomationTrigger,
    val conditions: List<PostProcessingAutomationCondition> = emptyList(),
    val actions: List<PostProcessingAutomationAction> = emptyList(),
) {
    val summary: String get() = buildString {
        append(if (enabled) "Enabled" else "Disabled")
        append(" • ")
        append(trigger.label)
        append(" • ")
        append(actions.joinToString { it.kind.label }.ifBlank { "No actions" })
    }
}

data class TermuxPostProcessingPlan(
    val kind: PostProcessingActionKind,
    val inputPath: String,
    val outputPath: String = "",
    val expectedSha256: String = "",
    val formatSelector: String = "",
    val extraArguments: List<String> = emptyList(),
) {
    val summary: String get() = listOf(kind.label, inputPath, outputPath.ifBlank { null }).filterNotNull().joinToString(" • ")
}

data class PostProcessingAutomationEvent(
    val id: String,
    val ruleId: String,
    val ruleName: String,
    val trigger: PostProcessingAutomationTrigger,
    val status: PostProcessingAutomationEventStatus,
    val subjectId: String,
    val subjectLabel: String,
    val message: String,
    val runId: String = "",
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long = createdAtEpochMs,
) {
    val summary: String get() = "${status.label} • $ruleName • $subjectLabel"
}


data class PostProcessingActionAvailability(
    val canRun: Boolean,
    val reason: String,
)

data class PostProcessingAutomationStatus(
    val enabled: Boolean = true,
    val rules: List<PostProcessingAutomationRule> = PostProcessingAutomationPolicy.defaultRules(),
    val events: List<PostProcessingAutomationEvent> = emptyList(),
    val lastMessage: String = "Post-processing automation has not run yet.",
    val updatedAtEpochMs: Long = 0L,
) {
    val enabledRules: List<PostProcessingAutomationRule> get() = rules.filter { it.enabled }
    val recentEvents: List<PostProcessingAutomationEvent> get() = events.sortedByDescending { it.updatedAtEpochMs }.take(6)
    val failedEvents: List<PostProcessingAutomationEvent> get() = events.filter { it.status == PostProcessingAutomationEventStatus.Failed }
    val readinessLabel: String get() = when {
        !enabled -> "Automation disabled"
        enabledRules.isEmpty() -> "No enabled rules"
        failedEvents.isNotEmpty() -> "${failedEvents.size} need review"
        recentEvents.isNotEmpty() -> "${enabledRules.size} rules • ${recentEvents.size} recent"
        else -> "${enabledRules.size} rules ready"
    }

    fun diagnosticsSummary(): String = buildString {
        appendLine("Post-processing automation: $readinessLabel")
        appendLine("Enabled: $enabled")
        appendLine("Rules: ${rules.size}")
        appendLine("Enabled rules: ${enabledRules.size}")
        appendLine("Last message: $lastMessage")
        rules.forEach { rule -> appendLine("Rule ${rule.id}: ${rule.summary}") }
        recentEvents.forEach { event -> appendLine("Event ${event.id}: ${event.summary} ${event.runId.ifBlank { "no-run-id" }}") }
    }.trim()
}

object PostProcessingAutomationPolicy {
    fun defaultRules(): List<PostProcessingAutomationRule> = listOf(
        PostProcessingAutomationRule(
            id = "rule-media-faststart",
            name = "Fast-start completed MP4 videos",
            enabled = true,
            trigger = PostProcessingAutomationTrigger.DownloadCompleted,
            conditions = listOf(PostProcessingAutomationCondition(PostProcessingConditionKind.FileExtension, "mp4")),
            actions = listOf(PostProcessingAutomationAction(PostProcessingActionKind.RemuxFastStart, "{name}.faststart.mp4")),
        ),
        PostProcessingAutomationRule(
            id = "rule-media-ffprobe",
            name = "Inspect completed media",
            enabled = true,
            trigger = PostProcessingAutomationTrigger.MediaDownloadCreated,
            conditions = listOf(PostProcessingAutomationCondition(PostProcessingConditionKind.MimeType, "video/*")),
            actions = listOf(PostProcessingAutomationAction(PostProcessingActionKind.FfprobeInspect)),
        ),
        PostProcessingAutomationRule(
            id = "rule-clean-partials",
            name = "Clean failed partial markers",
            enabled = false,
            trigger = PostProcessingAutomationTrigger.DownloadFailed,
            actions = listOf(PostProcessingAutomationAction(PostProcessingActionKind.CleanupPartials)),
        ),
    )

    fun matchingRules(status: PostProcessingAutomationStatus, download: Download): List<PostProcessingAutomationRule> {
        val trigger = when (download.state) {
            DownloadState.Completed -> PostProcessingAutomationTrigger.DownloadCompleted
            DownloadState.Failed, DownloadState.Cancelled, DownloadState.RecoveryRequired -> PostProcessingAutomationTrigger.DownloadFailed
            else -> return emptyList()
        }
        return status.enabledRules.filter { it.trigger == trigger && it.conditions.all { condition -> condition.matches(download) } }
    }

    fun matchingRules(status: PostProcessingAutomationStatus, capture: MediaCaptureRecord): List<PostProcessingAutomationRule> {
        val trigger = when {
            capture.downloadId != null -> PostProcessingAutomationTrigger.MediaDownloadCreated
            else -> PostProcessingAutomationTrigger.MediaCaptured
        }
        return status.enabledRules.filter { it.trigger == trigger && it.conditions.all { condition -> condition.matches(capture) } }
    }

    fun preview(download: Download, status: PostProcessingAutomationStatus): String {
        val rules = matchingRules(status, download)
        return if (rules.isEmpty()) "No post-processing rule matches ${download.fileName}." else rules.joinToString(separator = "\n") { rule -> "${rule.name}: ${rule.actions.joinToString { it.summary }}" }
    }

    fun preview(capture: MediaCaptureRecord, status: PostProcessingAutomationStatus): String {
        val rules = matchingRules(status, capture)
        return if (rules.isEmpty()) "No media post-processing rule matches ${capture.title}." else rules.joinToString(separator = "\n") { rule -> "${rule.name}: ${rule.actions.joinToString { it.summary }}" }
    }

    fun availabilityFor(
        download: Download,
        status: PostProcessingAutomationStatus,
        bridge: TermuxBridgeStatus,
    ): PostProcessingActionAvailability {
        val rules = matchingRules(status, download)
        if (rules.isEmpty()) return PostProcessingActionAvailability(false, "No enabled post-processing rule matches this download.")
        val actions = rules.flatMap(PostProcessingAutomationRule::actions)
        val termuxActions = actions.filter { it.kind.requiresTermux }
        if (termuxActions.isEmpty()) return PostProcessingActionAvailability(true, "Configured Android-owned actions are ready.")
        if (!bridge.termuxInstalled) return PostProcessingActionAvailability(false, "Install Termux before running the configured actions.")
        if (!bridge.runCommandPermissionGranted) return PostProcessingActionAvailability(false, "Grant Termux RUN_COMMAND permission before running the configured actions.")
        if (termuxActions.any { it.kind.requiresRoot } && !bridge.canRunRootAction) {
            return PostProcessingActionAvailability(false, "Run a successful root probe and enable a reviewed root mode first.")
        }
        if (!bridge.hasFreshSuccessfulToolProbe()) {
            return PostProcessingActionAvailability(false, "Run a fresh Termux tool and capability probe first.")
        }
        val required = termuxActions.flatMap { requiredToolsFor(it.kind) }.toSet()
        val unavailable = required.filter { tool ->
            bridge.toolRows.none { row ->
                row.tool == tool && row.available && row.executablePath.isNotBlank() && row.versionLine.isNotBlank() && row.versionLine != "Not probed yet"
            }
        }
        if (unavailable.isNotEmpty()) {
            return PostProcessingActionAvailability(false, "Unavailable or unverified tools: ${unavailable.joinToString { it.displayName }}.")
        }
        val unsupportedMuxer = termuxActions.firstNotNullOfOrNull { action ->
            val extension = action.value.substringAfterLast('.', "").lowercase(Locale.US)
            extension.takeIf {
                action.kind in setOf(PostProcessingActionKind.RemuxFastStart, PostProcessingActionKind.ExtractAudio, PostProcessingActionKind.FfmpegRemux) &&
                    it.isNotBlank() && bridge.ffmpegMuxers.isNotEmpty() && it !in bridge.ffmpegMuxers
            }
        }
        if (unsupportedMuxer != null) {
            return PostProcessingActionAvailability(false, "The probed FFmpeg build does not advertise the $unsupportedMuxer output muxer.")
        }
        return PostProcessingActionAvailability(true, "Configured tools and capabilities were verified by a fresh probe.")
    }

    fun requiredToolsFor(kind: PostProcessingActionKind): Set<ExternalTool> = when (kind) {
        PostProcessingActionKind.FfprobeInspect -> setOf(ExternalTool.Ffprobe)
        PostProcessingActionKind.RemuxFastStart, PostProcessingActionKind.ExtractAudio, PostProcessingActionKind.FfmpegRemux -> setOf(ExternalTool.Ffmpeg, ExternalTool.Ffprobe)
        PostProcessingActionKind.YtDlpMetadata -> setOf(ExternalTool.YtDlp)
        PostProcessingActionKind.YtDlpDownload -> setOf(ExternalTool.YtDlp, ExternalTool.Ffmpeg, ExternalTool.Ffprobe)
        else -> emptySet()
    }

    private fun PostProcessingAutomationCondition.matches(download: Download): Boolean = when (kind) {
        PostProcessingConditionKind.FileExtension -> download.fileName.extensionEquals(value)
        PostProcessingConditionKind.MimeType -> download.mimeType.matchesMimePattern(value)
        PostProcessingConditionKind.SourceHost -> download.sourceUrl.hostEquals(value)
        PostProcessingConditionKind.Backend -> runCatching { BackendType.valueOf(value) }.getOrNull() == download.backend || download.backend.name.equals(value, ignoreCase = true)
        PostProcessingConditionKind.MinimumSizeBytes -> download.totalBytes?.let { it >= value.toLongOrNull().orZero() } ?: false
    }

    private fun PostProcessingAutomationCondition.matches(capture: MediaCaptureRecord): Boolean = when (kind) {
        PostProcessingConditionKind.FileExtension -> capture.fileName.extensionEquals(value)
        PostProcessingConditionKind.MimeType -> capture.mimeType.matchesMimePattern(value)
        PostProcessingConditionKind.SourceHost -> (capture.pageUrl ?: capture.sourceUrl).hostEquals(value)
        PostProcessingConditionKind.Backend -> true
        PostProcessingConditionKind.MinimumSizeBytes -> false
    }

    private fun String.extensionEquals(expected: String): Boolean {
        val normalized = expected.trim().removePrefix(".").lowercase(Locale.US)
        return substringAfterLast('.', missingDelimiterValue = "").lowercase(Locale.US) == normalized
    }

    private fun String?.matchesMimePattern(pattern: String): Boolean {
        val mime = this?.lowercase(Locale.US)?.takeIf { it.isNotBlank() } ?: return false
        val lower = pattern.lowercase(Locale.US).trim()
        return when {
            lower.endsWith("/*") -> mime.startsWith(lower.removeSuffix("*"))
            else -> mime == lower
        }
    }

    private fun String.hostEquals(expected: String): Boolean {
        val host = runCatching { URI(this).host?.lowercase(Locale.US) }.getOrNull().orEmpty()
        val wanted = expected.trim().lowercase(Locale.US).removePrefix("www.")
        return host.removePrefix("www.") == wanted || host.endsWith(".$wanted")
    }

    private fun Long?.orZero(): Long = this ?: 0L
}
