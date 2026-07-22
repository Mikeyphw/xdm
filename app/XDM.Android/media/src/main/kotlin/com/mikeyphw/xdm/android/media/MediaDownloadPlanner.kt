package com.mikeyphw.xdm.android.media

import com.mikeyphw.xdm.android.model.MediaCaptureRecord
import com.mikeyphw.xdm.android.model.MediaSourceKind
import com.mikeyphw.xdm.android.model.MediaVariant
import com.mikeyphw.xdm.android.model.MediaVariantKind

enum class MediaDownloadStrategy { Native, Aria2, YtDlp, FfmpegLive, UnsupportedProtected }

enum class MediaDownloadIntent { BestVideo, AudioOnly, VideoOnly, Subtitles, Thumbnail, LiveRecording }

data class MediaDownloadPlan(
    val strategy: MediaDownloadStrategy,
    val intent: MediaDownloadIntent,
    val primaryUrl: String,
    val selectedVariantId: String?,
    val displayName: String,
    val requiresTermux: Boolean,
    val canQueueDirectly: Boolean,
    val explanation: String,
    val metadataProbeUrl: String,
    val needsCookieContext: Boolean,
)

data class MediaPlaybackCandidate(
    val captureId: String,
    val title: String,
    val playbackUrl: String,
    val isAdaptive: Boolean,
    val needsExternalResolver: Boolean,
    val subtitleCount: Int,
    val audioTrackCount: Int,
)

data class OfflineMediaLibrarySummary(
    val playableCount: Int,
    val adaptiveCount: Int,
    val audioOnlyCount: Int,
    val subtitleTrackCount: Int,
    val message: String,
)

class MediaDownloadPlanner {
    fun plan(
        capture: MediaCaptureRecord,
        variants: List<MediaVariant>,
        intent: MediaDownloadIntent = MediaDownloadIntent.BestVideo,
    ): MediaDownloadPlan {
        val selected = selectedVariant(capture, variants, intent)
        val primaryUrl = selected?.url ?: capture.selectedVariantUrl ?: capture.sourceUrl
        val live = isLive(capture)
        val protected = isProtected(capture)
        val strategy = when {
            protected -> MediaDownloadStrategy.UnsupportedProtected
            intent == MediaDownloadIntent.LiveRecording || live -> MediaDownloadStrategy.FfmpegLive
            capture.kind == MediaSourceKind.HlsPlaylist || capture.kind == MediaSourceKind.DashManifest -> MediaDownloadStrategy.YtDlp
            intent == MediaDownloadIntent.AudioOnly && capture.kind != MediaSourceKind.AudioStream -> MediaDownloadStrategy.YtDlp
            intent == MediaDownloadIntent.Subtitles -> MediaDownloadStrategy.YtDlp
            capture.kind == MediaSourceKind.AudioStream -> MediaDownloadStrategy.Native
            capture.kind == MediaSourceKind.ProgressiveMedia || capture.kind == MediaSourceKind.DirectFile || capture.kind == MediaSourceKind.VideoStream -> MediaDownloadStrategy.Aria2
            else -> MediaDownloadStrategy.YtDlp
        }
        return MediaDownloadPlan(
            strategy = strategy,
            intent = intent,
            primaryUrl = primaryUrl,
            selectedVariantId = selected?.id ?: capture.selectedVariantId,
            displayName = displayNameFor(strategy, intent),
            requiresTermux = strategy == MediaDownloadStrategy.YtDlp || strategy == MediaDownloadStrategy.FfmpegLive,
            canQueueDirectly = strategy != MediaDownloadStrategy.UnsupportedProtected,
            explanation = explanationFor(strategy, capture.kind, intent, capture, variants),
            metadataProbeUrl = metadataProbeUrl(capture),
            needsCookieContext = capture.pageUrl != null || capture.sourceUrl.contains("token", ignoreCase = true) || variants.any { it.url.contains("token", ignoreCase = true) },
        )
    }

    fun playbackCandidate(capture: MediaCaptureRecord, variants: List<MediaVariant>): MediaPlaybackCandidate? {
        val selected = selectedVariant(capture, variants, MediaDownloadIntent.BestVideo) ?: variants.firstOrNull() ?: return null
        val adaptive = capture.kind == MediaSourceKind.HlsPlaylist || capture.kind == MediaSourceKind.DashManifest || selected.mimeType in adaptiveMimeTypes
        return MediaPlaybackCandidate(
            captureId = capture.id,
            title = capture.title.ifBlank { capture.fileName },
            playbackUrl = selected.url,
            isAdaptive = adaptive,
            needsExternalResolver = adaptive || isProtected(capture),
            subtitleCount = variants.count { it.kind == MediaVariantKind.Subtitle },
            audioTrackCount = variants.count { it.kind == MediaVariantKind.Audio },
        )
    }

