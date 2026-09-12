package com.mikeyphw.xdm.android.media.ffmpeg

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FfmpegMediaVerifierTest {
    @Test fun rejectsMissingRequiredAudio() {
        val file = File.createTempFile("xdm-verify-", ".mkv").apply { writeBytes(ByteArray(4096)) }
        try {
            val report = FfmpegMediaVerifier.verify(
                file,
                FfprobeResult("matroska", "Matroska", 12.0, 4096, null, listOf(stream("video"))),
                FfmpegVerificationExpectation(requireVideo = true, requireAudio = true),
            )
            assertFalse(report.valid)
        } finally { file.delete() }
    }

    @Test fun acceptsExpectedMuxedStreams() {
        val file = File.createTempFile("xdm-verify-", ".mkv").apply { writeBytes(ByteArray(4096)) }
        try {
            val report = FfmpegMediaVerifier.verify(
                file,
                FfprobeResult("matroska", "Matroska", 12.0, 4096, null, listOf(stream("video"), stream("audio"))),
                FfmpegVerificationExpectation(requireVideo = true, requireAudio = true, expectedDurationMs = 12_000),
            )
            assertTrue(report.valid)
        } finally { file.delete() }
    }


    @Test fun rejectsTruncatedArtifactBelowMinimumBytes() {
        val file = File.createTempFile("xdm-verify-tiny-", ".mkv").apply { writeBytes(ByteArray(64)) }
        try {
            val report = FfmpegMediaVerifier.verify(
                file,
                FfprobeResult("matroska", "Matroska", 1.0, 64, null, listOf(stream("video"), stream("audio"))),
                FfmpegVerificationExpectation(requireVideo = true, requireAudio = true, minimumBytes = 1024),
            )
            assertFalse(report.valid)
        } finally { file.delete() }
    }

    private fun stream(kind: String) = FfprobeStream(0, kind, null, null, null, null, null, null, null, null)
}
