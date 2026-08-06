package com.mikeyphw.xdm.android.media

import com.mikeyphw.xdm.android.model.DebugArea
import com.mikeyphw.xdm.android.model.DebugEventRecorder
import com.mikeyphw.xdm.android.model.DebugSeverity
import com.mikeyphw.xdm.android.model.MediaCaptureRecord
import com.mikeyphw.xdm.android.model.NoOpDebugEventRecorder
import com.mikeyphw.xdm.android.model.MediaSourceKind
import com.mikeyphw.xdm.android.model.MediaVariant
import com.mikeyphw.xdm.android.model.PrivacyDiagnosticsRedactor
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.Locale

/** Origin of an app-side sniffing request. Kept browser-neutral for future GeckoView reuse. */
enum class MediaSniffingSource {
    ManualPage,
    BatchInput,
    SharedText,
    BrowserExtension,
    NetworkObservation,
    AppPageProbe,
}

data class MediaSniffingInput(
    val url: String?,
    val finalUrl: String? = null,
    val mimeType: String? = null,
    val contentDisposition: String? = null,
    val contentLength: Long? = null,
    val durationMs: Long? = null,
    val thumbnailUrl: String? = null,
    val bodyPrefix: String? = null,
    val pageUrl: String? = null,
    val pageTitle: String? = null,
    val requestHeaders: Map<String, String> = emptyMap(),
    val source: MediaSniffingSource = MediaSniffingSource.ManualPage,
)

data class MediaSniffingCandidate(
    val url: String,
    val kind: MediaSourceKind,
    val rank: Int,
    val reason: String,
    val mimeType: String?,
    val pageUrl: String?,
    val title: String?,
)

data class MediaSniffingPlan(
    val candidates: List<MediaSniffingCandidate>,
    val records: List<MediaCaptureRecord>,
    val variants: List<MediaVariant>,
    val diagnostics: List<String>,
) {
    val summary: String
        get() = listOf(
            "${candidates.size} candidates",
            "${records.size} captures",
            if (diagnostics.any { it.contains("page-probe", ignoreCase = true) }) "page probe" else "static sniff",
        ).joinToString(" • ")
}

data class MediaPageProbePolicy(
    val connectTimeoutMillis: Int = 10_000,
    val readTimeoutMillis: Int = 10_000,
    val bodyPrefixBytes: Int = 768 * 1024,
    val followRedirects: Boolean = true,
    val executeJavaScript: Boolean = false,
    val bypassDrm: Boolean = false,
)

