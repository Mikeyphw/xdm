package com.mikeyphw.xdm.android.media

import com.mikeyphw.xdm.android.model.MediaCaptureRecord
import com.mikeyphw.xdm.android.model.MediaCaptureStatus
import com.mikeyphw.xdm.android.model.MediaSourceKind
import com.mikeyphw.xdm.android.model.MediaVariant
import com.mikeyphw.xdm.android.model.MediaVariantKind
import java.net.URI
import java.security.MessageDigest
import java.util.Locale

interface MediaInboxContract { suspend fun clearExpired() }

data class MediaCaptureCandidate(
    val sourceUrl: String,
    val pageUrl: String? = null,
    val title: String? = null,
    val kind: MediaSourceKind,
    val mimeType: String?,
    val container: String?,
    val codecs: String? = null,
    val durationMs: Long? = null,
    val thumbnailUrl: String? = null,
    val variants: List<MediaVariant> = emptyList(),
)

class MediaCaptureService(private val clock: () -> Long = System::currentTimeMillis) {
    private val urlPattern = Regex("""https?://[^\s<>\"']+""", RegexOption.IGNORE_CASE)

    fun detect(text: String, pageTitle: String? = null, pageUrl: String? = null): List<MediaCaptureRecord> =
        candidates(text, pageTitle, pageUrl).map { it.toRecord(clock()) }.distinctBy(MediaCaptureRecord::id)

    fun candidates(text: String, pageTitle: String? = null, pageUrl: String? = null): List<MediaCaptureCandidate> {
        val urls = urlPattern.findAll(text).map { it.value.trimEnd(')', ']', ',', '.', ';') }.distinct().toList()
        return urls.mapNotNull { url -> candidateFor(url, pageTitle, pageUrl) }
    }

    fun candidateFor(url: String, pageTitle: String? = null, pageUrl: String? = null): MediaCaptureCandidate? {
        val normalized = url.trim()
        if (!normalized.startsWith("http://", ignoreCase = true) && !normalized.startsWith("https://", ignoreCase = true)) return null
        val lowerPath = runCatching { URI(normalized).path.orEmpty().lowercase(Locale.ROOT) }.getOrDefault(normalized.lowercase(Locale.ROOT))
        val kind = when {
            lowerPath.endsWith(".m3u8") -> MediaSourceKind.HlsPlaylist
            lowerPath.endsWith(".mpd") -> MediaSourceKind.DashManifest
            lowerPath.endsWith(".mp4") || lowerPath.endsWith(".m4v") || lowerPath.endsWith(".webm") || lowerPath.endsWith(".mkv") || lowerPath.endsWith(".mov") -> MediaSourceKind.ProgressiveMedia
            lowerPath.endsWith(".mp3") || lowerPath.endsWith(".m4a") || lowerPath.endsWith(".aac") || lowerPath.endsWith(".flac") || lowerPath.endsWith(".ogg") || lowerPath.endsWith(".opus") -> MediaSourceKind.AudioStream
            lowerPath.contains("/hls/") || lowerPath.contains("manifest") && lowerPath.contains("m3u") -> MediaSourceKind.HlsPlaylist
            lowerPath.contains("/dash/") || lowerPath.contains("manifest") && lowerPath.contains("mpd") -> MediaSourceKind.DashManifest
            else -> MediaSourceKind.Unknown
        }
        if (kind == MediaSourceKind.Unknown) return null
        val mimeType = when (kind) {
            MediaSourceKind.HlsPlaylist -> "application/vnd.apple.mpegurl"
            MediaSourceKind.DashManifest -> "application/dash+xml"
            MediaSourceKind.AudioStream -> audioMime(lowerPath)
            MediaSourceKind.ProgressiveMedia, MediaSourceKind.VideoStream, MediaSourceKind.DirectFile -> videoMime(lowerPath)
            MediaSourceKind.Unknown -> null
        }
        val variants = listOf(MediaVariant(captureIdFor(normalized) + ":primary", captureIdFor(normalized), normalized, MediaVariantKind.Primary, mimeType))
        return MediaCaptureCandidate(
            sourceUrl = normalized,
            pageUrl = pageUrl,
            title = pageTitle?.takeIf(String::isNotBlank) ?: inferredTitle(normalized),
            kind = kind,
            mimeType = mimeType,
            container = containerFor(lowerPath, kind),
            variants = variants,
        )
    }

