package com.mikeyphw.xdm.android.media

import com.mikeyphw.xdm.android.model.MediaCaptureRecord
import com.mikeyphw.xdm.android.model.MediaSourceKind
import com.mikeyphw.xdm.android.model.MediaVariant
import com.mikeyphw.xdm.android.model.MediaVariantKind
import java.util.Locale

/**
 * Clean-room resolver models for XDM's Android media workbench.
 *
 * This layer mirrors the user-visible flow of dedicated video downloaders without copying their
 * implementation: inspect page context, group variants, choose tracks, hand off safe session hints,
 * and keep protected media diagnostic-only.
 */
enum class MediaDownloadStrategy { Native, Aria2, YtDlp, FfmpegLive, UnsupportedProtected }

enum class MediaDownloadIntent { BestVideo, AudioOnly, VideoOnly, Subtitles, Thumbnail, LiveRecording }

data class MediaTrackSelection(
    val videoVariantId: String? = null,
    val audioVariantId: String? = null,
    val subtitleVariantId: String? = null,
) {
    fun selectedIds(): Set<String> = setOfNotNull(videoVariantId, audioVariantId, subtitleVariantId)
}

data class MediaVariantPickerGroup(
    val kind: MediaVariantKind,
    val title: String,
    val variants: List<MediaVariant>,
    val selectedVariantId: String?,
) {
    val countLabel: String get() = "${variants.size} ${title.lowercase(Locale.US)}"
}

data class MediaSessionHeader(
    val name: String,
    val value: String,
) {
    val redactedValue: String get() = redactHeaderValue(name, value)
    val redactedLine: String get() = "$name: $redactedValue"
}

data class MediaSessionHandoff(
    val pageUrl: String?,
    val sourceUrl: String,
    val selectedVariantUrl: String?,
    val headers: List<MediaSessionHeader> = emptyList(),
    val cookieJarAvailable: Boolean = false,
) {
    val referer: String? get() = pageUrl?.takeIf { it.isNotBlank() }
    val needsSession: Boolean get() = referer != null || cookieJarAvailable || headers.isNotEmpty()
    val redactedSummary: String get() = buildString {
        append("referer=").append(referer?.let(::redactUrl) ?: "none")
        if (cookieJarAvailable) append("; cookies=available/redacted")
        if (headers.isNotEmpty()) append("; headers=").append(headers.joinToString { it.redactedLine })
    }

    fun ytdlpArguments(): List<String> {
        val arguments = mutableListOf<String>()
        referer?.let { arguments += listOf("--referer", it) }
        headers.forEach { header -> arguments += listOf("--add-header", "${header.name}: ${header.value}") }
        if (cookieJarAvailable) arguments += "--cookies-from-browser=android-webview"
        return arguments
    }

    fun aria2Options(): Map<String, String> {
        val options = linkedMapOf<String, String>()
        referer?.let { options["referer"] = it }
        if (headers.isNotEmpty()) options["header"] = headers.joinToString("\n") { "${it.name}: ${it.value}" }
        return options
    }

    fun requestHeaders(): Map<String, String> {
        val requestHeaders = linkedMapOf<String, String>()
        referer?.let { requestHeaders["Referer"] = it }
        headers.forEach { header -> requestHeaders[header.name] = header.value }
        return requestHeaders
    }

    fun diagnosticLines(): List<String> = listOf(
        "session_handoff\t${redactedSummary}",
        "session_target\t${redactUrl(selectedVariantUrl ?: sourceUrl)}",
    )
}

data class YtDlpMetadataProbeResult(
    val title: String,
    val thumbnailUrl: String?,
    val durationMs: Long?,
    val extractor: String?,
    val formatCount: Int,
    val isLive: Boolean,
    val webpageUrl: String?,
) {
    val durationLabel: String get() = durationMs?.let { formatDuration(it) } ?: "duration unknown"
    val summary: String get() = listOfNotNull(
        title,
        durationLabel,
        extractor?.takeIf { it.isNotBlank() },
        formatCount.takeIf { it > 0 }?.let { "$it formats" },
        if (isLive) "live" else null,
    ).joinToString(" • ")
}

