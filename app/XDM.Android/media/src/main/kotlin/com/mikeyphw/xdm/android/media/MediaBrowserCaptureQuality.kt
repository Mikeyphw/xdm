package com.mikeyphw.xdm.android.media

import com.mikeyphw.xdm.android.model.MediaCaptureRecord
import com.mikeyphw.xdm.android.model.MediaCaptureStatus
import com.mikeyphw.xdm.android.model.MediaResolutionStatus
import com.mikeyphw.xdm.android.model.MediaSourceKind
import com.mikeyphw.xdm.android.model.MediaVariant
import com.mikeyphw.xdm.android.model.MediaVariantKind
import java.net.URI
import java.util.Locale

/**
 * Phase 30 Browser Capture Quality.
 *
 * This planner makes the browser/media sniffer less confetti-shaped: it groups related captures,
 * flags analytics noise, scores confidence, surfaces stale metadata, and keeps every diagnostic
 * redacted. It is intentionally pure Kotlin so the UI can preview quality decisions before any
 * worker, WebView hook, or Room migration changes are introduced.
 */
enum class CaptureQualitySignal(val label: String) {
    StrongManifest("strong manifest"),
    DirectMedia("direct media"),
    PageContext("page context"),
    Thumbnail("thumbnail"),
    Duration("duration"),
    TrackRichness("track richness"),
    Duplicate("duplicate"),
    TinyAsset("tiny/noisy asset"),
    AnalyticsBeacon("analytics beacon"),
    Protected("protected"),
    Live("live"),
    ExpiredSession("expired session"),
    StaleMetadata("stale metadata"),
}

enum class CaptureQualityDisposition(val label: String) {
    Treasure("Treasure"),
    NeedsMetadataRefresh("Needs metadata refresh"),
    IgnoreNoise("Ignore noise"),
    GroupWithExisting("Group with existing"),
    ProtectedDiagnostic("Protected diagnostic"),
    LiveReview("Live review"),
}

data class BrowserCaptureQualityRow(
    val captureId: String,
    val title: String,
    val sourceHost: String,
    val groupKey: String,
    val confidenceScore: Int,
    val disposition: CaptureQualityDisposition,
    val duplicateOfCaptureId: String?,
    val ignoredByDefault: Boolean,
    val refreshMetadataAvailable: Boolean,
    val signals: List<CaptureQualitySignal>,
    val safeDiagnostics: String,
) {
    val summary: String get() = listOf(
        disposition.label,
        "confidence=$confidenceScore",
        sourceHost.ifBlank { "unknown host" },
        signals.take(3).joinToString("/") { it.label },
    ).joinToString(" • ")
}

data class BrowserCaptureQualityDashboard(
    val rows: List<BrowserCaptureQualityRow>,
    val treasureCount: Int,
    val noiseCount: Int,
    val duplicateCount: Int,
    val refreshCount: Int,
    val protectedCount: Int,
    val liveCount: Int,
    val groupedHosts: List<String>,
    val secretSafe: Boolean,
) {
    val empty: Boolean get() = rows.isEmpty()
    val summary: String get() = listOf(
        "treasure=$treasureCount",
        "noise=$noiseCount",
        "duplicates=$duplicateCount",
        "refresh=$refreshCount",
        "protected=$protectedCount",
        "live=$liveCount",
        if (secretSafe) "secret-safe capture quality" else "redaction review",
    ).joinToString(" • ")
}

class MediaBrowserCaptureQualityPlanner {
    fun dashboard(
        captures: List<MediaCaptureRecord>,
        variants: List<MediaVariant>,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): BrowserCaptureQualityDashboard {
        val variantsByCapture = variants.groupBy { it.captureId }
        val seenGroups = mutableMapOf<String, String>()
        val rows = mutableListOf<BrowserCaptureQualityRow>()
        captures.sortedWith(compareBy<MediaCaptureRecord> { it.createdAtEpochMs }.thenBy { it.id }).forEach { capture ->
            val captureVariants = variantsByCapture[capture.id].orEmpty()
            val group = groupKeyFor(capture)
            val duplicateOf = seenGroups[group]
            if (duplicateOf == null) seenGroups[group] = capture.id
            rows += rowFor(capture, captureVariants, group, duplicateOf, nowEpochMs)
        }
        return BrowserCaptureQualityDashboard(
            rows = rows.sortedWith(compareByDescending<BrowserCaptureQualityRow> { it.confidenceScore }.thenBy { it.title.lowercase(Locale.US) }),
            treasureCount = rows.count { it.disposition == CaptureQualityDisposition.Treasure },
            noiseCount = rows.count { it.disposition == CaptureQualityDisposition.IgnoreNoise },
            duplicateCount = rows.count { it.disposition == CaptureQualityDisposition.GroupWithExisting },
            refreshCount = rows.count { it.refreshMetadataAvailable },
            protectedCount = rows.count { it.disposition == CaptureQualityDisposition.ProtectedDiagnostic },
            liveCount = rows.count { it.disposition == CaptureQualityDisposition.LiveReview },
            groupedHosts = rows.map { it.sourceHost }.filter { it.isNotBlank() }.distinct().sorted(),
            secretSafe = rows.all { !containsKnownSecret(it.safeDiagnostics) && !containsKnownSecret(it.summary) },
        )
    }

