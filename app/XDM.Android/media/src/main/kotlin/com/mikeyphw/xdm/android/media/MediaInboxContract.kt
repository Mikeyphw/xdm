package com.mikeyphw.xdm.android.media

import com.mikeyphw.xdm.android.model.MediaCaptureRecord
import com.mikeyphw.xdm.android.model.MediaCaptureStatus
import com.mikeyphw.xdm.android.model.MediaResolutionStatus
import com.mikeyphw.xdm.android.model.MediaSourceKind
import com.mikeyphw.xdm.android.model.MediaManifestRole
import com.mikeyphw.xdm.android.model.MediaVariant
import com.mikeyphw.xdm.android.model.MediaVariantKind
import com.mikeyphw.xdm.android.model.PageObservationProof
import com.mikeyphw.xdm.android.model.BrowserHandoffMediaPolicy
import com.mikeyphw.xdm.android.model.ExternalUrlPolicy
import com.mikeyphw.xdm.android.util.sanitizeFileName
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node

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

data class MediaRequestFacts(
    val url: String,
    val mimeType: String? = null,
    val contentLength: Long? = null,
    val pageUrl: String? = null,
    val pageTitle: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val frameUrl: String? = null,
    val stableMediaId: String? = null,
    val requestFingerprint: String? = null,
    val sessionRevision: Long? = null,
    val proposedHeaders: Map<String, String> = emptyMap(),
    val finalHeaders: Map<String, String> = emptyMap(),
    val pageObservationProof: PageObservationProof? = null,
    val requiresPageObservationProof: Boolean = false,
)

data class MediaManifestSummary(
    val kind: MediaSourceKind,
    val role: MediaManifestRole = MediaManifestRole.Unknown,
    val variantCount: Int,
    val audioTrackCount: Int = 0,
    val subtitleTrackCount: Int = 0,
    val thumbnailCount: Int = 0,
    val isLive: Boolean? = null,
    val hasDrm: Boolean = false,
    val protectionScheme: String? = null,
) {
    val variantSummary: String
        get() = listOfNotNull(
            variantCount.takeIf { it > 0 }?.let { "$it variants" },
            audioTrackCount.takeIf { it > 0 }?.let { "$it audio" },
            subtitleTrackCount.takeIf { it > 0 }?.let { "$it subtitles" },
            thumbnailCount.takeIf { it > 0 }?.let { "$it thumbnails" },
            if (isLive == true) "live" else null,
            if (hasDrm) "protected" else null,
        ).joinToString(" • ").ifBlank { "single stream" }
}

class MediaCandidateClassifier {
    fun classify(facts: MediaRequestFacts): MediaSourceKind {
        val normalized = facts.url.trim()
        if (!normalized.startsWith("http://", ignoreCase = true) && !normalized.startsWith("https://", ignoreCase = true)) return MediaSourceKind.Unknown
        val lowerPath = runCatching { URI(normalized).path.orEmpty().lowercase(Locale.ROOT) }.getOrDefault(normalized.lowercase(Locale.ROOT))
        val lowerUrl = normalized.lowercase(Locale.ROOT)
        val mime = facts.mimeType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
        val hardNonMediaMime = mime?.let(::isHardNonMediaMime) == true
        return when {
            hardNonMediaMime -> MediaSourceKind.Unknown
            mime in HlsMimeTypes || lowerPath.endsWith(".m3u8") || lowerUrl.contains(".m3u8?") -> MediaSourceKind.HlsPlaylist
            mime in DashMimeTypes || lowerPath.endsWith(".mpd") || lowerUrl.contains(".mpd?") -> MediaSourceKind.DashManifest
            mime?.startsWith("video/") == true -> MediaSourceKind.ProgressiveMedia
            mime?.startsWith("audio/") == true -> MediaSourceKind.AudioStream
            lowerPath.endsWith(".mp4") || lowerPath.endsWith(".m4v") || lowerPath.endsWith(".webm") || lowerPath.endsWith(".mkv") || lowerPath.endsWith(".mov") -> MediaSourceKind.ProgressiveMedia
            lowerPath.endsWith(".mp3") || lowerPath.endsWith(".m4a") || lowerPath.endsWith(".aac") || lowerPath.endsWith(".flac") || lowerPath.endsWith(".ogg") || lowerPath.endsWith(".opus") -> MediaSourceKind.AudioStream
            lowerPath.contains("/hls/") || lowerPath.contains("manifest") && lowerPath.contains("m3u") -> MediaSourceKind.HlsPlaylist
            lowerPath.contains("/dash/") || lowerPath.contains("manifest") && lowerPath.contains("mpd") -> MediaSourceKind.DashManifest
            else -> MediaSourceKind.Unknown
        }
    }