data class ProtectedMediaDiagnostic(
    val protected: Boolean,
    val scheme: String?,
    val reason: String,
    val allowedAction: String,
) {
    val label: String get() = if (protected) "Protected media detected" else "No DRM markers detected"
}

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
    val trackSelection: MediaTrackSelection = MediaTrackSelection(),
    val sessionHandoff: MediaSessionHandoff = MediaSessionHandoff(null, primaryUrl, primaryUrl),
    val ytDlpFormatSelector: String? = null,
    val protectedDiagnostic: ProtectedMediaDiagnostic = ProtectedMediaDiagnostic(false, null, "No protection markers found.", "Download or play after review."),
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
        selection: MediaTrackSelection = MediaTrackSelection(videoVariantId = capture.selectedVariantId),
        sessionHeaders: List<MediaSessionHeader> = defaultSessionHeaders(capture),
    ): MediaDownloadPlan {
        val selected = selectedVariant(capture, variants, intent, selection)
        val primaryUrl = selected?.url ?: capture.selectedVariantUrl ?: capture.sourceUrl
        val live = isLive(capture)
        val protectedDiagnostic = protectedDiagnostic(capture, variants)
        val strategy = when {
            protectedDiagnostic.protected -> MediaDownloadStrategy.UnsupportedProtected
            intent == MediaDownloadIntent.LiveRecording || live -> MediaDownloadStrategy.FfmpegLive
            capture.kind == MediaSourceKind.HlsPlaylist || capture.kind == MediaSourceKind.DashManifest -> MediaDownloadStrategy.YtDlp
            intent == MediaDownloadIntent.AudioOnly && capture.kind != MediaSourceKind.AudioStream -> MediaDownloadStrategy.YtDlp
            intent == MediaDownloadIntent.Subtitles -> MediaDownloadStrategy.YtDlp
            capture.kind == MediaSourceKind.AudioStream -> MediaDownloadStrategy.Native
            capture.kind == MediaSourceKind.ProgressiveMedia || capture.kind == MediaSourceKind.DirectFile || capture.kind == MediaSourceKind.VideoStream -> MediaDownloadStrategy.Aria2
            else -> MediaDownloadStrategy.YtDlp
        }
        val normalizedSelection = normalizeSelection(capture, variants, selection, selected)
        val session = MediaSessionHandoff(
            pageUrl = capture.pageUrl,
            sourceUrl = capture.sourceUrl,
            selectedVariantUrl = primaryUrl,
            headers = sessionHeaders,
            cookieJarAvailable = capture.pageUrl != null,
        )
        return MediaDownloadPlan(
            strategy = strategy,
            intent = intent,
            primaryUrl = primaryUrl,
            selectedVariantId = selected?.id ?: capture.selectedVariantId,
            displayName = displayNameFor(strategy, intent),
            requiresTermux = strategy == MediaDownloadStrategy.YtDlp || strategy == MediaDownloadStrategy.FfmpegLive,
            canQueueDirectly = strategy != MediaDownloadStrategy.UnsupportedProtected,
            explanation = explanationFor(strategy, capture.kind, intent, capture, variants, normalizedSelection, session),
            metadataProbeUrl = metadataProbeUrl(capture),
            needsCookieContext = session.needsSession || capture.sourceUrl.contains("token", ignoreCase = true) || variants.any { it.url.contains("token", ignoreCase = true) },
            trackSelection = normalizedSelection,
            sessionHandoff = session,
            ytDlpFormatSelector = ytdlpFormatSelector(variants, normalizedSelection, intent),
            protectedDiagnostic = protectedDiagnostic,
        )
    }

    fun pickerGroups(capture: MediaCaptureRecord, variants: List<MediaVariant>, selection: MediaTrackSelection = MediaTrackSelection(videoVariantId = capture.selectedVariantId)): List<MediaVariantPickerGroup> =
        listOf(
            MediaVariantPickerGroup(MediaVariantKind.Video, "Video quality", variants.filter { it.kind == MediaVariantKind.Video || it.kind == MediaVariantKind.Primary }, selection.videoVariantId ?: capture.selectedVariantId),
            MediaVariantPickerGroup(MediaVariantKind.Audio, "Audio track", variants.filter { it.kind == MediaVariantKind.Audio }, selection.audioVariantId),
            MediaVariantPickerGroup(MediaVariantKind.Subtitle, "Subtitle track", variants.filter { it.kind == MediaVariantKind.Subtitle }, selection.subtitleVariantId),
        ).filter { it.variants.isNotEmpty() }

    fun metadataProbePreview(capture: MediaCaptureRecord, variants: List<MediaVariant>): YtDlpMetadataProbeResult = YtDlpMetadataProbeResult(
        title = capture.title.ifBlank { capture.fileName },
        thumbnailUrl = capture.thumbnailUrl ?: variants.firstOrNull { it.kind == MediaVariantKind.Thumbnail }?.url,
        durationMs = capture.durationMs,
        extractor = if (capture.pageUrl != null) "page-context" else capture.kind.name.lowercase(Locale.US),
        formatCount = variants.size.coerceAtLeast(capture.variantCount),
        isLive = isLive(capture),
        webpageUrl = capture.pageUrl,
    )

    fun parseYtDlpMetadata(json: String): YtDlpMetadataProbeResult = YtDlpMetadataProbeResult(
        title = jsonString(json, "title") ?: jsonString(json, "fulltitle") ?: "Untitled media",
        thumbnailUrl = jsonString(json, "thumbnail"),
        durationMs = jsonNumber(json, "duration")?.let { (it * 1000).toLong() },
        extractor = jsonString(json, "extractor_key") ?: jsonString(json, "extractor"),
        formatCount = Regex("""\"format_id\"\s*:""").findAll(json).count().takeIf { it > 0 } ?: jsonArrayCount(json, "formats"),
        isLive = jsonBoolean(json, "is_live") == true || jsonString(json, "live_status")?.contains("live", ignoreCase = true) == true,
        webpageUrl = jsonString(json, "webpage_url"),
    )

    fun playbackCandidate(capture: MediaCaptureRecord, variants: List<MediaVariant>): MediaPlaybackCandidate? {
        val selected = selectedVariant(capture, variants, MediaDownloadIntent.BestVideo, MediaTrackSelection(videoVariantId = capture.selectedVariantId)) ?: variants.firstOrNull() ?: return null
        val selectedMimeType = selected.mimeType
        val adaptive = capture.kind == MediaSourceKind.HlsPlaylist || capture.kind == MediaSourceKind.DashManifest || selectedMimeType?.let(adaptiveMimeTypes::contains) == true
        return MediaPlaybackCandidate(
            captureId = capture.id,
            title = capture.title.ifBlank { capture.fileName },
            playbackUrl = selected.url,
            isAdaptive = adaptive,
            needsExternalResolver = adaptive || protectedDiagnostic(capture, variants).protected,
            subtitleCount = variants.count { it.kind == MediaVariantKind.Subtitle },
            audioTrackCount = variants.count { it.kind == MediaVariantKind.Audio },
        )
    }

    fun summarizeOfflineLibrary(captures: List<MediaCaptureRecord>, variants: List<MediaVariant>): OfflineMediaLibrarySummary {
        val playable = captures.mapNotNull { capture -> playbackCandidate(capture, variants.filter { it.captureId == capture.id }) }
        val adaptive = playable.count { it.isAdaptive }
        val audio = captures.count { it.kind == MediaSourceKind.AudioStream }
        val subtitles = playable.sumOf { it.subtitleCount }
        val message = when {
            playable.isEmpty() -> "No playable media yet. Capture or download a stream to seed the offline library."
            adaptive > 0 -> "${playable.size} playable items; $adaptive adaptive streams need yt-dlp/session resolution before offline playback."
            else -> "${playable.size} playable direct media items can open in the Media3 player."
        }
        return OfflineMediaLibrarySummary(
            playableCount = playable.size,
            adaptiveCount = adaptive,
            audioOnlyCount = audio,
            subtitleTrackCount = subtitles,
            message = message,
        )
    }

    private fun selectedVariant(capture: MediaCaptureRecord, variants: List<MediaVariant>, intent: MediaDownloadIntent, selection: MediaTrackSelection): MediaVariant? {
        val preferredKind = when (intent) {
            MediaDownloadIntent.AudioOnly -> MediaVariantKind.Audio
            MediaDownloadIntent.VideoOnly, MediaDownloadIntent.BestVideo, MediaDownloadIntent.LiveRecording -> MediaVariantKind.Video
            MediaDownloadIntent.Subtitles -> MediaVariantKind.Subtitle
            MediaDownloadIntent.Thumbnail -> MediaVariantKind.Thumbnail
        }
        val explicitId = when (preferredKind) {
            MediaVariantKind.Video -> selection.videoVariantId ?: capture.selectedVariantId
            MediaVariantKind.Audio -> selection.audioVariantId
            MediaVariantKind.Subtitle -> selection.subtitleVariantId
            MediaVariantKind.Thumbnail -> null
            MediaVariantKind.Primary -> capture.selectedVariantId
        }
        return explicitId?.let { id -> variants.firstOrNull { it.id == id } }
            ?: variants.firstOrNull { it.id == capture.selectedVariantId }
            ?: variants.filter { it.kind == preferredKind }.maxWithOrNull(compareBy<MediaVariant> { it.height ?: 0 }.thenBy { it.bitrateBitsPerSecond ?: 0L })
            ?: variants.maxWithOrNull(compareBy<MediaVariant> { variantRank(it.kind) }.thenBy { it.height ?: 0 }.thenBy { it.bitrateBitsPerSecond ?: 0L })
    }

    private fun normalizeSelection(capture: MediaCaptureRecord, variants: List<MediaVariant>, selection: MediaTrackSelection, selected: MediaVariant?): MediaTrackSelection = MediaTrackSelection(
        videoVariantId = selection.videoVariantId ?: selected?.takeIf { it.kind == MediaVariantKind.Video || it.kind == MediaVariantKind.Primary }?.id ?: capture.selectedVariantId,
        audioVariantId = selection.audioVariantId?.takeIf { id -> variants.any { it.id == id && it.kind == MediaVariantKind.Audio } },
        subtitleVariantId = selection.subtitleVariantId?.takeIf { id -> variants.any { it.id == id && it.kind == MediaVariantKind.Subtitle } },
    )

    private fun ytdlpFormatSelector(variants: List<MediaVariant>, selection: MediaTrackSelection, intent: MediaDownloadIntent): String? {
        if (intent == MediaDownloadIntent.AudioOnly) return "bestaudio/best"
        val video = selection.videoVariantId?.let { id -> variants.firstOrNull { it.id == id } }
        val audio = selection.audioVariantId?.let { id -> variants.firstOrNull { it.id == id } }
        val videoSelector = video?.height?.let { "bestvideo[height<=${it}]" } ?: video?.bitrateBitsPerSecond?.let { "bestvideo[tbr<=${it / 1000}]" } ?: "bestvideo"
        val audioSelector = audio?.language?.let { "bestaudio[language=${it}]/bestaudio" } ?: "bestaudio"
        return if (variants.any { it.kind == MediaVariantKind.Audio }) "$videoSelector+$audioSelector/best" else "$videoSelector+bestaudio/best"
    }

    private fun metadataProbeUrl(capture: MediaCaptureRecord): String = capture.pageUrl?.takeIf { it.isNotBlank() } ?: capture.sourceUrl

    private fun isLive(capture: MediaCaptureRecord): Boolean = capture.container?.contains("live", ignoreCase = true) == true

    private fun protectedDiagnostic(capture: MediaCaptureRecord, variants: List<MediaVariant>): ProtectedMediaDiagnostic {
        val evidence = listOfNotNull(capture.container, capture.codecs, capture.mimeType) + variants.mapNotNull { it.codecs } + variants.mapNotNull { it.mimeType }
        val marker = evidence.firstOrNull { value -> protectedMarkers.any { marker -> value.contains(marker, ignoreCase = true) } }
        return if (marker != null) {
            ProtectedMediaDiagnostic(
                protected = true,
                scheme = protectedMarkers.firstOrNull { scheme -> evidence.any { it.contains(scheme, ignoreCase = true) } },
                reason = "Manifest or codec metadata contains protection marker: ${marker.take(80)}.",
                allowedAction = "Show diagnostics only. XDM does not bypass DRM or queue protected media.",
            )
        } else {
            ProtectedMediaDiagnostic(false, null, "No DRM/protection marker was present in capture metadata.", "Resolver and player actions remain review-first.")
        }
    }

    private fun displayNameFor(strategy: MediaDownloadStrategy, intent: MediaDownloadIntent): String = when (strategy) {
        MediaDownloadStrategy.Native -> if (intent == MediaDownloadIntent.AudioOnly) "Native audio" else "Native direct"
        MediaDownloadStrategy.Aria2 -> "aria2 segmented"
        MediaDownloadStrategy.YtDlp -> "yt-dlp resolver"
        MediaDownloadStrategy.FfmpegLive -> "Live recorder"
        MediaDownloadStrategy.UnsupportedProtected -> "Protected media"
    }

    private fun explanationFor(strategy: MediaDownloadStrategy, kind: MediaSourceKind, intent: MediaDownloadIntent, capture: MediaCaptureRecord, variants: List<MediaVariant>, selection: MediaTrackSelection, session: MediaSessionHandoff): String {
        val base = when (strategy) {
            MediaDownloadStrategy.Native -> "Direct audio or file download can stay inside XDM native storage handling."
            MediaDownloadStrategy.Aria2 -> "Progressive media can use aria2 with referer/header handoff when the page context matters."
            MediaDownloadStrategy.YtDlp -> "Playlist, site-page, subtitle, or audio extraction workflows use yt-dlp metadata, track selection, and session hints."
            MediaDownloadStrategy.FfmpegLive -> "Live playlists need an explicit stop-and-save recording workflow."
            MediaDownloadStrategy.UnsupportedProtected -> "Protected DRM media is detected but is not bypassed or downloaded."
        }
        val extras = listOfNotNull(
            variants.count { it.kind == MediaVariantKind.Audio }.takeIf { it > 0 }?.let { "$it audio" },
            variants.count { it.kind == MediaVariantKind.Subtitle }.takeIf { it > 0 }?.let { "$it subtitles" },
            selection.selectedIds().takeIf { it.isNotEmpty() }?.let { "${it.size} selected tracks" },
            session.needsSession.takeIf { it }?.let { "session handoff ready" },
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
        private val protectedMarkers = setOf("drm", "widevine", "playready", "fairplay", "protected", "sample-aes", "cenc")

        @Suppress("UNUSED_PARAMETER")
        fun defaultSessionHeaders(capture: MediaCaptureRecord): List<MediaSessionHeader> = emptyList()
    }
}