    private fun rowFor(
        capture: MediaCaptureRecord,
        variants: List<MediaVariant>,
        groupKey: String,
        duplicateOf: String?,
        nowEpochMs: Long,
    ): BrowserCaptureQualityRow {
        val signals = signalsFor(capture, variants, duplicateOf, nowEpochMs)
        val disposition = dispositionFor(capture, signals, duplicateOf)
        val score = confidenceFor(capture, variants, signals, disposition)
        val host = hostFor(capture.sourceUrl).ifBlank { hostFor(capture.pageUrl.orEmpty()) }
        val title = capture.title.ifBlank { capture.fileName.ifBlank { host.ifBlank { "Captured media" } } }
        val diagnostics = redactKnownSecrets(
            listOf(
                "Browser capture quality",
                "host=$host",
                "kind=${capture.kind.name}",
                "group=$groupKey",
                "disposition=${disposition.label}",
                "signals=${signals.joinToString(",") { it.label }}",
                duplicateOf?.let { "duplicateOf=$it" }.orEmpty(),
                if (capture.needsManifestRefresh(nowEpochMs)) "manifest refresh required" else "manifest fresh or finite",
            ).filter { it.isNotBlank() }.joinToString(" • "),
        )
        return BrowserCaptureQualityRow(
            captureId = capture.id,
            title = title,
            sourceHost = host,
            groupKey = groupKey,
            confidenceScore = score,
            disposition = disposition,
            duplicateOfCaptureId = duplicateOf,
            ignoredByDefault = disposition == CaptureQualityDisposition.IgnoreNoise || disposition == CaptureQualityDisposition.GroupWithExisting,
            refreshMetadataAvailable = signals.contains(CaptureQualitySignal.StaleMetadata) || signals.contains(CaptureQualitySignal.ExpiredSession),
            signals = signals,
            safeDiagnostics = diagnostics,
        )
    }

    private fun signalsFor(
        capture: MediaCaptureRecord,
        variants: List<MediaVariant>,
        duplicateOf: String?,
        nowEpochMs: Long,
    ): List<CaptureQualitySignal> {
        val result = mutableListOf<CaptureQualitySignal>()
        when (capture.kind) {
            MediaSourceKind.HlsPlaylist, MediaSourceKind.DashManifest -> result += CaptureQualitySignal.StrongManifest
            MediaSourceKind.ProgressiveMedia, MediaSourceKind.VideoStream, MediaSourceKind.AudioStream, MediaSourceKind.DirectFile -> result += CaptureQualitySignal.DirectMedia
            MediaSourceKind.Unknown -> Unit
        }
        if (!capture.pageUrl.isNullOrBlank()) result += CaptureQualitySignal.PageContext
        if (!capture.thumbnailUrl.isNullOrBlank() || variants.any { it.kind == MediaVariantKind.Thumbnail }) result += CaptureQualitySignal.Thumbnail
        if ((capture.durationMs ?: 0L) > 0L) result += CaptureQualitySignal.Duration
        if (variants.any { it.kind == MediaVariantKind.Audio } || variants.any { it.kind == MediaVariantKind.Subtitle } || variants.count { it.kind == MediaVariantKind.Video } > 1) result += CaptureQualitySignal.TrackRichness
        if (duplicateOf != null) result += CaptureQualitySignal.Duplicate
        if (isNoiseUrl(capture.sourceUrl)) result += CaptureQualitySignal.AnalyticsBeacon
        if (looksTinyAsset(capture)) result += CaptureQualitySignal.TinyAsset
        if (isProtected(capture, variants)) result += CaptureQualitySignal.Protected
        if (isLive(capture)) result += CaptureQualitySignal.Live
        if (capture.needsManifestRefresh(nowEpochMs) || hasTokenizedUrl(capture.sourceUrl) || variants.any { hasTokenizedUrl(it.url) }) result += CaptureQualitySignal.ExpiredSession
        if (capture.status == MediaCaptureStatus.MetadataMissing || capture.resolutionStatus == MediaResolutionStatus.RequiresRefresh || capture.resolutionStatus == MediaResolutionStatus.Failed) result += CaptureQualitySignal.StaleMetadata
        return result.distinct()
    }

    private fun dispositionFor(
        capture: MediaCaptureRecord,
        signals: List<CaptureQualitySignal>,
        duplicateOf: String?,
    ): CaptureQualityDisposition = when {
        signals.contains(CaptureQualitySignal.Protected) -> CaptureQualityDisposition.ProtectedDiagnostic
        signals.contains(CaptureQualitySignal.Live) -> CaptureQualityDisposition.LiveReview
        duplicateOf != null -> CaptureQualityDisposition.GroupWithExisting
        signals.contains(CaptureQualitySignal.AnalyticsBeacon) || signals.contains(CaptureQualitySignal.TinyAsset) -> CaptureQualityDisposition.IgnoreNoise
        capture.resolutionStatus == MediaResolutionStatus.RequiresRefresh || signals.contains(CaptureQualitySignal.StaleMetadata) || signals.contains(CaptureQualitySignal.ExpiredSession) -> CaptureQualityDisposition.NeedsMetadataRefresh
        else -> CaptureQualityDisposition.Treasure
    }