    fun isCandidate(facts: MediaRequestFacts): Boolean = classify(facts) != MediaSourceKind.Unknown

    companion object {
        val HlsMimeTypes = setOf(
            "application/vnd.apple.mpegurl",
            "application/x-mpegurl",
            "audio/mpegurl",
            "audio/x-mpegurl",
        )
        val DashMimeTypes = setOf("application/dash+xml", "video/vnd.mpeg.dash.mpd", "application/mpd")
        fun isHardNonMediaMime(mime: String): Boolean =
            mime in setOf("application/json", "application/ld+json", "application/javascript", "application/x-javascript", "text/html", "text/css", "text/javascript") ||
                mime.startsWith("image/") || mime.startsWith("font/")
    }
}

class MediaCaptureService(private val clock: () -> Long = System::currentTimeMillis) {
    private val urlPattern = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)

    fun detect(text: String, pageTitle: String? = null, pageUrl: String? = null): List<MediaCaptureRecord> =
        recordsFor(candidates(text, pageTitle, pageUrl))

    fun recordFor(candidate: MediaCaptureCandidate): MediaCaptureRecord = candidate.toRecord(clock())

    fun recordsFor(candidates: List<MediaCaptureCandidate>): List<MediaCaptureRecord> =
        candidates.map { it.toRecord(clock()) }.distinctBy(MediaCaptureRecord::id)

    fun candidates(text: String, pageTitle: String? = null, pageUrl: String? = null): List<MediaCaptureCandidate> {
        val urls = urlPattern.findAll(text).map { it.value.trimEnd(')', ']', ',', '.', ';') }.distinct().toList()
        return urls.mapNotNull { url -> candidateFor(url, pageTitle, pageUrl) }
    }

    fun candidatesFromHtml(html: String, pageTitle: String? = null, pageUrl: String? = null): List<MediaCaptureCandidate> {
        val sources = Regex("""(?:src|href|poster)=['\"]([^'\"]+)['\"]""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .map { value -> pageUrl?.let { resolveVariantUrl(it, value) } ?: value }
            .distinct()
            .toList()
        return sources.mapNotNull { url -> candidateFor(url, pageTitle, pageUrl) }
    }

    fun candidateFor(
        url: String,
        pageTitle: String? = null,
        pageUrl: String? = null,
        mimeTypeHint: String? = null,
        contentLength: Long? = null,
        headers: Map<String, String> = emptyMap(),
        durationMs: Long? = null,
        thumbnailUrl: String? = null,
    ): MediaCaptureCandidate? {
        val normalized = url.trim()
        val facts = MediaRequestFacts(normalized, mimeTypeHint, contentLength, pageUrl, pageTitle, headers)
        val kind = MediaCandidateClassifier().classify(facts)
        if (kind == MediaSourceKind.Unknown) return null
        val lowerPath = runCatching { URI(normalized).path.orEmpty().lowercase(Locale.ROOT) }.getOrDefault(normalized.lowercase(Locale.ROOT))
        val normalizedMimeHint = mimeTypeHint?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
        val mimeType = normalizedMimeHint ?: when (kind) {
            MediaSourceKind.HlsPlaylist -> "application/vnd.apple.mpegurl"
            MediaSourceKind.DashManifest -> "application/dash+xml"
            MediaSourceKind.AudioStream -> audioMime(lowerPath)
            MediaSourceKind.ProgressiveMedia, MediaSourceKind.VideoStream, MediaSourceKind.DirectFile -> videoMime(lowerPath)
            MediaSourceKind.Unknown -> null
        }
        val captureId = captureIdFor(normalized)
        val variants = listOf(
            MediaVariant(
                id = "$captureId:primary",
                captureId = captureId,
                url = normalized,
                kind = MediaVariantKind.Primary,
                mimeType = mimeType,
                displayLabel = listOfNotNull("Primary", contentLength?.takeIf { it > 0L }?.let { "${it / 1024 / 1024} MB" }).joinToString(" • "),
            ),
        )
        return MediaCaptureCandidate(
            sourceUrl = normalized,
            pageUrl = pageUrl,
            title = pageTitle?.takeIf(String::isNotBlank) ?: inferredTitle(normalized),
            kind = kind,
            mimeType = mimeType,
            container = containerFor(lowerPath, kind),
            durationMs = durationMs?.takeIf { it > 0L },
            thumbnailUrl = thumbnailUrl?.takeIf(String::isNotBlank),
            variants = variants,
        )
    }

    fun parseHlsPlaylist(captureId: String, playlistUrl: String, playlistText: String, expiresAtEpochMs: Long? = null): List<MediaVariant> {
        val lines = playlistText.lineSequence().map(String::trim).filter(String::isNotBlank).toList()
        val variants = mutableListOf<MediaVariant>()
        var index = 0
        for (line in lines) {
            if (!line.startsWith("#EXT-X-MEDIA", ignoreCase = true)) continue
            val attrs = attributeList(line.substringAfter(':', ""))
            val type = attrs["TYPE"]?.uppercase(Locale.ROOT)
            val kind = when (type) {
                "AUDIO" -> MediaVariantKind.Audio
                "SUBTITLES", "CLOSED-CAPTIONS" -> MediaVariantKind.Subtitle
                else -> null
            } ?: continue
            val uri = attrs["URI"]?.takeIf(String::isNotBlank)
            // CLOSED-CAPTIONS normally use INSTREAM-ID with no URI. Preserve the track relation
            // as metadata while keeping the authoritative master as the execution URL.
            if (uri == null && type != "CLOSED-CAPTIONS") continue
            val label = listOfNotNull(attrs["NAME"], attrs["LANGUAGE"]?.uppercase(Locale.ROOT), attrs["CHANNELS"]).joinToString(" • ").ifBlank { kind.name }
            variants += MediaVariant(
                id = "$captureId:hls-media:$index",
                captureId = captureId,
                url = uri?.let { resolveVariantUrl(playlistUrl, it) } ?: playlistUrl,
                kind = kind,
                mimeType = if (type == "CLOSED-CAPTIONS") "application/cea-608" else if (kind == MediaVariantKind.Subtitle) "text/vtt" else "application/vnd.apple.mpegurl",
                language = attrs["LANGUAGE"],
                position = index,
                displayLabel = label,
                expiresAtEpochMs = expiresAtEpochMs,
                groupId = attrs["GROUP-ID"],
                isDefault = attrs["DEFAULT"].equals("YES", true),
                isAutoselect = attrs["AUTOSELECT"].equals("YES", true),
                isForced = attrs["FORCED"].equals("YES", true),
                channels = attrs["CHANNELS"],
                inStreamId = attrs["INSTREAM-ID"],
            )
            index++
        }

        var pending: Map<String, String>? = null
        for (line in lines) {
            if (line.startsWith("#EXT-X-STREAM-INF", ignoreCase = true)) {
                pending = attributeList(line.substringAfter(':', ""))
                continue
            }
            val attrs = pending ?: continue
            if (!line.startsWith("#")) {
                val bandwidth = attrs["BANDWIDTH"]?.toLongOrNull()
                val resolution = attrs["RESOLUTION"]?.let { resolution ->
                    val width = resolution.substringBefore('x').toIntOrNull()
                    val height = resolution.substringAfter('x').toIntOrNull()
                    if (width != null && height != null) width to height else null
                }
                variants += MediaVariant(
                    id = "$captureId:variant:$index",
                    captureId = captureId,
                    url = resolveVariantUrl(playlistUrl, line),
                    kind = MediaVariantKind.Video,
                    mimeType = "application/vnd.apple.mpegurl",
                    width = resolution?.first,
                    height = resolution?.second,
                    bitrateBitsPerSecond = bandwidth,
                    codecs = attrs["CODECS"],
                    position = index,
                    displayLabel = labelFor(resolution?.second, bandwidth, attrs["CODECS"]),
                    expiresAtEpochMs = expiresAtEpochMs,
                    audioGroupId = attrs["AUDIO"],
                    subtitleGroupId = attrs["SUBTITLES"],
                )
                index++
                pending = null
            }
        }
        return variants
    }

    fun inspectHlsPlaylist(playlistText: String): MediaManifestSummary {
        val lines = playlistText.lineSequence().map(String::trim).filter(String::isNotBlank).toList()
        val isMaster = lines.any { it.startsWith("#EXT-X-STREAM-INF", true) || it.startsWith("#EXT-X-MEDIA", true) }
        val role = if (isMaster) MediaManifestRole.HlsMaster else MediaManifestRole.HlsMedia
        val variants = lines.count { it.startsWith("#EXT-X-STREAM-INF", ignoreCase = true) }
        val mediaGroups = lines.filter { it.startsWith("#EXT-X-MEDIA", ignoreCase = true) }.map { attributeList(it.substringAfter(':', "")) }
        val keyLines = lines.filter { it.startsWith("#EXT-X-KEY", true) || it.startsWith("#EXT-X-SESSION-KEY", true) }
        val protection = keyLines.map { attributeList(it.substringAfter(':', "")) }.firstOrNull { attrs -> attrs["METHOD"]?.equals("NONE", ignoreCase = true) != true }
        val scheme = protection?.let { it["KEYFORMAT"] ?: it["METHOD"] }
        val protectionEvidence = BrowserHandoffMediaPolicy.classifyProtection(
            hlsKeyMetadata = scheme,
            dashContentProtection = null,
            browserEncryptionEvent = null,
            resolverReport = null,
        )
        return MediaManifestSummary(
            kind = MediaSourceKind.HlsPlaylist,
            role = role,
            variantCount = variants.coerceAtLeast(1),
            audioTrackCount = mediaGroups.count { it["TYPE"]?.equals("AUDIO", ignoreCase = true) == true },
            subtitleTrackCount = mediaGroups.count { it["TYPE"]?.equals("SUBTITLES", ignoreCase = true) == true || it["TYPE"]?.equals("CLOSED-CAPTIONS", ignoreCase = true) == true },
            // A master playlist is neither live nor VOD by itself. Live/VOD belongs to media playlists.
            isLive = if (isMaster) null else lines.none { it.equals("#EXT-X-ENDLIST", ignoreCase = true) },
            hasDrm = protectionEvidence.protected,
            protectionScheme = protectionEvidence.evidence.firstOrNull()?.scheme ?: scheme,
        )
    }

    fun parseDashManifest(captureId: String, manifestUrl: String, manifestText: String, expiresAtEpochMs: Long? = null): List<MediaVariant> {
        val document = parseMpd(manifestText) ?: return emptyList()
        val mpd = document.documentElement ?: return emptyList()
        val rootBase = directChildText(mpd, "BaseURL")?.let { resolveVariantUrl(manifestUrl, it) } ?: manifestUrl
        val periods = directChildren(mpd, "Period").ifEmpty { listOf(mpd) }
        val variants = mutableListOf<MediaVariant>()
        var position = 0
        periods.forEachIndexed { periodIndex, period ->
            val periodBase = directChildText(period, "BaseURL")?.let { resolveVariantUrl(rootBase, it) } ?: rootBase
            directChildren(period, "AdaptationSet").forEachIndexed { adaptationIndex, adaptation ->
                val adaptationBase = directChildText(adaptation, "BaseURL")?.let { resolveVariantUrl(periodBase, it) } ?: periodBase
                val adaptationMime = adaptation.attrOrNull("mimeType")
                val adaptationContent = adaptation.attrOrNull("contentType")
                val adaptationLang = adaptation.attrOrNull("lang")
                val adaptationCodecs = adaptation.attrOrNull("codecs")
                val adaptationTemplate = directChildren(adaptation, "SegmentTemplate").firstOrNull()
                val representations = directChildren(adaptation, "Representation")
                representations.forEachIndexed { repIndex, rep ->
                    val repBase = directChildText(rep, "BaseURL")?.let { resolveVariantUrl(adaptationBase, it) } ?: adaptationBase
                    val codecs = rep.attrOrNull("codecs") ?: adaptationCodecs
                    val mime = rep.attrOrNull("mimeType") ?: adaptationMime ?: mimeFromCodecs(codecs)
                    val content = rep.attrOrNull("contentType") ?: adaptationContent
                    val lang = rep.attrOrNull("lang") ?: adaptationLang
                    val kind = dashVariantKind(mime, content, codecs)
                    val template = directChildren(rep, "SegmentTemplate").firstOrNull() ?: adaptationTemplate
                    val repId = rep.attrOrNull("id")
                    val label = labelFor(rep.attrOrNull("height")?.toIntOrNull(), rep.attrOrNull("bandwidth")?.toLongOrNull(), codecs, lang, kind) +
                        template?.attrOrNull("media")?.let { " • segmented" }.orEmpty()
                    variants += MediaVariant(
                        id = "$captureId:dash:$periodIndex:$adaptationIndex:${repId ?: repIndex}",
                        captureId = captureId,
                        url = repBase,
                        kind = kind,
                        mimeType = mime,
                        width = rep.attrOrNull("width")?.toIntOrNull(),
                        height = rep.attrOrNull("height")?.toIntOrNull(),
                        bitrateBitsPerSecond = rep.attrOrNull("bandwidth")?.toLongOrNull(),
                        codecs = codecs,
                        language = lang,
                        position = position++,
                        displayLabel = label,
                        expiresAtEpochMs = expiresAtEpochMs,
                        groupId = adaptation.attrOrNull("id") ?: "$periodIndex:$adaptationIndex",
                    )
                }
            }
        }
        return variants
    }

    fun inspectDashManifest(manifestText: String): MediaManifestSummary {
        val document = parseMpd(manifestText)
        val mpd = document?.documentElement
        val summaryVariants = parseDashManifest("inspect", "https://example.invalid/manifest.mpd", manifestText)
        val protectionElements = document?.getElementsByTagNameNS("*", "ContentProtection")
        val schemes = buildList {
            if (protectionElements != null) for (i in 0 until protectionElements.length) {
                val element = protectionElements.item(i) as? Element ?: continue
                element.attrOrNull("schemeIdUri")?.let(::add)
            }
        }
        val hasDrm = schemes.isNotEmpty()
        val protectionEvidence = BrowserHandoffMediaPolicy.classifyProtection(
            hlsKeyMetadata = null,
            dashContentProtection = schemes.joinToString(",").takeIf { hasDrm },
            browserEncryptionEvent = null,
            resolverReport = null,
        )
        return MediaManifestSummary(
            kind = MediaSourceKind.DashManifest,
            role = MediaManifestRole.DashMpd,
            variantCount = summaryVariants.count { it.kind == MediaVariantKind.Video }.coerceAtLeast(summaryVariants.size.coerceAtLeast(1)),
            audioTrackCount = summaryVariants.count { it.kind == MediaVariantKind.Audio },
            subtitleTrackCount = summaryVariants.count { it.kind == MediaVariantKind.Subtitle },
            isLive = mpd?.attrOrNull("type")?.equals("dynamic", true),
            hasDrm = protectionEvidence.protected,
            protectionScheme = schemes.firstOrNull(),
        )
    }

    fun selectVariant(record: MediaCaptureRecord, variants: List<MediaVariant>, variantId: String, nowEpochMs: Long = clock()): MediaCaptureRecord {
        val selected = variants.firstOrNull { it.id == variantId } ?: return record
        return record.copy(
            selectedVariantId = selected.id,
            selectedVariantUrl = selected.url,
            resolutionStatus = if (selected.isExpired(nowEpochMs)) MediaResolutionStatus.RequiresRefresh else MediaResolutionStatus.Resolved,
            updatedAtEpochMs = nowEpochMs,
        )
    }

    fun selectedOrBestVariant(record: MediaCaptureRecord, variants: List<MediaVariant>, nowEpochMs: Long = clock()): MediaVariant? {
        val usable = variants.filterNot { it.isExpired(nowEpochMs) }
        return usable.firstOrNull { it.id == record.selectedVariantId }
            ?: usable.maxWithOrNull(compareBy<MediaVariant> { variantRank(it.kind) }.thenBy { it.height ?: 0 }.thenBy { it.bitrateBitsPerSecond ?: 0L })
            ?: variants.firstOrNull { it.id == record.selectedVariantId }
            ?: variants.maxWithOrNull(compareBy<MediaVariant> { variantRank(it.kind) }.thenBy { it.height ?: 0 }.thenBy { it.bitrateBitsPerSecond ?: 0L })
    }

    fun refreshRecordAfterResolution(record: MediaCaptureRecord, variants: List<MediaVariant>, nowEpochMs: Long = clock(), maxAgeMs: Long = DEFAULT_MANIFEST_MAX_AGE_MS): MediaCaptureRecord {
        val selected = selectedOrBestVariant(record, variants, nowEpochMs)
        val expiry = if (record.isPlaylist) nowEpochMs + maxAgeMs else null
        return record.copy(
            variantCount = variants.size.coerceAtLeast(1),
            selectedVariantId = selected?.id ?: record.selectedVariantId,
            selectedVariantUrl = selected?.url ?: record.selectedVariantUrl ?: record.sourceUrl,
            manifestExpiresAtEpochMs = expiry,
            lastResolvedAtEpochMs = nowEpochMs,
            resolutionStatus = if (record.isPlaylist && variants.isEmpty()) MediaResolutionStatus.Failed else MediaResolutionStatus.Resolved,
            updatedAtEpochMs = nowEpochMs,
        )
    }

    fun decorateRecordWithManifestSummary(record: MediaCaptureRecord, summary: MediaManifestSummary, nowEpochMs: Long = clock()): MediaCaptureRecord = record.copy(
        container = record.container ?: when (summary.kind) {
            MediaSourceKind.HlsPlaylist -> "HLS"
            MediaSourceKind.DashManifest -> "DASH"
            else -> null
        },
        manifestRole = summary.role,
        manifestIsLive = summary.isLive,
        manifestProtected = summary.hasDrm,
        manifestProtectionScheme = summary.protectionScheme,
        updatedAtEpochMs = nowEpochMs,
    )

    private fun MediaCaptureCandidate.toRecord(now: Long): MediaCaptureRecord {
        val safeTitle = title?.takeIf(String::isNotBlank) ?: inferredTitle(sourceUrl)
        val firstVariant = variants.firstOrNull()
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
            selectedVariantId = firstVariant?.id,
            selectedVariantUrl = firstVariant?.url ?: sourceUrl,
            resolutionStatus = if (kind == MediaSourceKind.HlsPlaylist || kind == MediaSourceKind.DashManifest) MediaResolutionStatus.Unresolved else MediaResolutionStatus.Resolved,
        )
    }

    companion object {
        const val DEFAULT_MANIFEST_MAX_AGE_MS = 15 * 60 * 1000L

        /** Logical capture identity: normalize scheme/host, preserve case-sensitive path/query, and
         * redact credential values so a refreshed signed URL updates the same capture. */
        fun captureIdentityUrl(url: String): String = ExternalUrlPolicy.persistableUrl(url)
            ?: url.substringBefore('#').trim()

        fun captureIdFor(url: String): String = "media-" + MessageDigest.getInstance("SHA-256")
            .digest(captureIdentityUrl(url).toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(24)

        fun browserCaptureIdFor(url: String, sessionId: String, requestFingerprint: String): String {
            // sessionId/requestFingerprint are request-provenance only. They must not create a new
            // logical capture for retries, range requests, or refreshed browser credentials.
            @Suppress("UNUSED_VARIABLE") val requestEvidence = sessionId to requestFingerprint
            return "media-browser-" + MessageDigest.getInstance("SHA-256")
                .digest(captureIdentityUrl(url).toByteArray())
                .joinToString("") { "%02x".format(it) }
                .take(28)
        }
    }

    private fun parseMpd(text: String) = runCatching {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        factory.newDocumentBuilder().parse(text.byteInputStream(Charsets.UTF_8))
    }.getOrNull()

    private fun directChildren(parent: Element, localName: String): List<Element> = buildList {
        val nodes = parent.childNodes
        for (i in 0 until nodes.length) {
            val child = nodes.item(i)
            if (child.nodeType == Node.ELEMENT_NODE) {
                val element = child as Element
                if ((element.localName ?: element.tagName.substringAfter(':')).equals(localName, true)) add(element)
            }
        }
    }

    private fun directChildText(parent: Element, localName: String): String? = directChildren(parent, localName).firstOrNull()?.textContent?.trim()?.takeIf(String::isNotBlank)
    private fun Element.attrOrNull(name: String): String? = getAttribute(name).trim().takeIf(String::isNotBlank)

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
        val base = sanitizeFileName(
            pathName?.substringBeforeLast('.', missingDelimiterValue = "")?.takeIf(String::isNotBlank) ?: title,
            fallback = "captured-media",
            maxLength = 160,
        )
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

    private fun attr(attributes: String, name: String): String? = Regex("""$name=['\"]([^'\"]+)['\"]""", RegexOption.IGNORE_CASE)
        .find(attributes)
        ?.groupValues
        ?.getOrNull(1)

    private fun attributeList(value: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        var index = 0
        while (index < value.length) {
            while (index < value.length && (value[index] == ',' || value[index].isWhitespace())) index++
            val keyStart = index
            while (index < value.length && value[index] != '=') index++
            if (index >= value.length) break
            val key = value.substring(keyStart, index).trim().uppercase(Locale.ROOT)
            index++
            val parsedValue = if (index < value.length && value[index] == '"') {
                index++
                val start = index
                while (index < value.length && value[index] != '"') index++
                value.substring(start, index).also { if (index < value.length) index++ }
            } else {
                val start = index
                while (index < value.length && value[index] != ',') index++
                value.substring(start, index).trim()
            }
            if (key.isNotBlank()) result[key] = parsedValue
            while (index < value.length && value[index] != ',') index++
        }
        return result
    }

    private fun dashVariantKind(mimeType: String?, contentType: String?, codecs: String?): MediaVariantKind = when {
        contentType.equals("audio", ignoreCase = true) || mimeType?.startsWith("audio/") == true || codecs?.startsWith("mp4a", ignoreCase = true) == true -> MediaVariantKind.Audio
        contentType.equals("text", ignoreCase = true) || contentType.equals("subtitle", ignoreCase = true) || mimeType?.startsWith("text/") == true || mimeType == "application/ttml+xml" || mimeType == "application/mp4" && codecs?.contains("wvtt", ignoreCase = true) == true -> MediaVariantKind.Subtitle
        else -> MediaVariantKind.Video
    }

    private fun mimeFromCodecs(codecs: String?): String = when {
        codecs?.startsWith("mp4a", ignoreCase = true) == true -> "audio/mp4"
        codecs?.contains("wvtt", ignoreCase = true) == true -> "application/mp4"
        else -> "video/mp4"
    }

    private fun labelFor(height: Int?, bandwidth: Long?, codecs: String? = null, language: String? = null, kind: MediaVariantKind = MediaVariantKind.Video): String = listOfNotNull(
        height?.let { "${it}p" },
        bandwidth?.takeIf { it > 0 }?.let { "${it / 1000} kbps" },
        language?.uppercase(Locale.ROOT),
        codecs,
    ).joinToString(" • ").ifBlank { if (kind == MediaVariantKind.Subtitle) "Subtitle" else if (kind == MediaVariantKind.Audio) "Audio" else "Auto" }

    private fun variantRank(kind: MediaVariantKind): Int = when (kind) {
        MediaVariantKind.Video -> 5
        MediaVariantKind.Primary -> 4
        MediaVariantKind.Audio -> 3
        MediaVariantKind.Subtitle -> 2
        MediaVariantKind.Thumbnail -> 1
    }

    private fun resolveVariantUrl(baseUrl: String, value: String): String = runCatching { URI(baseUrl).resolve(value).toString() }.getOrDefault(value)
}