    fun summarizeOfflineLibrary(captures: List<MediaCaptureRecord>, variants: List<MediaVariant>): OfflineMediaLibrarySummary {
        val playable = captures.mapNotNull { capture -> playbackCandidate(capture, variants.filter { it.captureId == capture.id }) }
        val adaptive = playable.count(MediaPlaybackCandidate::isAdaptive)
        val audio = captures.count { it.kind == MediaSourceKind.AudioStream }
        val subtitles = playable.sumOf(MediaPlaybackCandidate::subtitleCount)
        val message = when {
            playable.isEmpty() -> "No playable media yet. Capture or download a stream to seed the offline library."
            adaptive > 0 -> "${playable.size} playable items; $adaptive adaptive streams need Media3/yt-dlp handoff before offline playback."
            else -> "${playable.size} playable direct media items are ready for the offline library shell."
        }
        return OfflineMediaLibrarySummary(
            playableCount = playable.size,
            adaptiveCount = adaptive,
            audioOnlyCount = audio,
            subtitleTrackCount = subtitles,
            message = message,
        )
    }

    private fun selectedVariant(capture: MediaCaptureRecord, variants: List<MediaVariant>, intent: MediaDownloadIntent): MediaVariant? {
        val preferredKind = when (intent) {
            MediaDownloadIntent.AudioOnly -> MediaVariantKind.Audio
            MediaDownloadIntent.VideoOnly, MediaDownloadIntent.BestVideo, MediaDownloadIntent.LiveRecording -> MediaVariantKind.Video
            MediaDownloadIntent.Subtitles -> MediaVariantKind.Subtitle
            MediaDownloadIntent.Thumbnail -> MediaVariantKind.Thumbnail
        }
        return variants.firstOrNull { it.id == capture.selectedVariantId }
            ?: variants.filter { it.kind == preferredKind }.maxWithOrNull(compareBy<MediaVariant> { it.height ?: 0 }.thenBy { it.bitrateBitsPerSecond ?: 0L })
            ?: variants.maxWithOrNull(compareBy<MediaVariant> { variantRank(it.kind) }.thenBy { it.height ?: 0 }.thenBy { it.bitrateBitsPerSecond ?: 0L })
    }

    private fun metadataProbeUrl(capture: MediaCaptureRecord): String = capture.pageUrl?.takeIf { it.isNotBlank() } ?: capture.sourceUrl

    private fun isLive(capture: MediaCaptureRecord): Boolean = capture.container?.contains("live", ignoreCase = true) == true

    private fun isProtected(capture: MediaCaptureRecord): Boolean = listOfNotNull(capture.container, capture.codecs, capture.mimeType)
        .any { value -> protectedMarkers.any { marker -> value.contains(marker, ignoreCase = true) } }

    private fun displayNameFor(strategy: MediaDownloadStrategy, intent: MediaDownloadIntent): String = when (strategy) {
        MediaDownloadStrategy.Native -> if (intent == MediaDownloadIntent.AudioOnly) "Native audio" else "Native direct"
        MediaDownloadStrategy.Aria2 -> "aria2 segmented"
        MediaDownloadStrategy.YtDlp -> "yt-dlp resolver"
        MediaDownloadStrategy.FfmpegLive -> "Live recorder"
        MediaDownloadStrategy.UnsupportedProtected -> "Protected media"
    }

    private fun explanationFor(strategy: MediaDownloadStrategy, kind: MediaSourceKind, intent: MediaDownloadIntent, capture: MediaCaptureRecord, variants: List<MediaVariant>): String {
        val base = when (strategy) {
            MediaDownloadStrategy.Native -> "Direct audio or file download can stay inside XDM native storage handling."
            MediaDownloadStrategy.Aria2 -> "Progressive media can use aria2 for resilient segmented transfer."
            MediaDownloadStrategy.YtDlp -> "Playlist, site-page, subtitle, or audio extraction workflows need yt-dlp metadata and format selection."
            MediaDownloadStrategy.FfmpegLive -> "Live playlists need an explicit stop-and-save recording workflow."
            MediaDownloadStrategy.UnsupportedProtected -> "Protected DRM media is detected but is not bypassed or downloaded."
        }
        val extras = listOfNotNull(
            variants.count { it.kind == MediaVariantKind.Audio }.takeIf { it > 0 }?.let { "$it audio" },
            variants.count { it.kind == MediaVariantKind.Subtitle }.takeIf { it > 0 }?.let { "$it subtitles" },
            capture.pageUrl?.let { "probe page available" },
        ).joinToString("; ")
        return "$base Kind: ${kind.name}; intent: ${intent.name}.${extras.takeIf { it.isNotBlank() }?.let { " $it." }.orEmpty()}"
    }

    private fun variantRank(kind: MediaVariantKind): Int = when (kind) {
        MediaVariantKind.Video -> 5
        MediaVariantKind.Primary -> 4
        MediaVariantKind.Audio -> 3
        MediaVariantKind.Subtitle -> 2
        MediaVariantKind.Thumbnail -> 1
    }

    companion object {
        private val adaptiveMimeTypes = setOf("application/vnd.apple.mpegurl", "application/x-mpegurl", "application/dash+xml")
        private val protectedMarkers = setOf("drm", "widevine", "playready", "fairplay", "protected")
    }
}
