package com.mikeyphw.xdm.android.media.ffmpeg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FfprobeJsonParserTest {
    @Test fun parsesFormatAndTypedStreams() {
        val result = FfprobeJsonParser.parse(
            """{"streams":[{"index":0,"codec_name":"h264","codec_type":"video","width":1920,"height":1080},{"index":1,"codec_name":"aac","codec_type":"audio","sample_rate":"48000","channels":2,"tags":{"language":"eng"}}],"format":{"format_name":"mov,mp4","format_long_name":"QuickTime / MOV","duration":"12.500","size":"123456","bit_rate":"79011"}}""",
        )
        assertEquals("mov,mp4", result.formatName)
        assertEquals(12.5, result.durationSeconds!!, 0.001)
        assertEquals(1, result.videoStreams.size)
        assertEquals(1920, result.videoStreams.single().width)
        assertEquals("eng", result.audioStreams.single().language)
        assertTrue(result.subtitleStreams.isEmpty())
    }
}