/** Bounded page fetcher for manual page URLs. It reads only a prefix and never executes JS. */
class MediaPageProbe(
    private val engine: MediaSniffingEngine = MediaSniffingEngine(),
    private val policy: MediaPageProbePolicy = MediaPageProbePolicy(),
    private val debugRecorder: DebugEventRecorder = NoOpDebugEventRecorder,
) {
    fun probePage(url: String, pageTitle: String? = null, requestHeaders: Map<String, String> = emptyMap()): MediaSniffingPlan {
        val normalized = url.trim()
        val uri = runCatching { URI(normalized) }.getOrNull()
        val scheme = uri?.scheme?.lowercase(Locale.US)
        if (scheme != "http" && scheme != "https") {
            debugRecorder.record(
                area = DebugArea.MediaSniffing,
                severity = DebugSeverity.Warning,
                action = "page-probe",
                result = "rejected",
                safeDetails = mapOf("url" to normalized, "reason" to "unsupported-scheme"),
            )
            return MediaSniffingPlan(
                candidates = emptyList(),
                records = emptyList(),
                variants = emptyList(),
                diagnostics = listOf("page-probe rejected unsupported scheme"),
            )
        }
        val connection = runCatching { uri.toURL().openConnection() as HttpURLConnection }.getOrElse { error ->
            debugRecorder.record(
                area = DebugArea.MediaSniffing,
                severity = DebugSeverity.Error,
                action = "page-probe",
                result = "open-failed",
                safeDetails = mapOf("url" to normalized, "error" to error.javaClass.simpleName),
            )
            return MediaSniffingPlan(
                candidates = emptyList(),
                records = emptyList(),
                variants = emptyList(),
                diagnostics = listOf("page-probe open failed: ${error.javaClass.simpleName}"),
            )
        }
        return try {
            connection.instanceFollowRedirects = policy.followRedirects
            connection.connectTimeout = policy.connectTimeoutMillis
            connection.readTimeout = policy.readTimeoutMillis
            connection.requestMethod = "GET"
            applyDefaultProbeHeaders(connection, normalized, requestHeaders)
            val statusCode = runCatching { connection.responseCode }.getOrDefault(-1)
            val finalUrl = connection.url?.toString() ?: normalized
            if (statusCode in 400..599) {
                val diagnostic = pageProbeStatusDiagnostic(statusCode)
                debugRecorder.record(
                    area = DebugArea.MediaSniffing,
                    severity = if (statusCode == 403) DebugSeverity.Warning else DebugSeverity.Error,
                    action = "page-probe",
                    result = "http-blocked",
                    safeDetails = mapOf(
                        "url" to normalized,
                        "finalUrl" to finalUrl,
                        "status" to statusCode.toString(),
                        "policy" to "browser-like-bounded-get-no-js-no-drm",
                    ),
                )
                return MediaSniffingPlan(
                    candidates = emptyList(),
                    records = emptyList(),
                    variants = emptyList(),
                    diagnostics = listOf(diagnostic),
                )
            }
            val body = BufferedInputStream(connection.inputStream).use { stream ->
                stream.readBoundedUtf8(policy.bodyPrefixBytes)
            }
            val input = MediaSniffingInput(
                url = normalized,
                finalUrl = finalUrl,
                mimeType = connection.contentType,
                contentLength = connection.contentLengthLong.takeIf { it >= 0L },
                bodyPrefix = body,
                pageUrl = finalUrl,
                pageTitle = pageTitle,
                requestHeaders = requestHeaders,
                source = MediaSniffingSource.AppPageProbe,
            )
            val plan = engine.sniff(input)
            debugRecorder.record(
                area = DebugArea.MediaSniffing,
                severity = DebugSeverity.Info,
                action = "page-probe",
                result = if (plan.records.isEmpty()) "no-captures" else "captures-created",
                safeDetails = mapOf(
                    "url" to normalized,
                    "finalUrl" to finalUrl,
                    "status" to statusCode.toString(),
                    "candidateCount" to plan.candidates.size.toString(),
                    "recordCount" to plan.records.size.toString(),
                    "policy" to "browser-like-bounded-get-no-js-no-drm",
                ),
            )
            plan.copy(
                diagnostics = plan.diagnostics + listOf(
                    "page-probe browser-like bounded GET • timeout=${policy.connectTimeoutMillis}ms • prefix=${policy.bodyPrefixBytes} bytes • no-js=${!policy.executeJavaScript} • no-drm-bypass=${!policy.bypassDrm}",
                ),
            )
        } catch (error: Exception) {
            debugRecorder.record(
                area = DebugArea.MediaSniffing,
                severity = DebugSeverity.Error,
                action = "page-probe",
                result = "failed",
                safeDetails = mapOf("url" to normalized, "error" to error.javaClass.simpleName),
            )
            MediaSniffingPlan(
                candidates = emptyList(),
                records = emptyList(),
                variants = emptyList(),
                diagnostics = listOf("page-probe failed: ${error.javaClass.simpleName}"),
            )
        } finally {
            connection.disconnect()
        }
    }
}

private fun applyDefaultProbeHeaders(connection: HttpURLConnection, url: String, requestHeaders: Map<String, String>) {
    fun supplied(name: String) = requestHeaders.keys.any { it.equals(name, ignoreCase = true) }
    if (!supplied("User-Agent")) connection.setRequestProperty("User-Agent", DEFAULT_MEDIA_PROBE_USER_AGENT)
    if (!supplied("Accept")) {
        connection.setRequestProperty(
            "Accept",
            "text/html,application/xhtml+xml,application/xml;q=0.9,application/vnd.apple.mpegurl;q=0.9,application/dash+xml;q=0.9,*/*;q=0.8",
        )
    }
    if (!supplied("Accept-Language")) connection.setRequestProperty("Accept-Language", "en-US,en;q=0.8")
    if (!supplied("Accept-Encoding")) connection.setRequestProperty("Accept-Encoding", "identity")
    sameOriginReferer(url)?.takeIf { !supplied("Referer") }?.let { connection.setRequestProperty("Referer", it) }
    requestHeaders
        .filterKeys { name -> name.none { it == '\n' || it == '\n' } }
        .filterValues { value -> value.none { it == '\n' || it == '\n' } }
        .forEach { (name, value) -> connection.setRequestProperty(name, value) }
}

