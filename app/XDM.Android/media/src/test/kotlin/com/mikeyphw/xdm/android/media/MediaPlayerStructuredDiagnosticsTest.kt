package com.mikeyphw.xdm.android.media

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaPlayerStructuredDiagnosticsTest {
    private val candidate = MediaPlaybackCandidate(
        captureId = "capture-structured-error",
        title = "Example",
        playbackUrl = "content://downloads/example.mp4",
        isAdaptive = false,
        needsExternalResolver = false,
        subtitleCount = 0,
        audioTrackCount = 1,
    )

    @Test
    fun freeTextCannotPromoteUnknownFailureIntoNetworkOrDecoderBucket() {
        val report = MediaPlayerDiagnosticsPlanner().report(
            candidate = candidate,
            error = MediaPlayerErrorSnapshot(
                errorCodeName = "ERROR_CODE_UNSPECIFIED",
                errorCode = 1000,
                causeClassName = "java.lang.IllegalStateException",
                message = "network timeout decoder unsupported",
                playbackStateLabel = "idle",
                playWhenReady = false,
                suppressionReasonLabel = null,
            ),
        )
        assertEquals(MediaPlayerDiagnosticBucket.Unknown, report.bucket)
    }

    @Test
    fun structuredMedia3NetworkCodeWinsWithoutMessageHeuristics() {
        val report = MediaPlayerDiagnosticsPlanner().report(
            candidate = candidate,
            error = MediaPlayerErrorSnapshot(
                errorCodeName = "ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT",
                errorCode = 2002,
                causeClassName = "androidx.media3.datasource.HttpDataSource.HttpDataSourceException",
                message = "playback failed",
                playbackStateLabel = "idle",
                playWhenReady = false,
                suppressionReasonLabel = null,
            ),
        )
        assertEquals(MediaPlayerDiagnosticBucket.Network, report.bucket)
    }
}
