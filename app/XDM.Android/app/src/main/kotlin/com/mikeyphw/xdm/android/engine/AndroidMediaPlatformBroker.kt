package com.mikeyphw.xdm.android.engine

import android.content.Context
import com.mikeyphw.xdm.android.media.ffmpeg.EmbeddedFfmpegRuntime
import com.mikeyphw.xdm.android.media.ffmpeg.FfmpegInput
import com.mikeyphw.xdm.android.media.ffmpeg.FfmpegInputFormat
import com.mikeyphw.xdm.android.media.ffmpeg.FfmpegInputKind
import com.mikeyphw.xdm.android.media.ffmpeg.FfmpegOperation
import com.mikeyphw.xdm.android.scheduler.MediaRequestHandoff
import com.mikeyphw.xdm.android.scheduler.MediaRequestHandoffStore
import java.io.File
import org.json.JSONObject

/**
 * Narrow XGO-73 platform broker for app-owned media tooling.
 *
 * Go selects the capture/variant and operation. Android only resolves the encrypted request
 * handoff and executes an explicit typed FFmpeg operation; it never accepts arbitrary argv/shell.
 */
class AndroidMediaPlatformBroker(
    context: Context,
    private val runtime: EmbeddedFfmpegRuntime,
) {
    private val root = File(context.applicationContext.filesDir, "xgo-media-broker").apply { mkdirs() }

    suspend fun execute(payload: JSONObject): JSONObject {
        if (payload.optString("request_kind") != "android_media_execute" || payload.optString("tool") != "ffmpeg") {
            return rejected("unsupported_media_tool_request", "Only typed XGO media FFmpeg requests are supported")
        }
        val captureId = payload.optString("capture_id").trim()
        val variantId = payload.optString("variant_id").trim().takeIf(String::isNotBlank)
        if (captureId.isBlank()) return rejected("invalid_capture_id", "Media capture id is required")
        val handoff = resolveHandoff(captureId, variantId)
            ?: return rejected("request_handoff_missing", "The exact browser request handoff is unavailable; refresh this media capture")
        val inputUrl = handoff.exactUrl?.takeIf(String::isNotBlank)
            ?: return rejected("request_url_missing", "The exact media request URL is unavailable")
        val outputName = sanitizeOutputName(payload.optString("output_name").ifBlank { "$captureId.mp4" })
        val outputDir = File(root, sanitizeSegment(captureId)).apply { mkdirs() }
        val outputFile = File(outputDir, outputName)
        val expectedDurationMs = payload.optLong("expected_duration_ms", 0L).takeIf { it > 0L }
        val protocol = payload.optString("protocol").lowercase()
        val operation = when (payload.optString("operation")) {
            "finalize_adaptive" -> FfmpegOperation.FinalizeAdaptive(
                input = FfmpegInput(
                    source = inputUrl,
                    kind = FfmpegInputKind.Generic,
                    formatHint = if (protocol == "hls") FfmpegInputFormat.Hls else null,
                    headers = handoff.headers,
                ),
                outputFile = outputFile,
                expectedDurationMs = expectedDurationMs,
                overwrite = true,
            )
            "record_stream" -> FfmpegOperation.RecordStream(
                inputUrl = inputUrl,
                outputFile = outputFile,
                headers = handoff.headers,
                durationMs = expectedDurationMs,
                overwrite = true,
            )
            else -> return rejected("unsupported_ffmpeg_operation", "The requested FFmpeg operation is not in the Android media broker allowlist")
        }
        val result = runtime.execute(operation)
        return JSONObject()
            .put("ok", result.success)
            .put("capture_id", captureId)
            .put("variant_id", variantId ?: JSONObject.NULL)
            .put("operation", payload.optString("operation"))
            .put("exit_code", result.exitCode)
            .put("failure_kind", result.failureKind.name)
            .put("message", result.redactedSummary)
            .put("output_path", if (result.success) outputFile.absolutePath else JSONObject.NULL)
            .put("duration_ms", result.durationMs)
            .apply {
                if (!result.success) put("error_code", "ffmpeg_${result.failureKind.name.lowercase()}")
            }
    }

    private fun resolveHandoff(captureId: String, variantId: String?): MediaRequestHandoff? =
        variantId?.let(MediaRequestHandoffStore::forVariant) ?: MediaRequestHandoffStore.forCapture(captureId)

    private fun sanitizeOutputName(value: String): String {
        val cleaned = value.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._ -]"), "_")
            .trim('.', ' ')
            .take(160)
        return cleaned.ifBlank { "media.mp4" }
    }

    private fun sanitizeSegment(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]"), "_").take(96).ifBlank { "capture" }

    private fun rejected(code: String, message: String): JSONObject = JSONObject()
        .put("ok", false)
        .put("error_code", code)
        .put("message", message)
}

interface AndroidMediaPlatformBrokerProvider {
    fun androidMediaPlatformBrokerOrNull(): AndroidMediaPlatformBroker?
}
