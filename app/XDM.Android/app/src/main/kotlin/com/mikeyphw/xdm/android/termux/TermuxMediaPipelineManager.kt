package com.mikeyphw.xdm.android.termux

import android.content.Context
import com.mikeyphw.xdm.android.model.ConversionPreset
import com.mikeyphw.xdm.android.model.MediaCaptureRecord
import com.mikeyphw.xdm.android.util.sanitizeFileName
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

class TermuxMediaPipelineManager(context: Context) {
    private val appContext = context.applicationContext
    private val runner = TermuxCommandRunner(appContext)
    private val statusFlow = MutableStateFlow(TermuxMediaPipelineStatus())

    val status: StateFlow<TermuxMediaPipelineStatus> = statusFlow

    fun refreshStatus() {
        runner.refreshStatus()
        statusFlow.update { it.copy(updatedAtEpochMs = System.currentTimeMillis()) }
    }

    fun extractMetadata(record: MediaCaptureRecord) = launch(
        record = record,
        kind = TermuxMediaJobKind.YtDlpMetadata,
        output = "JSON metadata in Termux run log",
        command = XdmTermuxCommand.YtDlpMetadata(record.sourceUrl),
        message = "Queued yt-dlp metadata extraction for ${record.title}.",
    )

    fun inspectWithFfprobe(record: MediaCaptureRecord) = launch(
        record = record,
        kind = TermuxMediaJobKind.FfprobeInspect,
        output = "FFprobe JSON in Termux run log",
        command = XdmTermuxCommand.FfprobeInspect(record.selectedVariantUrl ?: record.sourceUrl),
        message = "Queued FFprobe inspection for ${record.title}.",
    )

    fun downloadWithYtDlp(record: MediaCaptureRecord, destination: String = DefaultDownloadDir) = launch(
        record = record,
        kind = TermuxMediaJobKind.YtDlpDownload,
        output = destination,
        command = XdmTermuxCommand.YtDlpDownload(
            url = record.sourceUrl,
            destination = destination,
            outputTemplate = sanitizeFileName(record.title.ifBlank { record.fileName }, fallback = "xdm-media", maxLength = 96) + ".%(ext)s",
            format = "bestvideo+bestaudio/best",
        ),
        message = "Queued yt-dlp download for ${record.title}.",
    )

    fun convert(record: MediaCaptureRecord, preset: ConversionPreset, destination: String = DefaultDownloadDir) {
        val input = record.selectedVariantUrl ?: record.sourceUrl
        val safeBase = sanitizeFileName(record.title.ifBlank { record.fileName }, fallback = "xdm-media", maxLength = 96)
        val output = when (preset) {
            ConversionPreset.AudioExtract -> "$destination/$safeBase.m4a"
            ConversionPreset.VideoFastStart -> "$destination/$safeBase.faststart.mp4"
            ConversionPreset.ArchiveExtract -> "$destination/$safeBase.remux.mkv"
            ConversionPreset.CustomCommand -> "$destination/$safeBase.custom.mp4"
            ConversionPreset.None -> "$destination/$safeBase.remux.mp4"
        }
        val kind = when (preset) {
            ConversionPreset.AudioExtract -> TermuxMediaJobKind.FfmpegAudioExtract
            ConversionPreset.VideoFastStart -> TermuxMediaJobKind.FfmpegFastStart
            else -> TermuxMediaJobKind.FfmpegRemux
        }
        launch(
            record = record,
            kind = kind,
            output = output,
            command = XdmTermuxCommand.FfmpegConvert(input = input, output = output, preset = preset.shellPreset()),
            message = "Queued ${kind.label} for ${record.title}.",
        )
    }

    fun clearCompleted() {
        statusFlow.update { current ->
            current.copy(
                jobs = current.jobs.filter { it.status == TermuxMediaJobStatus.Queued || it.status == TermuxMediaJobStatus.Running },
                lastAction = "Cleared completed Termux media jobs.",
                updatedAtEpochMs = System.currentTimeMillis(),
            )
        }
    }

    private fun launch(record: MediaCaptureRecord, kind: TermuxMediaJobKind, output: String, command: XdmTermuxCommand, message: String) {
        val startedAt = System.currentTimeMillis()
        val jobId = "termux-media-${UUID.randomUUID()}"
        val launch = runner.run(command)
        val status = if (launch.started) TermuxMediaJobStatus.Running else TermuxMediaJobStatus.Failed
        if (!launch.started) TermuxRunStore.recordLaunchFailure(appContext, command.operation, launch.error)
        val job = TermuxMediaPipelineJob(
            id = jobId,
            captureId = record.id,
            title = record.title.ifBlank { record.fileName },
            kind = kind,
            status = status,
            input = record.selectedVariantUrl ?: record.sourceUrl,
            output = output,
            runId = launch.runId,
            message = if (launch.started) message else launch.error,
            createdAtEpochMs = startedAt,
            updatedAtEpochMs = startedAt,
        )
        statusFlow.update { current ->
            current.copy(
                lastAction = job.message,
                jobs = (listOf(job) + current.jobs.filterNot { it.id == job.id }).take(MaxJobs),
                updatedAtEpochMs = startedAt,
            )
        }
    }

    private fun ConversionPreset.shellPreset(): String = when (this) {
        ConversionPreset.AudioExtract -> "audio"
        ConversionPreset.VideoFastStart -> "faststart"
        ConversionPreset.ArchiveExtract -> "remux"
        ConversionPreset.CustomCommand -> "remux"
        ConversionPreset.None -> "remux"
    }

    companion object {
        private const val MaxJobs = 12
        private const val DefaultDownloadDir = "storage/downloads/XDM/Media"
    }
}