    private fun confidenceFor(
        capture: MediaCaptureRecord,
        variants: List<MediaVariant>,
        signals: List<CaptureQualitySignal>,
        disposition: CaptureQualityDisposition,
    ): Int {
        var score = 20
        if (signals.contains(CaptureQualitySignal.StrongManifest)) score += 35
        if (signals.contains(CaptureQualitySignal.DirectMedia)) score += 30
        if (signals.contains(CaptureQualitySignal.PageContext)) score += 10
        if (signals.contains(CaptureQualitySignal.Thumbnail)) score += 5
        if (signals.contains(CaptureQualitySignal.Duration)) score += 5
        if (signals.contains(CaptureQualitySignal.TrackRichness)) score += 10
        score += variants.size.coerceAtMost(6) * 2
        if (capture.mimeType != null) score += 5
        if (disposition == CaptureQualityDisposition.IgnoreNoise) score -= 60
        if (disposition == CaptureQualityDisposition.GroupWithExisting) score -= 25
        if (signals.contains(CaptureQualitySignal.ExpiredSession)) score -= 10
        if (signals.contains(CaptureQualitySignal.StaleMetadata)) score -= 10
        return score.coerceIn(0, 100)
    }

    private fun groupKeyFor(capture: MediaCaptureRecord): String {
        val host = hostFor(capture.sourceUrl).ifBlank { hostFor(capture.pageUrl.orEmpty()) }
        val path = runCatching { URI(capture.sourceUrl).path.orEmpty().lowercase(Locale.US) }.getOrDefault(capture.sourceUrl.substringBefore('?').lowercase(Locale.US))
        val cleanPath = path.replace(Regex("/[0-9a-f]{12,}(?=/|$)", RegexOption.IGNORE_CASE), "/<id>")
        return listOf(host, capture.kind.name, cleanPath.substringBeforeLast('/').ifBlank { cleanPath }).joinToString("|").take(160)
    }

    private fun hostFor(url: String): String = runCatching { URI(url).host.orEmpty().removePrefix("www.").lowercase(Locale.US) }.getOrDefault("")

    private fun isNoiseUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.US)
        return noiseTokens.any { lower.contains(it) }
    }

    private fun looksTinyAsset(capture: MediaCaptureRecord): Boolean {
        val lower = capture.sourceUrl.substringBefore('?').lowercase(Locale.US)
        val mime = capture.mimeType.orEmpty().lowercase(Locale.US)
        return mime.startsWith("image/") || lower.endsWith("/pixel.mp4") || lower.contains("1x1") || lower.endsWith(".gif")
    }

    private fun isProtected(capture: MediaCaptureRecord, variants: List<MediaVariant>): Boolean {
        val merged = (capture.sourceUrl + " " + capture.pageUrl.orEmpty() + " " + capture.codecs.orEmpty() + " " + variants.joinToString(" ") { it.url + " " + it.codecs.orEmpty() }).lowercase(Locale.US)
        return listOf("widevine", "playready", "fairplay", "drm", "contentprotection", "license").any { merged.contains(it) }
    }

    private fun isLive(capture: MediaCaptureRecord): Boolean {
        val merged = (capture.title + " " + capture.sourceUrl + " " + capture.pageUrl.orEmpty()).lowercase(Locale.US)
        return merged.contains("/live") || merged.contains("live-") || merged.contains("livestream") || merged.contains("eventstream")
    }

    private fun hasTokenizedUrl(url: String): Boolean = secretPatterns.any { it.containsMatchIn(url) }

    private fun containsKnownSecret(text: String): Boolean = secretPatterns.any { it.containsMatchIn(text) }

    private fun redactKnownSecrets(text: String): String {
        var redacted = text
        secretPatterns.forEach { pattern -> redacted = pattern.replace(redacted, "<redacted>") }
        return redacted
    }

    private companion object {
        val noiseTokens = listOf("/analytics", "/beacon", "/collect", "/metrics", "/telemetry", "/ads/", "/adserver", "doubleclick", "googletag", "pixel")
        val secretPatterns = listOf(
            Regex("""Bearer\s+[A-Za-z0-9._~+/=-]+""", RegexOption.IGNORE_CASE),
            Regex("""Cookie\s*[:=]\s*[^\n;]+""", RegexOption.IGNORE_CASE),
            Regex("""(?i)(token|session|sid|sig|signature|auth|key)=((?!<redacted>)[^\s&#;]+)"""),
            Regex("secret-[A-Za-z0-9._-]+", RegexOption.IGNORE_CASE),
        )
    }
}