private fun BufferedInputStream.readBoundedUtf8(limitBytes: Int): String {
    if (limitBytes <= 0) return ""
    val output = ByteArray(limitBytes)
    var total = 0
    while (total < limitBytes) {
        val read = read(output, total, limitBytes - total)
        if (read <= 0) break
        total += read
    }
    return if (total <= 0) "" else output.decodeToString(endIndex = total)
}

private fun sameOriginReferer(url: String): String? = runCatching {
    val parsed = URI(url)
    val scheme = parsed.scheme?.lowercase(Locale.US)?.takeIf { it == "http" || it == "https" } ?: return@runCatching null
    val host = parsed.host?.takeIf { it.isNotBlank() } ?: return@runCatching null
    val port = parsed.port.takeIf { it > 0 }?.let { ":$it" }.orEmpty()
    "$scheme://$host$port/"
}.getOrNull()

private fun pageProbeStatusDiagnostic(statusCode: Int): String = when (statusCode) {
    401, 403 -> "page-probe blocked by the site (HTTP $statusCode); use the browser extension capture so cookies, referer, and the active session stay in the browser"
    404 -> "page-probe could not find the page (HTTP 404)"
    in 500..599 -> "page-probe reached the site but the server failed (HTTP $statusCode)"
    else -> "page-probe stopped at HTTP $statusCode"
}

private const val DEFAULT_MEDIA_PROBE_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

/**
 * 1DM-style shared app-side media sniffing engine for Add Download, media batch input, shares,
 * browser-extension handoff, and future GeckoView/browser engines. It is deliberately static:
 * no arbitrary JavaScript execution, no DRM bypass, redacted diagnostics only, and signed media
 * query strings are preserved on real candidates.
 */
