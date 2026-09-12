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

    @Test fun remoteMuxUsesTypedInputsProgressAndStreamCopy() {
        val ca = File.createTempFile("xdm-ca-", ".pem").apply { writeText("test-ca") }
        try {
            val command = FfmpegCommandCompiler.compile(
                FfmpegOperation.MuxRemoteTracks(
                    inputs = listOf(
                        FfmpegInput("https://media.example/video.m4s", FfmpegInputKind.Video, tlsCaFile = ca),
                        FfmpegInput("https://media.example/audio.m4s", FfmpegInputKind.Audio, tlsCaFile = ca),
                    ),
                    outputFile = File("/tmp/final.mkv"),
                    expectedDurationMs = 10_000,
                ),
            )
            assertTrue(command.progressEnabled)
            assertEquals(10_000L, command.expectedDurationMs)
            assertTrue(command.arguments.windowed(2).contains(listOf("-progress", "pipe:1")))
            assertTrue(command.arguments.windowed(2).contains(listOf("-c", "copy")))
            assertTrue(command.arguments.windowed(2).contains(listOf("-map", "0:v:0?")))
            assertTrue(command.arguments.windowed(2).contains(listOf("-map", "1:a:0?")))
        } finally { ca.delete() }
    }


    @Test fun remoteAudioExtractionMapsAudioOnlyWithProgress() {
        val command = FfmpegCommandCompiler.compile(
            FfmpegOperation.ExtractRemoteAudio(
                input = FfmpegInput("http://media.example/audio.m4s", FfmpegInputKind.Audio),
                outputFile = File("/tmp/audio.m4a"),
                expectedDurationMs = 20_000,
            ),
        )
        assertTrue(command.progressEnabled)
        assertTrue(command.arguments.windowed(2).contains(listOf("-map", "0:a:0?")))
        assertTrue(command.arguments.contains("-vn"))
        assertTrue(command.arguments.windowed(2).contains(listOf("-c:a", "copy")))
    }

    @Test fun hlsFinalizationUsesConcatDemuxerAndStreamCopy() {
        val concat = File.createTempFile("xdm-hls-", ".ffconcat").apply { writeText("ffconcat version 1.0\nfile '/tmp/part.ts'\n") }
        try {
            val command = FfmpegCommandCompiler.compile(
                FfmpegOperation.FinalizeHlsSegments(concat, File("/tmp/final.mkv"), expectedDurationMs = 30_000),
            )
            assertTrue(command.arguments.windowed(2).contains(listOf("-f", "concat")))
            assertTrue(command.arguments.windowed(2).contains(listOf("-safe", "0")))
            assertTrue(command.arguments.windowed(2).contains(listOf("-c", "copy")))
            assertTrue(command.progressEnabled)
        } finally { concat.delete() }
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
    @Test fun remoteAudioExtractionSelectsAudioOnlyAndStreamCopies() {
        val command = FfmpegCommandCompiler.compile(
            FfmpegOperation.ExtractRemoteAudio(
                input = FfmpegInput("http://media.example/audio.m4a", FfmpegInputKind.Audio),
                outputFile = File("/tmp/audio.m4a"),
                expectedDurationMs = 8_000,
            ),
        )
        assertTrue(command.progressEnabled)
        assertTrue(command.arguments.windowed(2).contains(listOf("-map", "0:a:0?")))
        assertTrue(command.arguments.windowed(2).contains(listOf("-c:a", "copy")))
        assertTrue(command.arguments.containsAll(listOf("-vn", "-sn", "-dn")))
    }

    @Test fun nativeHlsConcatFinalizationUsesTypedConcatInputAndStreamCopy() {
        val concat = File.createTempFile("xdm-hls-", ".ffconcat").apply {
            writeText("ffconcat version 1.0\nfile '/tmp/seg-0.ts'\n")
        }
        try {
            val command = FfmpegCommandCompiler.compile(
                FfmpegOperation.FinalizeHlsSegments(
                    concatFile = concat,
                    outputFile = File("/tmp/final.mkv"),
                    expectedDurationMs = 12_000,
                    overwrite = true,
                ),
            )
            assertTrue(command.progressEnabled)
            assertTrue(command.arguments.windowed(2).contains(listOf("-f", "concat")))
            assertTrue(command.arguments.windowed(2).contains(listOf("-safe", "0")))
            assertTrue(command.arguments.windowed(2).contains(listOf("-c", "copy")))
            assertFalse(command.arguments.contains("-movflags"))
        } finally { concat.delete() }
    }

    @Test fun concatManifestCanonicalizesAndEscapesQuotedPaths() {
        val directory = kotlin.io.path.createTempDirectory("xdm-hls-manifest-").toFile()
        val first = File(directory, "part 1.ts").apply { writeBytes(byteArrayOf(1)) }
        val second = File(directory, "part'2.ts").apply { writeBytes(byteArrayOf(2)) }
        try {
            val manifest = FfmpegConcatManifest.render(listOf(first, second))
            assertTrue(manifest.startsWith("ffconcat version 1.0"))
            assertTrue(manifest.contains(first.canonicalPath))
            assertTrue(manifest.contains("part'\\''2.ts"))
        } finally { directory.deleteRecursively() }
    }

    @Test(expected = IllegalArgumentException::class)
    fun fastStartRejectsNonMovContainer() {
        FfmpegCommandCompiler.compile(
            FfmpegOperation.FastStart(File("/tmp/input.mkv"), File("/tmp/output.mkv")),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun concatManifestRejectsControlDelimiterPaths() {
        val directory = kotlin.io.path.createTempDirectory("xdm-hls-invalid-").toFile()
        val segment = File(directory, "part\n2.ts").apply { writeBytes(byteArrayOf(1)) }
        try {
            FfmpegConcatManifest.render(listOf(segment))
        } finally {
            directory.deleteRecursively()
        }
    }

}
