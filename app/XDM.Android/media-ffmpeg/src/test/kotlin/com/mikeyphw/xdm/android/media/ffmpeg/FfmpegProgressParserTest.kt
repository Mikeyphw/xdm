package com.mikeyphw.xdm.android.media.ffmpeg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FfmpegProgressParserTest {
    @Test fun parsesMachineReadableProgressAndPercent() {
        val parser = FfmpegProgressParser(expectedDurationMs = 10_000)
        assertNull(parser.accept("frame=42"))
        assertNull(parser.accept("out_time_us=5000000"))
        assertNull(parser.accept("total_size=123456"))
        assertNull(parser.accept("speed=1.25x"))
        val snapshot = parser.accept("progress=continue")!!
        assertEquals(FfmpegProgressPhase.Processing, snapshot.phase)
        assertEquals(5_000L, snapshot.outTimeMs)
        assertEquals(50, snapshot.percent)
        assertEquals(123456L, snapshot.totalSizeBytes)
        assertEquals("1.25x", snapshot.speed)
    }

    @Test fun terminalProgressAlwaysReachesOneHundred() {
        val parser = FfmpegProgressParser(expectedDurationMs = null)
        val snapshot = parser.accept("progress=end")!!
        assertEquals(FfmpegProgressPhase.Completed, snapshot.phase)
        assertEquals(100, snapshot.percent)
    }
}
