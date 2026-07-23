package com.mikeyphw.xdm.android.termux

import android.content.Context
import com.mikeyphw.xdm.android.media.MediaDownloadPlanner
import com.mikeyphw.xdm.android.media.MediaSessionHeader
import com.mikeyphw.xdm.android.media.MediaTrackSelection
import com.mikeyphw.xdm.android.model.ConversionPreset
import com.mikeyphw.xdm.android.model.MediaCaptureRecord
import com.mikeyphw.xdm.android.model.MediaVariant
import com.mikeyphw.xdm.android.util.sanitizeFileName
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

class TermuxMediaPipelineManager(context: Context) {
    private val appContext = context.applicationContext
    private val runner = TermuxCommandRunner(appContext)
    private val planner = MediaDownloadPlanner()
    private val statusFlow = MutableStateFlow(TermuxMediaPipelineStatus())

    val status: StateFlow<TermuxMediaPipelineStatus> = statusFlow

    fun refreshStatus() {
        runner.refreshStatus()
        statusFlow.update { it.copy(updatedAtEpochMs = System.currentTimeMillis()) }
    }

    fun extractMetadata(record: MediaCaptureRecord, variants: List<MediaVariant> = emptyList(), selection: MediaTrackSelection = MediaTrackSelection()) {
        val plan = planner.plan(record, variants, selection = selection, sessionHeaders = MediaDownloadPlanner.defaultSessionHeaders(record))
        val probeUrl = plan.metadataProbeUrl.takeIf { it.isNotBlank() } ?: metadataProbeUrl(record)
        launch(
            record = record,
            kind = TermuxMediaJobKind.YtDlpMetadata,
            output = "JSON metadata in Termux run log",
            command = XdmTermuxCommand.YtDlpMetadata(probeUrl, plan.sessionHandoff.ytdlpArguments()),
            message = "Queued yt-dlp metadata extraction for ${record.title} using ${if (probeUrl == record.pageUrl) "page URL" else "stream URL"}; ${plan.sessionHandoff.redactedSummary}.",
            inputOverride = probeUrl,
            redactedSession = plan.sessionHandoff.redactedSummary,
        )
    }

    fun inspectWithFfprobe(record: MediaCaptureRecord) = launch(
        record = record,
        kind = TermuxMediaJobKind.FfprobeInspect,
        output = "FFprobe JSON in Termux run log",
        command = XdmTermuxCommand.FfprobeInspect(record.selectedVariantUrl ?: record.sourceUrl),
        message = "Queued FFprobe inspection for ${record.title}.",
    )

    fun downloadWithYtDlp(
        record: MediaCaptureRecord,
        variants: List<MediaVariant> = emptyList(),
        selection: MediaTrackSelection = MediaTrackSelection(videoVariantId = record.selectedVariantId),
        destination: String = DefaultDownloadDir,
    ) {
        val plan = planner.plan(
            capture = record,
            variants = variants,
            selection = selection,
            sessionHeaders = MediaDownloadPlanner.defaultSessionHeaders(record) + sessionHintHeaders(record),
        )
        launch(
            record = record,
            kind = TermuxMediaJobKind.YtDlpDownload,
            output = destination,
            command = XdmTermuxCommand.YtDlpDownload(
                url = plan.metadataProbeUrl,
                destination = destination,
                outputTemplate = sanitizeFileName(record.title.ifBlank { record.fileName }, fallback = "xdm-media", maxLength = 96) + ".%(ext)s",
                format = plan.ytDlpFormatSelector ?: "bestvideo+bestaudio/best",
                extraArguments = plan.sessionHandoff.ytdlpArguments(),
            ),
            message = "Queued yt-dlp download for ${record.title}; format=${plan.ytDlpFormatSelector ?: "best"}; ${plan.sessionHandoff.redactedSummary}.",
            inputOverride = plan.metadataProbeUrl,
            redactedSession = plan.sessionHandoff.redactedSummary,
        )
    }

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

    private fun launch(record: MediaCaptureRecord, kind: TermuxMediaJobKind, output: String, command: XdmTermuxCommand, message: String, inputOverride: String? = null, redactedSession: String = "") {
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
            input = inputOverride ?: record.selectedVariantUrl ?: record.sourceUrl,
            output = output,
            runId = launch.runId,
            message = if (launch.started) message else launch.error,
            redactedSession = redactedSession,
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

    private fun metadataProbeUrl(record: MediaCaptureRecord): String = planner.plan(record, emptyList()).metadataProbeUrl

    private fun sessionHintHeaders(record: MediaCaptureRecord): List<MediaSessionHeader> = buildList {
        record.pageUrl?.takeIf { it.isNotBlank() }?.let { page ->
            runCatching { URI(page) }.getOrNull()?.let { uri ->
                if (!uri.scheme.isNullOrBlank() && !uri.host.isNullOrBlank()) {
                    add(MediaSessionHeader("Origin", "${uri.scheme}://${uri.host}"))
                }
            }
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
