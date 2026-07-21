package com.mikeyphw.xdm.android.termux

import java.util.Locale

enum class TermuxMediaJobKind(val label: String) {
    YtDlpMetadata("yt-dlp metadata"),
    YtDlpDownload("yt-dlp download"),
    FfprobeInspect("FFprobe inspect"),
    FfmpegFastStart("FFmpeg fast-start"),
    FfmpegAudioExtract("FFmpeg audio extract"),
    FfmpegRemux("FFmpeg remux"),
}

enum class TermuxMediaJobStatus(val label: String) {
    Queued("Queued"),
    Running("Running"),
    Completed("Completed"),
    Failed("Failed"),
}

data class TermuxMediaPipelineJob(
    val id: String,
    val captureId: String?,
    val title: String,
    val kind: TermuxMediaJobKind,
    val status: TermuxMediaJobStatus,
    val input: String,
    val output: String = "",
    val runId: String = "",
    val message: String = "",
    val createdAtEpochMs: Long = 0L,
    val updatedAtEpochMs: Long = createdAtEpochMs,
) {
    val summary: String get() = listOf(kind.label, status.label, title).joinToString(" • ")
}

data class TermuxMediaPipelineStatus(
    val enabled: Boolean = true,
    val lastAction: String = "Termux media pipeline has not run yet.",
    val jobs: List<TermuxMediaPipelineJob> = emptyList(),
    val updatedAtEpochMs: Long = 0L,
) {
    val activeJobs: Int get() = jobs.count { it.status == TermuxMediaJobStatus.Queued || it.status == TermuxMediaJobStatus.Running }
    val recentJobs: List<TermuxMediaPipelineJob> get() = jobs.sortedByDescending { it.updatedAtEpochMs }.take(6)
    val readinessLabel: String get() = when {
        !enabled -> "Media pipeline disabled"
        activeJobs > 0 -> "$activeJobs running"
        jobs.isNotEmpty() -> "Ready • ${jobs.size} recent"
        else -> "Ready for Termux media"
    }

    fun diagnosticsSummary(): String = buildString {
        appendLine("Termux media pipeline: $readinessLabel")
        appendLine("Enabled: $enabled")
        appendLine("Last action: $lastAction")
        recentJobs.forEach { job ->
            appendLine("${job.kind.name.lowercase(Locale.US)}	${job.status.name}	${job.title}	${job.runId.ifBlank { "no-run-id" }}")
        }
    }.trim()
}
