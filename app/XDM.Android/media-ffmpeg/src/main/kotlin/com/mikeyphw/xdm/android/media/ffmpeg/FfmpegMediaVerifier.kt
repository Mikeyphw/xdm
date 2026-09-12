package com.mikeyphw.xdm.android.media.ffmpeg

import java.io.File
import kotlin.math.abs

object FfmpegMediaVerifier {
    fun verify(
        file: File,
        probe: FfprobeResult,
        expectation: FfmpegVerificationExpectation,
    ): FfmpegVerificationReport {
        val bytes = file.takeIf(File::isFile)?.length() ?: 0L
        val durationMs = probe.durationSeconds?.let { (it * 1_000.0).toLong() }
        val failures = buildList {
            if (bytes < expectation.minimumBytes) add("output is too small ($bytes bytes)")
            if (expectation.requireAnyStream && probe.streams.isEmpty()) add("no media streams were detected")
            if (expectation.requireVideo && probe.videoStreams.isEmpty()) add("video stream is missing")
            if (expectation.requireAudio && probe.audioStreams.isEmpty()) add("audio stream is missing")
            if (expectation.requireSubtitle && probe.subtitleStreams.isEmpty()) add("subtitle stream is missing")
            val expected = expectation.expectedDurationMs
            if (expected != null && expected > 5_000L && durationMs != null) {
                val tolerance = (expected / 20L).coerceAtLeast(2_000L)
                if (abs(durationMs - expected) > tolerance) {
                    add("duration differs from expected media by more than ${tolerance}ms")
                }
            }
        }
        return FfmpegVerificationReport(
            valid = failures.isEmpty(),
            fileBytes = bytes,
            videoStreams = probe.videoStreams.size,
            audioStreams = probe.audioStreams.size,
            subtitleStreams = probe.subtitleStreams.size,
            durationMs = durationMs,
            message = if (failures.isEmpty()) {
                "Verified ${probe.streams.size} stream(s), $bytes bytes${durationMs?.let { ", ${it}ms" }.orEmpty()}."
            } else {
                failures.joinToString("; ")
            },
        )
    }
}
