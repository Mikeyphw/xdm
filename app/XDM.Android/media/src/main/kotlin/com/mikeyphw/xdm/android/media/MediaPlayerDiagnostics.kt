package com.mikeyphw.xdm.android.media

/**
 * Phase 29 Player 2.0 diagnostics.
 *
 * Media3 remains the playback surface, while this pure planner classifies source/network/decoder
 * problems, lists available tracks, plans retry prepare, and remembers local playback positions
 * without storing secrets or attempting protected-media bypass.
 */
enum class MediaPlayerDiagnosticBucket(val label: String) {
    Ready("Ready"),
    Source("Source"),
    Network("Network"),
    Decoder("Decoder"),
    UnsupportedCodec("Unsupported codec"),
    ProtectedMedia("Protected media"),
    Subtitle("Subtitle"),
    Unknown("Unknown"),
}

enum class MediaPlayerDiagnosticAction(val label: String) {
    RetryPrepare("Retry prepare"),
    RefreshMetadata("Refresh metadata"),
    OpenLibrary("Open library"),
    SelectTracks("Select tracks"),
    OpenExternal("Open externally"),
    ViewProtectedDiagnostics("View protected diagnostics"),
}

data class MediaPlayerTrackRow(
    val kind: String,
    val label: String,
    val selected: Boolean,
    val supported: Boolean,
) {
    val summary: String get() = listOf(kind, label, if (selected) "selected" else "available", if (supported) "supported" else "unsupported").joinToString(" • ")
}

data class MediaPlayerErrorSnapshot(
    val errorCodeName: String?,
    val errorCode: Int? = null,
    val causeClassName: String? = null,
    val message: String?,
    val playbackStateLabel: String,
    val playWhenReady: Boolean,
    val suppressionReasonLabel: String?,
)

data class MediaPlayerPositionMemoryPlan(
    val captureId: String,
    val positionMs: Long,
    val durationMs: Long?,
    val persistAllowed: Boolean,
    val key: String,
) {
    val summary: String get() = listOf("position=${positionMs.coerceAtLeast(0L)}ms", durationMs?.let { "duration=${it}ms" } ?: "duration=unknown", if (persistAllowed) "remember" else "do-not-persist").joinToString(" • ")
}

data class MediaPlayerDiagnosticReport(
    val captureId: String,
    val title: String,
    val bucket: MediaPlayerDiagnosticBucket,
    val message: String,
    val retryPrepareAvailable: Boolean,
    val protectedDiagnosticOnly: Boolean,
    val actions: List<MediaPlayerDiagnosticAction>,
    val tracks: List<MediaPlayerTrackRow>,
    val subtitleRows: List<MediaPlayerTrackRow>,
    val positionMemory: MediaPlayerPositionMemoryPlan,
    val sourceSafe: Boolean,
) {
    val summary: String get() = listOf(bucket.label, message, if (retryPrepareAvailable) "retry prepare" else "retry withheld", if (sourceSafe) "source-safe" else "redaction review").joinToString(" • ")
}

class MediaPlayerDiagnosticsPlanner {
    fun report(
        candidate: MediaPlaybackCandidate,
        error: MediaPlayerErrorSnapshot? = null,
        positionMs: Long = 0L,
        durationMs: Long? = null,
    ): MediaPlayerDiagnosticReport {
        val bucket = bucketFor(candidate, error)
        val sourceSafe = !containsKnownSecret(candidate.playbackUrl) && !containsKnownSecret(error?.message.orEmpty())
        val protectedOnly = candidate.needsExternalResolver || bucket == MediaPlayerDiagnosticBucket.ProtectedMedia
        val retry = !protectedOnly && bucket != MediaPlayerDiagnosticBucket.Ready
        val tracks = trackRows(candidate)
        val subtitles = subtitleRows(candidate)
        val actions = actionsFor(bucket, candidate, retry)
        val message = redactKnownSecrets(messageFor(error, bucket))
        return MediaPlayerDiagnosticReport(
            captureId = candidate.captureId,
            title = candidate.title,
            bucket = bucket,
            message = message,
            retryPrepareAvailable = retry,
            protectedDiagnosticOnly = protectedOnly,
            actions = actions,
            tracks = tracks,
            subtitleRows = subtitles,
            positionMemory = MediaPlayerPositionMemoryPlan(
                captureId = candidate.captureId,
                positionMs = positionMs.coerceAtLeast(0L),
                durationMs = durationMs?.coerceAtLeast(0L),
                persistAllowed = !candidate.needsExternalResolver && sourceSafe,
                key = "media-player-position-${candidate.captureId}",
            ),
            sourceSafe = sourceSafe,
        )
    }