class MediaSniffingEngine(
    private val captureService: MediaCaptureService = MediaCaptureService(),
    private val classifier: MediaCandidateClassifier = MediaCandidateClassifier(),
    private val debugRecorder: DebugEventRecorder = NoOpDebugEventRecorder,
) {
    private data class RawCandidate(
        val url: String,
        val reason: String,
        val mimeType: String? = null,
        val contentLength: Long? = null,
    )

    private val urlPattern = Regex("""https?://[^\s<>\"'`]+""", RegexOption.IGNORE_CASE)
    private val htmlAttributePattern = Regex(
        """(?:src|href|poster|data-src|data-href|data-url)\s*=\s*['\"]([^'\"]+)['\"]""",
        RegexOption.IGNORE_CASE,
    )
    private val cssUrlPattern = Regex("""url\((?:['\"]?)([^)'\"]+)(?:['\"]?)\)""", RegexOption.IGNORE_CASE)
    private val unicodeEscapePattern = Regex("""\\u([0-9a-fA-F]{4})""")

    fun sniff(input: MediaSniffingInput): MediaSniffingPlan {
        val rawCandidates = gatherRawCandidates(input)
        val seen = linkedSetOf<String>()
        val diagnostics = mutableListOf<String>()
        val candidates = mutableListOf<MediaSniffingCandidate>()

        rawCandidates.forEach { raw ->
            val normalized = normalizeHttpUrl(raw.url, input.finalUrl ?: input.pageUrl ?: input.url)
            if (normalized == null) {
                diagnostics += "ignored invalid URL from ${raw.reason}"
                return@forEach
            }
            if (isFragmentOrNoise(normalized)) {
                diagnostics += "filtered fragment/noise ${PrivacyDiagnosticsRedactor.redactUrl(normalized)}"
                return@forEach
            }
            if (!seen.add(normalized)) {
                diagnostics += "deduped ${PrivacyDiagnosticsRedactor.redactUrl(normalized)}"
                return@forEach
            }
            val facts = MediaRequestFacts(
                url = normalized,
                mimeType = raw.mimeType ?: input.mimeType,
                contentLength = raw.contentLength ?: input.contentLength,
                pageUrl = input.pageUrl ?: input.finalUrl,
                pageTitle = input.pageTitle,
                headers = input.requestHeaders,
            )
            val kind = bodySignatureKind(normalized, input.bodyPrefix) ?: classifier.classify(facts)
            if (kind == MediaSourceKind.Unknown) {
                diagnostics += "page-inspection-needed ${PrivacyDiagnosticsRedactor.redactUrl(normalized)}"
                return@forEach
            }
            val rank = rankFor(kind, normalized, raw.reason, raw.contentLength ?: input.contentLength)
            candidates += MediaSniffingCandidate(
                url = normalized,
                kind = kind,
                rank = rank,
                reason = reasonFor(kind, raw.reason, input.mimeType, input.bodyPrefix),
                mimeType = raw.mimeType ?: input.mimeType ?: mimeFor(kind),
                pageUrl = input.pageUrl ?: input.finalUrl,
                title = input.pageTitle,
            )
        }

        val ranked = candidates
            .sortedWith(compareByDescending<MediaSniffingCandidate> { it.rank }.thenBy { it.url })
            .distinctBy(MediaSniffingCandidate::url)
        val captureCandidates = ranked.mapNotNull { candidate ->
            captureService.candidateFor(
                url = candidate.url,
                pageTitle = candidate.title,
                pageUrl = candidate.pageUrl,
                mimeTypeHint = candidate.mimeType,
                contentLength = input.contentLength,
                headers = input.requestHeaders,
                durationMs = input.durationMs,
                thumbnailUrl = input.thumbnailUrl,
            )
        }
        val records = captureService.recordsFor(captureCandidates)
        val manifestVariants = captureCandidates.flatMap { candidate ->
            parseInlineManifestVariants(candidate, input.bodyPrefix).ifEmpty { candidate.variants }
        }
        val variants = manifestVariants.distinctBy(MediaVariant::id)
        val manifestDiagnostics = captureCandidates.mapNotNull { candidate ->
            val parsedCount = parseInlineManifestVariants(candidate, input.bodyPrefix).size
            parsedCount.takeIf { it > candidate.variants.size }?.let { "manifest-resolved-inline ${candidate.kind.name} variants=$it" }
        }
        val safeDiagnostics = diagnostics + manifestDiagnostics + diagnosticSummary(input, ranked)
        recordDebugSniff(input, ranked, records, variants, safeDiagnostics)
        return MediaSniffingPlan(
            candidates = ranked,
            records = records,
            variants = variants,
            diagnostics = safeDiagnostics.mapNotNull(PrivacyDiagnosticsRedactor::redactText).distinct(),
        )
    }



    private fun parseInlineManifestVariants(candidate: MediaCaptureCandidate, bodyPrefix: String?): List<MediaVariant> {
        val body = bodyPrefix?.trimStart()?.takeIf(String::isNotBlank) ?: return emptyList()
        val captureId = MediaCaptureService.captureIdFor(candidate.sourceUrl)
        return when (candidate.kind) {
            MediaSourceKind.HlsPlaylist -> if (body.startsWith("#EXTM3U", ignoreCase = true)) {
                val parsed = captureService.parseHlsPlaylist(captureId, candidate.sourceUrl, body)
                parsed.ifEmpty { candidate.variants }
            } else {
                emptyList()
            }
            MediaSourceKind.DashManifest -> if (body.contains("<MPD", ignoreCase = true)) {
                val parsed = captureService.parseDashManifest(captureId, candidate.sourceUrl, body)
                parsed.ifEmpty { candidate.variants }
            } else {
                emptyList()
            }
            else -> emptyList()
        }
    }

    private fun recordDebugSniff(
        input: MediaSniffingInput,
        candidates: List<MediaSniffingCandidate>,
        records: List<MediaCaptureRecord>,
        variants: List<MediaVariant>,
        diagnostics: List<String>,
    ) {
        debugRecorder.record(
            area = DebugArea.MediaSniffing,
            severity = DebugSeverity.Info,
            action = "shared-sniff",
            result = if (records.isEmpty()) "no-captures" else "captures-created",
            safeDetails = mapOf(
                "source" to input.source.humanLabel(),
                "url" to PrivacyDiagnosticsRedactor.redactUrl(input.url).orEmpty(),
                "pageUrl" to PrivacyDiagnosticsRedactor.redactUrl(input.pageUrl ?: input.finalUrl).orEmpty(),
                "mimeType" to input.mimeType.orEmpty(),
                "candidateCount" to candidates.size.toString(),
                "recordCount" to records.size.toString(),
                "variantCount" to variants.size.toString(),
                "diagnosticCount" to diagnostics.size.toString(),
                "policy" to "static-no-js-no-drm",
            ),
        )
    }

    private fun gatherRawCandidates(input: MediaSniffingInput): List<RawCandidate> {
        val raw = mutableListOf<RawCandidate>()
        listOfNotNull(input.url, input.finalUrl).forEach { raw += RawCandidate(decodeEscapedUrl(it), "direct-url", input.mimeType, input.contentLength) }
        input.bodyPrefix?.let { body ->
            val decoded = decodeEscapedUrl(body)
            if (bodySignatureKind(input.finalUrl ?: input.url.orEmpty(), decoded) != null) {
                listOfNotNull(input.finalUrl, input.url).firstOrNull()?.let { raw += RawCandidate(it, "body-signature", input.mimeType, input.contentLength) }
            }
            urlPattern.findAll(decoded).forEach { match -> raw += RawCandidate(match.value.cleanExtractedUrl(), "json-or-script-url") }
            htmlAttributePattern.findAll(decoded).forEach { match -> raw += RawCandidate(match.groupValues[1].cleanExtractedUrl(), "html-attribute") }
            cssUrlPattern.findAll(decoded).forEach { match -> raw += RawCandidate(match.groupValues[1].cleanExtractedUrl(), "css-url") }
        }
        return raw
    }

    private fun bodySignatureKind(url: String, bodyPrefix: String?): MediaSourceKind? {
        val prefix = bodyPrefix?.trimStart()?.take(4096) ?: return null
        val lowerUrl = url.lowercase(Locale.US)
        return when {
            prefix.startsWith("#EXTM3U", ignoreCase = true) -> MediaSourceKind.HlsPlaylist
            prefix.contains("<MPD", ignoreCase = true) -> MediaSourceKind.DashManifest
            lowerUrl.contains(".m3u8") && prefix.contains("#EXT-X-", ignoreCase = true) -> MediaSourceKind.HlsPlaylist
            lowerUrl.contains(".mpd") && prefix.contains("urn:mpeg:dash", ignoreCase = true) -> MediaSourceKind.DashManifest
            else -> null
        }
    }

    private fun normalizeHttpUrl(value: String, baseUrl: String?): String? {
        val decoded = decodeEscapedUrl(value).cleanExtractedUrl()
        val resolved = runCatching {
            val uri = URI(decoded)
            if (uri.isAbsolute) uri else baseUrl?.let { URI(it).resolve(uri) }
        }.getOrNull() ?: return null
        val scheme = resolved.scheme?.lowercase(Locale.US) ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = resolved.host?.lowercase(Locale.US) ?: return null
        return URI(
            scheme,
            resolved.userInfo,
            host,
            resolved.port,
            resolved.rawPath?.ifBlank { "/" } ?: "/",
            resolved.rawQuery,
            resolved.rawFragment,
        ).toASCIIString().cleanExtractedUrl()
    }

    private fun decodeEscapedUrl(value: String): String {
        val slashDecoded = value.replace("\\/", "/")
        return unicodeEscapePattern.replace(slashDecoded) { match ->
            match.groupValues[1].toInt(16).toChar().toString()
        }
    }

    private fun isFragmentOrNoise(url: String): Boolean {
        val lower = url.lowercase(Locale.US)
        val path = runCatching { URI(url).path.orEmpty().lowercase(Locale.US) }.getOrDefault(lower)
        if (path.endsWith(".ts") || path.endsWith(".m4s") || path.endsWith("/init.mp4")) return true
        if (path.contains("/segment") || path.contains("/seg-") || path.contains("/chunk-")) return true
        return listOf("doubleclick", "googlesyndication", "google-analytics", "/ads/", "adserver", "tracking", "pixel").any { lower.contains(it) }
    }

    private fun rankFor(kind: MediaSourceKind, url: String, reason: String, contentLength: Long?): Int {
        var rank = when (kind) {
            MediaSourceKind.HlsPlaylist, MediaSourceKind.DashManifest -> 120
            MediaSourceKind.ProgressiveMedia, MediaSourceKind.VideoStream -> 90
            MediaSourceKind.AudioStream -> 75
            MediaSourceKind.DirectFile -> 70
            MediaSourceKind.Unknown -> 0
        }
        if (reason == "body-signature") rank += 12
        if (reason == "html-attribute") rank += 4
        if (url.contains("preview", ignoreCase = true) || url.contains("thumbnail", ignoreCase = true)) rank -= 25
        if ((contentLength ?: Long.MAX_VALUE) in 1L..262_143L) rank -= 20
        return rank.coerceIn(0, 150)
    }

    private fun reasonFor(kind: MediaSourceKind, rawReason: String, mimeType: String?, bodyPrefix: String?): String = buildList {
        add(rawReason)
        if (!mimeType.isNullOrBlank()) add("mime")
        when (kind) {
            MediaSourceKind.HlsPlaylist -> add(if (bodyPrefix?.contains("#EXTM3U", ignoreCase = true) == true) "hls-body" else "hls-manifest")
            MediaSourceKind.DashManifest -> add(if (bodyPrefix?.contains("<MPD", ignoreCase = true) == true) "dash-body" else "dash-manifest")
            MediaSourceKind.ProgressiveMedia, MediaSourceKind.VideoStream, MediaSourceKind.AudioStream, MediaSourceKind.DirectFile -> add("media-file")
            MediaSourceKind.Unknown -> Unit
        }
    }.distinct().joinToString("+")

    private fun mimeFor(kind: MediaSourceKind): String? = when (kind) {
        MediaSourceKind.HlsPlaylist -> "application/vnd.apple.mpegurl"
        MediaSourceKind.DashManifest -> "application/dash+xml"
        MediaSourceKind.AudioStream -> "audio/mpeg"
        MediaSourceKind.ProgressiveMedia, MediaSourceKind.VideoStream, MediaSourceKind.DirectFile -> "video/mp4"
        MediaSourceKind.Unknown -> null
    }

    private fun MediaSniffingSource.humanLabel(): String = when (this) {
        MediaSniffingSource.ManualPage -> "Manual page"
        MediaSniffingSource.BatchInput -> "Batch input"
        MediaSniffingSource.SharedText -> "Shared text"
        MediaSniffingSource.BrowserExtension -> "Browser extension"
        MediaSniffingSource.NetworkObservation -> "Network observation"
        MediaSniffingSource.AppPageProbe -> "App page probe"
    }

    private fun diagnosticSummary(input: MediaSniffingInput, candidates: List<MediaSniffingCandidate>): String = listOfNotNull(
        "shared app-side media sniffing engine",
        "source=${input.source.humanLabel()}",
        "url=${PrivacyDiagnosticsRedactor.redactUrl(input.url)}",
        "page=${PrivacyDiagnosticsRedactor.redactUrl(input.pageUrl ?: input.finalUrl)}",
        "candidates=${candidates.size}",
        "no arbitrary JavaScript execution",
        "no DRM bypass",
        "signed media query strings preserved",
    ).joinToString(" • ")

    private fun String.cleanExtractedUrl(): String = trim()
        .trimEnd(')', ']', '}', '>', ',', '.', ';', ':')
        .trimEnd('"', '\'', '`')
}
