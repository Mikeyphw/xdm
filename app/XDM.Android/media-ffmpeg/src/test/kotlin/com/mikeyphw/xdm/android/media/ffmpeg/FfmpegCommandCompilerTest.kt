package com.mikeyphw.xdm.android.media.ffmpeg

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FfmpegCommandCompilerTest {
    @Test fun recordStreamUsesTypedArgumentsVerifiedTlsAndStreamCopy() {
        val ca = File.createTempFile("xdm-ca-", ".pem").apply { writeText("test-ca") }
        try {
            val command = FfmpegCommandCompiler.compile(
                FfmpegOperation.RecordStream(
                    inputUrl = "https://media.example/live.m3u8?token=secret",
                    outputFile = File("/tmp/out.mp4"),
                    headers = mapOf("Referer" to "https://example.test/", "User-Agent" to "XDM"),
                    tlsCaFile = ca,
                    durationMs = 1500,
                ),
            )
            assertEquals(FfmpegBinary.Ffmpeg, command.binary)
            assertTrue(command.arguments.containsAll(listOf("-i", "-map", "-c", "copy", "-headers", "-tls_verify", "1", "-ca_file")))
            assertEquals("/tmp/out.mp4", command.arguments.last())
            assertTrue(command.arguments.windowed(2).contains(listOf("-movflags", "+faststart")))
            assertFalse(command.arguments.any { it.contains("sh -c") })
        } finally {
            ca.delete()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun httpsInputRejectsMissingTrustBundle() {
        FfmpegCommandCompiler.compile(
            FfmpegOperation.RecordStream("https://media.example/live.m3u8", File("/tmp/out.mkv")),
        )
    }

    @Test fun matroskaRecordingDoesNotReceiveMovOnlyFlags() {
        val command = FfmpegCommandCompiler.compile(
            FfmpegOperation.RecordStream("http://media.example/live.m3u8", File("/tmp/out.mkv")),
        )
        assertFalse(command.arguments.contains("-movflags"))
        assertEquals("/tmp/out.mkv", command.arguments.last())
    }

    @Test(expected = IllegalArgumentException::class)
    fun headerValuesRejectCrLfInjection() {
        FfmpegCommandCompiler.compile(
            FfmpegOperation.Probe("http://example.test/a.mp4", mapOf("Cookie" to "a=b\r\nInjected: yes")),
        )
    }

    @Test fun muxTracksMapsVideoAndAudioWithoutTranscoding() {
        val command = FfmpegCommandCompiler.compile(
            FfmpegOperation.MuxTracks(File("/tmp/video.mp4"), File("/tmp/audio.m4a"), File("/tmp/final.mp4")),
        )
        assertTrue(command.arguments.windowed(2).contains(listOf("-c", "copy")))
        assertTrue(command.arguments.windowed(2).contains(listOf("-map", "0:v:0")))
        assertTrue(command.arguments.windowed(2).contains(listOf("-map", "1:a:0")))
    }

    @Test fun diagnosticSummaryRedactsUrlQueriesAndAuthorization() {
        val result = FfmpegExecutionResult(
            exitCode = 1,
            stdout = "",
            stderr = "",
            durationMs = 1,
            failureKind = FfmpegFailureKind.Authentication,
            message = "https://example.test/live.m3u8?token=supersecret Authorization:Bearer supersecret",
        )
        assertFalse(result.redactedSummary.contains("supersecret"))
        assertTrue(result.redactedSummary.contains("<redacted"))
    }
}
