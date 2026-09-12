package com.mikeyphw.xdm.android.media.ffmpeg

/** Parses FFmpeg's stable `-progress pipe:1` key/value protocol. */
class FfmpegProgressParser(
    private val expectedDurationMs: Long? = null,
) {
    private val values = linkedMapOf<String, String>()

    fun accept(line: String): FfmpegProgressSnapshot? {
        val separator = line.indexOf('=')
        if (separator <= 0) return null
        val key = line.substring(0, separator).trim()
        val value = line.substring(separator + 1).trim()
        values[key] = value
        if (key != "progress") return null

        val outTimeUs = values["out_time_us"]?.toLongOrNull()
            ?: values["out_time_ms"]?.toLongOrNull()
        val outTimeMs = outTimeUs?.div(1_000L)
        val terminal = value.equals("end", ignoreCase = true)
        val percent = expectedDurationMs
            ?.takeIf { it > 0L }
            ?.let { duration -> (((outTimeMs ?: 0L).coerceAtLeast(0L) * 100L) / duration).toInt().coerceIn(0, if (terminal) 100 else 99) }
        return FfmpegProgressSnapshot(
            phase = if (terminal) FfmpegProgressPhase.Completed else if ((outTimeMs ?: 0L) <= 0L) FfmpegProgressPhase.Preparing else FfmpegProgressPhase.Processing,
            outTimeMs = outTimeMs,
            expectedDurationMs = expectedDurationMs,
            totalSizeBytes = values["total_size"]?.toLongOrNull(),
            frame = values["frame"]?.toLongOrNull(),
            speed = values["speed"]?.takeIf { it.isNotBlank() && it != "N/A" },
            percent = if (terminal) 100 else percent,
        ).also { values.clear() }
    }
}
