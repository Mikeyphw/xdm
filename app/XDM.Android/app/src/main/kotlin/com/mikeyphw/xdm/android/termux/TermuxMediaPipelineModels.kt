package com.mikeyphw.xdm.android.termux

import java.util.Locale

enum class TermuxMediaJobKind(val label: String) {
    YtDlpMetadata("yt-dlp metadata"),
    YtDlpDownload("yt-dlp download"),
    FfprobeInspect("FFprobe inspect"),
    FfmpegFastStart("FFmpeg fast-start"),
    FfmpegAudioExtract("FFmpeg audio extract"),
    FfmpegRemux("FFmpeg remux"),
    VerifySha256("Verify SHA-256"),
    Move("Move output"),
    Rename("Rename output"),
    Cleanup("Cleanup partials"),
    Permissions("Fix permissions"),
    Unknown("Post-processing"),
    ;

    companion object {
        fun fromAction(value: String): TermuxMediaJobKind = when (runCatching { PostProcessingActionKind.valueOf(value) }.getOrNull()) {
            PostProcessingActionKind.FfprobeInspect -> FfprobeInspect
            PostProcessingActionKind.RemuxFastStart -> FfmpegFastStart
            PostProcessingActionKind.ExtractAudio -> FfmpegAudioExtract
            PostProcessingActionKind.VerifySha256 -> VerifySha256
            PostProcessingActionKind.MoveToFolder -> Move
            PostProcessingActionKind.RenameByPattern -> Rename
            PostProcessingActionKind.CleanupPartials -> Cleanup
            PostProcessingActionKind.FixPermissionsWithRoot -> Permissions
            PostProcessingActionKind.YtDlpMetadata -> YtDlpMetadata
            PostProcessingActionKind.YtDlpDownload -> YtDlpDownload
            PostProcessingActionKind.FfmpegRemux -> FfmpegRemux
            null -> when (value) {
                "YtDlpMetadata" -> YtDlpMetadata
                "YtDlpDownload" -> YtDlpDownload
                "FfmpegRemux" -> FfmpegRemux
                else -> Unknown
            }
        }
    }
}

enum class TermuxMediaJobStatus(val label: String, val terminal: Boolean = false) {
    Queued("Queued"),
    WaitingForPrerequisites("Waiting for prerequisites"),
    Preparing("Preparing"),
    Running("Running"),
    Publishing("Publishing"),
    Paused("Paused"),
    Cancelling("Cancelling"),
    Completed("Completed", terminal = true),
    Failed("Failed", terminal = true),
    Cancelled("Cancelled", terminal = true),
    TimedOut("Timed out", terminal = true),
    RecoveryRequired("Recovery required"),
    ;

    companion object {
        fun fromPersistent(value: String): TermuxMediaJobStatus = entries.firstOrNull { it.name == value } ?: RecoveryRequired
    }
}

data class TermuxMediaPipelineJob(
    val id: String,
    val rootJobId: String = id,
    val parentJobId: String? = null,
    val attemptGeneration: Int = 1,
    val captureId: String?,
    val downloadId: String? = null,
    val title: String,
    val kind: TermuxMediaJobKind,
    val status: TermuxMediaJobStatus,
    val input: String,
    val output: String = "",
    val runId: String = "",
    val processId: Int? = null,
    val processToken: String = "",
    val progressPercent: Int = 0,
    val progressBytes: Long = 0L,
    val progressTotalBytes: Long? = null,
    val timeoutAtEpochMs: Long? = null,
    val message: String = "",
    val redactedSession: String = "",
    val createdAtEpochMs: Long = 0L,
    val updatedAtEpochMs: Long = createdAtEpochMs,
) {
    val summary: String get() = listOf(kind.label, status.label, title, "attempt $attemptGeneration").joinToString(" • ")
    val controllable: Boolean get() = status in setOf(TermuxMediaJobStatus.WaitingForPrerequisites, TermuxMediaJobStatus.Preparing, TermuxMediaJobStatus.Running, TermuxMediaJobStatus.Paused)
    val progressLabel: String get() = when {
        progressTotalBytes != null && progressTotalBytes > 0L -> "$progressPercent% • $progressBytes / $progressTotalBytes bytes"
        progressPercent > 0 -> "$progressPercent%"
        else -> status.label
    }
}

data class TermuxMediaPipelineStatus(
    val enabled: Boolean = true,
    val lastAction: String = "Termux media pipeline has not run yet.",
    val jobs: List<TermuxMediaPipelineJob> = emptyList(),
    val updatedAtEpochMs: Long = 0L,
) {
    val activeJobs: Int get() = jobs.count { !it.status.terminal && it.status != TermuxMediaJobStatus.RecoveryRequired }
    val recentJobs: List<TermuxMediaPipelineJob> get() = jobs.sortedByDescending { it.updatedAtEpochMs }.take(12)
    val readinessLabel: String get() = when {
        !enabled -> "Media pipeline disabled"
        activeJobs > 0 -> "$activeJobs active"
        jobs.any { it.status == TermuxMediaJobStatus.RecoveryRequired } -> "Recovery review needed"
        jobs.isNotEmpty() -> "Ready • ${jobs.size} durable jobs"
        else -> "Ready for Termux media"
    }

    fun diagnosticsSummary(): String = buildString {
        appendLine("Termux media pipeline: $readinessLabel")
        appendLine("Enabled: $enabled")
        appendLine("Last action: $lastAction")
        recentJobs.forEach { job ->
            appendLine("${job.kind.name.lowercase(Locale.US)}\t${job.status.name}\t${job.title}\t${job.runId.ifBlank { "no-run-id" }}\tattempt=${job.attemptGeneration}\tprogress=${job.progressPercent}")
            job.processId?.let { appendLine("owner\tpid=$it\ttoken=${job.processToken.take(8)}…") }
            job.redactedSession.takeIf { it.isNotBlank() }?.let { appendLine("session\t$it") }
        }
    }.trim()
}