    private fun bucketFor(candidate: MediaPlaybackCandidate, error: MediaPlayerErrorSnapshot?): MediaPlayerDiagnosticBucket {
        if (candidate.needsExternalResolver) return MediaPlayerDiagnosticBucket.ProtectedMedia
        if (error == null) return MediaPlayerDiagnosticBucket.Ready
        val code = error.errorCodeName?.trim()?.uppercase().orEmpty()
        val cause = error.causeClassName.orEmpty()
        return when {
            code in DRM_ERROR_CODES -> MediaPlayerDiagnosticBucket.ProtectedMedia
            code in NETWORK_ERROR_CODES -> MediaPlayerDiagnosticBucket.Network
            code in UNSUPPORTED_FORMAT_ERROR_CODES -> MediaPlayerDiagnosticBucket.UnsupportedCodec
            code in DECODER_ERROR_CODES -> MediaPlayerDiagnosticBucket.Decoder
            cause.endsWith(".SubtitleDecoderException") || cause == "SubtitleDecoderException" -> MediaPlayerDiagnosticBucket.Subtitle
            code in SOURCE_ERROR_CODES -> MediaPlayerDiagnosticBucket.Source
            else -> MediaPlayerDiagnosticBucket.Unknown
        }
    }

    private fun messageFor(error: MediaPlayerErrorSnapshot?, bucket: MediaPlayerDiagnosticBucket): String = when (bucket) {
        MediaPlayerDiagnosticBucket.Ready -> "Player ready. Local direct media can remember playback position and expose track availability."
        MediaPlayerDiagnosticBucket.Source -> "Source failed. Check whether the local file still exists, then retry prepare. ${error?.message.orEmpty()}"
        MediaPlayerDiagnosticBucket.Network -> "Network source failed. Refresh metadata or retry prepare if this is a direct remote media item. ${error?.message.orEmpty()}"
        MediaPlayerDiagnosticBucket.Decoder -> "Decoder failed. The device may not support this stream. ${error?.message.orEmpty()}"
        MediaPlayerDiagnosticBucket.UnsupportedCodec -> "Unsupported codec. Open externally or re-download/transcode with a compatible format. ${error?.message.orEmpty()}"
        MediaPlayerDiagnosticBucket.ProtectedMedia -> "Protected media diagnostics only. XDM does not bypass DRM or content protection."
        MediaPlayerDiagnosticBucket.Subtitle -> "Subtitle/text track failed. Playback can continue without the subtitle track after review. ${error?.message.orEmpty()}"
        MediaPlayerDiagnosticBucket.Unknown -> "Playback failed. Retry prepare or open diagnostics for this completed library item. ${error?.message.orEmpty()}"
    }

    private fun trackRows(candidate: MediaPlaybackCandidate): List<MediaPlayerTrackRow> {
        val rows = mutableListOf<MediaPlayerTrackRow>()
        rows += MediaPlayerTrackRow("video", if (candidate.isAdaptive) "adaptive video group" else "direct video/audio source", selected = true, supported = !candidate.needsExternalResolver)
        repeat(candidate.audioTrackCount.coerceAtLeast(0)) { index -> rows += MediaPlayerTrackRow("audio", "Audio track ${index + 1}", selected = index == 0, supported = true) }
        return rows
    }

    private fun subtitleRows(candidate: MediaPlaybackCandidate): List<MediaPlayerTrackRow> = List(candidate.subtitleCount.coerceAtLeast(0)) { index ->
        MediaPlayerTrackRow("subtitle", "Subtitle track ${index + 1}", selected = index == 0, supported = true)
    }

