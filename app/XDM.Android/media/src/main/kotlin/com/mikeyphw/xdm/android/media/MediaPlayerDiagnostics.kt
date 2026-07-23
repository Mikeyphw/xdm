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
        val code = error?.errorCodeName.orEmpty()
        val message = error?.message.orEmpty()
        val merged = "$code $message"
        return when {
            error == null -> MediaPlayerDiagnosticBucket.Ready
            merged.contains("DRM", ignoreCase = true) || merged.contains("CONTENT_PROTECTION", ignoreCase = true) -> MediaPlayerDiagnosticBucket.ProtectedMedia
            merged.contains("SOURCE", ignoreCase = true) || merged.contains("IO", ignoreCase = true) || merged.contains("FILE", ignoreCase = true) -> MediaPlayerDiagnosticBucket.Source
            merged.contains("HTTP", ignoreCase = true) || merged.contains("NETWORK", ignoreCase = true) || merged.contains("TIMEOUT", ignoreCase = true) -> MediaPlayerDiagnosticBucket.Network
            merged.contains("DECODER", ignoreCase = true) -> MediaPlayerDiagnosticBucket.Decoder
            merged.contains("UNSUPPORTED", ignoreCase = true) || merged.contains("CODEC", ignoreCase = true) -> MediaPlayerDiagnosticBucket.UnsupportedCodec
            merged.contains("SUBTITLE", ignoreCase = true) || merged.contains("TEXT", ignoreCase = true) -> MediaPlayerDiagnosticBucket.Subtitle
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
        val secretPatterns = listOf(
            Regex("""Bearer\s+[A-Za-z0-9._~+/=-]+""", RegexOption.IGNORE_CASE),
            Regex("""Cookie\s*[:=]\s*[^\n;]+""", RegexOption.IGNORE_CASE),
            Regex("""(?i)(token|session|sid|sig|signature|auth|key)=((?!<redacted>)[^\s&#;]+)"""),
            Regex("secret-[A-Za-z0-9._-]+", RegexOption.IGNORE_CASE),
        )
    }
}