private fun redactHeaderValue(name: String, value: String): String = when {
    name.equals("Cookie", ignoreCase = true) -> "<redacted-cookie>"
    name.equals("Authorization", ignoreCase = true) -> "<redacted-auth>"
    name.equals("X-CSRF-Token", ignoreCase = true) -> "<redacted-token>"
    value.contains("token", ignoreCase = true) || value.contains("session", ignoreCase = true) -> "<redacted-secret>"
    else -> value.take(120)
}

private fun redactUrl(url: String): String = url
    .replace(Regex("""([?&](?:token|auth|session|sig|signature|key|cookie)=)[^&#]+""", RegexOption.IGNORE_CASE), "$1<redacted>")
    .take(220)

private fun formatDuration(durationMs: Long): String {
    val seconds = durationMs / 1000
    val h = seconds / 3600
    val m = seconds % 3600 / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private fun jsonString(json: String, key: String): String? = Regex("""\"${Regex.escape(key)}\"\s*:\s*\"((?:\\\\.|[^\"])*)\"""")
    .find(json)
    ?.groupValues
    ?.getOrNull(1)
    ?.replace("\\\"", "\"")
    ?.replace("\\/", "/")

private fun jsonNumber(json: String, key: String): Double? = Regex("""\"${Regex.escape(key)}\"\s*:\s*([0-9]+(?:\.[0-9]+)?)""")
    .find(json)
    ?.groupValues
    ?.getOrNull(1)
    ?.toDoubleOrNull()

private fun jsonBoolean(json: String, key: String): Boolean? = Regex("""\"${Regex.escape(key)}\"\s*:\s*(true|false)""", RegexOption.IGNORE_CASE)
    .find(json)
    ?.groupValues
    ?.getOrNull(1)
    ?.lowercase(Locale.US)
    ?.let { value -> value == "true" }

private fun jsonArrayCount(json: String, key: String): Int {
    val body = Regex("""\"${Regex.escape(key)}\"\s*:\s*\[(.*?)]""", setOf(RegexOption.DOT_MATCHES_ALL))
        .find(json)
        ?.groupValues
        ?.getOrNull(1)
        ?: return 0
    return Regex("""\{""").findAll(body).count()
}