    fun parseHlsPlaylist(captureId: String, playlistUrl: String, playlistText: String): List<MediaVariant> {
        val lines = playlistText.lineSequence().map(String::trim).filter(String::isNotBlank).toList()
        val variants = mutableListOf<MediaVariant>()
        var pendingBandwidth: Long? = null
        var pendingResolution: Pair<Int, Int>? = null
        var index = 0
        for (line in lines) {
            if (line.startsWith("#EXT-X-STREAM-INF", ignoreCase = true)) {
                pendingBandwidth = Regex("BANDWIDTH=(\\d+)").find(line)?.groupValues?.getOrNull(1)?.toLongOrNull()
                pendingResolution = Regex("RESOLUTION=(\\d+)x(\\d+)").find(line)?.let { it.groupValues[1].toIntOrNull() to it.groupValues[2].toIntOrNull() }?.let { pair ->
                    val width = pair.first
                    val height = pair.second
                    if (width != null && height != null) width to height else null
                }
                continue
            }
            if (!line.startsWith("#") && pendingBandwidth != null) {
                val resolved = resolveVariantUrl(playlistUrl, line)
                variants += MediaVariant(
                    id = "$captureId:variant:$index",
                    captureId = captureId,
                    url = resolved,
                    kind = MediaVariantKind.Video,
                    mimeType = "application/vnd.apple.mpegurl",
                    width = pendingResolution?.first,
                    height = pendingResolution?.second,
                    bitrateBitsPerSecond = pendingBandwidth,
                    position = index++,
                )
                pendingBandwidth = null
                pendingResolution = null
            }
        }
        return variants
    }

    companion object {
        fun captureIdFor(url: String): String = "media-" + MessageDigest.getInstance("SHA-256")
            .digest(url.trim().lowercase(Locale.ROOT).toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(24)
    }

    private fun MediaCaptureCandidate.toRecord(now: Long): MediaCaptureRecord {
        val safeTitle = title?.takeIf(String::isNotBlank) ?: inferredTitle(sourceUrl)
        return MediaCaptureRecord(
            id = captureIdFor(sourceUrl),
            sourceUrl = sourceUrl,
            pageUrl = pageUrl,
            title = safeTitle,
            status = if (mimeType == null && durationMs == null && thumbnailUrl == null) MediaCaptureStatus.MetadataMissing else MediaCaptureStatus.MetadataReady,
            kind = kind,
            mimeType = mimeType,
            container = container,
            codecs = codecs,
            durationMs = durationMs,
            thumbnailUrl = thumbnailUrl,
            fileName = fileNameFor(sourceUrl, safeTitle, kind),
            variantCount = variants.size.coerceAtLeast(1),
            downloadId = null,
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
        )
    }

    private fun inferredTitle(url: String): String = runCatching {
        URI(url).path.substringAfterLast('/').substringBefore('?').substringBefore('#').takeIf(String::isNotBlank)
    }.getOrNull() ?: "Captured media"

    private fun fileNameFor(url: String, title: String, kind: MediaSourceKind): String {
        val pathName = runCatching { URI(url).path.substringAfterLast('/').takeIf(String::isNotBlank) }.getOrNull()
        val extension = when (kind) {
            MediaSourceKind.HlsPlaylist -> ".m3u8"
            MediaSourceKind.DashManifest -> ".mpd"
            MediaSourceKind.AudioStream -> pathName?.substringAfterLast('.', "")?.takeIf(String::isNotBlank)?.let { ".$it" } ?: ".m4a"
            else -> pathName?.substringAfterLast('.', "")?.takeIf(String::isNotBlank)?.let { ".$it" } ?: ".mp4"
        }
        val base = (pathName?.substringBeforeLast('.', missingDelimiterValue = "")?.takeIf(String::isNotBlank) ?: title)
            .replace(Regex("[\\/:*?\"<>|\\p{Cntrl}]"), "_")
            .trim('.', ' ')
            .ifBlank { "captured-media" }
            .take(160)
        return if (base.endsWith(extension, ignoreCase = true)) base else base + extension
    }

    private fun containerFor(path: String, kind: MediaSourceKind): String? = when {
        kind == MediaSourceKind.HlsPlaylist -> "HLS"
        kind == MediaSourceKind.DashManifest -> "DASH"
        path.endsWith(".mp4") || path.endsWith(".m4v") || path.endsWith(".m4a") -> "MP4"
        path.endsWith(".webm") -> "WebM"
        path.endsWith(".mkv") -> "Matroska"
        path.endsWith(".mp3") -> "MP3"
        path.endsWith(".flac") -> "FLAC"
        path.endsWith(".ogg") || path.endsWith(".opus") -> "Ogg"
        else -> null
    }

    private fun videoMime(path: String): String = when {
        path.endsWith(".webm") -> "video/webm"
        path.endsWith(".mkv") -> "video/x-matroska"
        path.endsWith(".mov") -> "video/quicktime"
        else -> "video/mp4"
    }

    private fun audioMime(path: String): String = when {
        path.endsWith(".mp3") -> "audio/mpeg"
        path.endsWith(".flac") -> "audio/flac"
        path.endsWith(".ogg") -> "audio/ogg"
        path.endsWith(".opus") -> "audio/opus"
        else -> "audio/mp4"
    }

    private fun resolveVariantUrl(baseUrl: String, value: String): String = runCatching { URI(baseUrl).resolve(value).toString() }.getOrDefault(value)
}