    private fun actionsFor(
        bucket: MediaPlayerDiagnosticBucket,
        candidate: MediaPlaybackCandidate,
        retry: Boolean,
    ): List<MediaPlayerDiagnosticAction> {
        val actions = mutableListOf<MediaPlayerDiagnosticAction>()
        if (retry) actions += MediaPlayerDiagnosticAction.RetryPrepare
        actions += MediaPlayerDiagnosticAction.OpenLibrary
        if (candidate.audioTrackCount > 1 || candidate.subtitleCount > 0) actions += MediaPlayerDiagnosticAction.SelectTracks
        if (bucket == MediaPlayerDiagnosticBucket.Network || bucket == MediaPlayerDiagnosticBucket.Source) actions += MediaPlayerDiagnosticAction.RefreshMetadata
        if (bucket == MediaPlayerDiagnosticBucket.UnsupportedCodec || bucket == MediaPlayerDiagnosticBucket.Decoder) actions += MediaPlayerDiagnosticAction.OpenExternal
        if (bucket == MediaPlayerDiagnosticBucket.ProtectedMedia) actions += MediaPlayerDiagnosticAction.ViewProtectedDiagnostics
        return actions.distinct()
    }

    private fun containsKnownSecret(text: String): Boolean = secretPatterns.any { it.containsMatchIn(text) }

    private fun redactKnownSecrets(text: String): String {
        var redacted = text
        secretPatterns.forEach { pattern -> redacted = pattern.replace(redacted, "<redacted>") }
        return redacted
    }

    private companion object {
        val NETWORK_ERROR_CODES = setOf(
            "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED",
            "ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT",
            "ERROR_CODE_IO_BAD_HTTP_STATUS",
            "ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE",
            "ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED",
        )
        val SOURCE_ERROR_CODES = setOf(
            "ERROR_CODE_IO_UNSPECIFIED",
            "ERROR_CODE_IO_FILE_NOT_FOUND",
            "ERROR_CODE_IO_NO_PERMISSION",
            "ERROR_CODE_PARSING_CONTAINER_MALFORMED",
            "ERROR_CODE_PARSING_MANIFEST_MALFORMED",
        )
        val DECODER_ERROR_CODES = setOf(
            "ERROR_CODE_DECODER_INIT_FAILED",
            "ERROR_CODE_DECODER_QUERY_FAILED",
            "ERROR_CODE_DECODING_FAILED",
        )
        val UNSUPPORTED_FORMAT_ERROR_CODES = setOf(
            "ERROR_CODE_DECODING_FORMAT_UNSUPPORTED",
            "ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES",
            "ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED",
            "ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED",
        )
        val DRM_ERROR_CODES = setOf(
            "ERROR_CODE_DRM_UNSPECIFIED",
            "ERROR_CODE_DRM_SCHEME_UNSUPPORTED",
            "ERROR_CODE_DRM_PROVISIONING_FAILED",
            "ERROR_CODE_DRM_CONTENT_ERROR",
            "ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED",
            "ERROR_CODE_DRM_DISALLOWED_OPERATION",
            "ERROR_CODE_DRM_SYSTEM_ERROR",
            "ERROR_CODE_DRM_DEVICE_REVOKED",
            "ERROR_CODE_DRM_LICENSE_EXPIRED",
        )
        val secretPatterns = listOf(
            Regex("""Bearer\s+(?!<redacted(?:-[A-Za-z]+)?>)(?:secret-[A-Za-z0-9._-]+|[A-Za-z0-9._~+/=-]{16,})""", RegexOption.IGNORE_CASE),
            Regex("""Cookie\s*[:=](?!\s*<redacted(?:-[A-Za-z]+)?>)\s*[^\n;]+""", RegexOption.IGNORE_CASE),
            Regex("""(?i)(?<![-A-Za-z])(token|session|sid|sig|signature|auth|key)=((?!<redacted>|referer=|none\b|available\b|redacted\b)[^\s&#;]+)"""),
            Regex("\\b(?:super-)?secret-(?!(?:safe|bearing|free)\\b)[A-Za-z0-9._-]+", RegexOption.IGNORE_CASE),
        )
    }
}
