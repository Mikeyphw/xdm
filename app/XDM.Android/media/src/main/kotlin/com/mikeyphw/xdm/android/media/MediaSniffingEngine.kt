package com.mikeyphw.xdm.android.media

import com.mikeyphw.xdm.android.model.DebugArea
import com.mikeyphw.xdm.android.model.DebugEventRecorder
import com.mikeyphw.xdm.android.model.DebugSeverity
import com.mikeyphw.xdm.android.model.MediaCaptureRecord
import com.mikeyphw.xdm.android.model.NoOpDebugEventRecorder
import com.mikeyphw.xdm.android.model.MediaSourceKind
import com.mikeyphw.xdm.android.model.MediaVariant
import com.mikeyphw.xdm.android.model.PrivacyDiagnosticsRedactor
import com.mikeyphw.xdm.android.transfer.DownloadRequest
import com.mikeyphw.xdm.android.transfer.DownloadRequestKind
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

/** Bounded page fetcher for manual page URLs. It reads only a prefix and never executes JS.
 * Every network hop is validated by the same transfer request-security boundary used by downloads. */
class MediaPageProbe(
    private val engine: MediaSniffingEngine = MediaSniffingEngine(),
    private val policy: MediaPageProbePolicy = MediaPageProbePolicy(),
    private val debugRecorder: DebugEventRecorder = NoOpDebugEventRecorder,
    private val securityValidator: suspend (DownloadRequest) -> Unit = {},
) {
    suspend fun probePage(
        url: String,
        pageTitle: String? = null,
        requestHeaders: Map<String, String> = emptyMap(),
        privateNetworkApproved: Boolean = false,
        cleartextCredentialsApproved: Boolean = false,
        privateNetworkApprovalScopes: Set<String> = emptySet(),
        cleartextCredentialApprovalScopes: Set<String> = emptySet(),
    ): MediaSniffingPlan {
        val normalized = normalizeProbeUrl(url) ?: return rejectedPlan(url, "unsafe-or-unsupported-url")
        val sanitizedHeaders = sanitizeProbeHeaders(requestHeaders)
        var currentUrl = normalized
        var redirects = 0
        val originalOrigin = originOf(normalized)
        while (true) {
            val hopHeaders = if (originOf(currentUrl) == originalOrigin) sanitizedHeaders else sanitizedHeaders.filterKeys { !isSensitiveProbeHeader(it) }
            try {
                securityValidator(
                    DownloadRequest(
                        id = "media-page-probe",
                        sourceUrl = currentUrl,
                        destinationUri = "app-private://media-page-probe",
                        fileName = "media-page-probe.html",
                        requestKind = DownloadRequestKind.Direct,
                        headers = hopHeaders,
                        privateNetworkApproved = privateNetworkApproved,
                        cleartextCredentialsApproved = cleartextCredentialsApproved,
                        privateNetworkApprovalScopes = privateNetworkApprovalScopes,
                        cleartextCredentialApprovalScopes = cleartextCredentialApprovalScopes,
                    ),
                )
            } catch (error: Exception) {
                debugRecorder.record(
                    area = DebugArea.MediaSniffing,
                    severity = DebugSeverity.Warning,
                    action = "page-probe",
                    result = "security-rejected",
                    safeDetails = mapOf("url" to currentUrl, "error" to error.javaClass.simpleName),
                )
                return MediaSniffingPlan(emptyList(), emptyList(), emptyList(), listOf("page-probe rejected by transfer request security: ${error.message ?: error.javaClass.simpleName}"))
            }

            val uri = URI(currentUrl)
            val connection = runCatching { uri.toURL().openConnection() as HttpURLConnection }.getOrElse { error ->
                return failedPlan(currentUrl, "open failed: ${error.javaClass.simpleName}")
            }
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = policy.connectTimeoutMillis
                connection.readTimeout = policy.readTimeoutMillis
                connection.requestMethod = "GET"
                applyDefaultProbeHeaders(connection, currentUrl, hopHeaders)
                val statusCode = connection.responseCode
                if (statusCode in REDIRECT_STATUS_CODES && policy.followRedirects) {
                    if (++redirects > MAX_PROBE_REDIRECTS) return failedPlan(currentUrl, "redirect limit exceeded")
                    val location = connection.getHeaderField("Location")?.trim().orEmpty()
                    val next = resolveProbeRedirect(currentUrl, location) ?: return rejectedPlan(currentUrl, "unsafe-redirect")
                    currentUrl = next
                    continue
                }
                if (statusCode in 400..599) {
                    val diagnostic = pageProbeStatusDiagnostic(statusCode)
                    debugRecorder.record(
                        area = DebugArea.MediaSniffing,
                        severity = if (statusCode == 403) DebugSeverity.Warning else DebugSeverity.Error,
                        action = "page-probe",
                        result = "http-blocked",
                        safeDetails = mapOf("url" to normalized, "finalUrl" to currentUrl, "status" to statusCode.toString()),
                    )
                    return MediaSniffingPlan(emptyList(), emptyList(), emptyList(), listOf(diagnostic))
                }
                val body = BufferedInputStream(connection.inputStream).use { stream -> stream.readBoundedUtf8(policy.bodyPrefixBytes) }
                val input = MediaSniffingInput(
                    url = normalized,
                    finalUrl = currentUrl,
                    mimeType = connection.contentType,
                    contentLength = connection.contentLengthLong.takeIf { it >= 0L },
                    bodyPrefix = body,
                    pageUrl = currentUrl,
                    pageTitle = pageTitle,
                    requestHeaders = hopHeaders,
                    source = MediaSniffingSource.AppPageProbe,
                )
                val plan = engine.sniff(input)
                debugRecorder.record(
                    area = DebugArea.MediaSniffing,
                    severity = DebugSeverity.Info,
                    action = "page-probe",
                    result = if (plan.records.isEmpty()) "no-captures" else "captures-created",
                    safeDetails = mapOf("url" to normalized, "finalUrl" to currentUrl, "status" to statusCode.toString(), "candidateCount" to plan.candidates.size.toString()),
                )
                return plan.copy(diagnostics = plan.diagnostics + "page-probe bounded GET • redirects=$redirects/$MAX_PROBE_REDIRECTS • each-hop-security=true • no-js=${!policy.executeJavaScript} • no-drm-bypass=${!policy.bypassDrm}")
            } catch (error: Exception) {
                return failedPlan(currentUrl, "failed: ${error.javaClass.simpleName}")
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun rejectedPlan(url: String, reason: String): MediaSniffingPlan {
        debugRecorder.record(DebugArea.MediaSniffing, DebugSeverity.Warning, "page-probe", "rejected", mapOf("url" to url, "reason" to reason))
        return MediaSniffingPlan(emptyList(), emptyList(), emptyList(), listOf("page-probe rejected: $reason"))
    }

    private fun failedPlan(url: String, reason: String): MediaSniffingPlan {
        debugRecorder.record(DebugArea.MediaSniffing, DebugSeverity.Error, "page-probe", "failed", mapOf("url" to url, "reason" to reason))
        return MediaSniffingPlan(emptyList(), emptyList(), emptyList(), listOf("page-probe $reason"))
    }
}

private val SAFE_PROBE_HEADER_NAME = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]{1,128}$")
private val PROBE_HEADER_ALLOWLIST = setOf(
    "accept", "accept-encoding", "accept-language", "authorization", "cookie", "origin", "referer", "range", "user-agent",
    "if-range", "if-none-match", "if-modified-since", "x-api-key", "x-auth-token", "x-access-token", "x-csrf-token",
)
private val REDIRECT_STATUS_CODES = setOf(301, 302, 303, 307, 308)
private const val MAX_PROBE_REDIRECTS = 5

private fun sanitizeProbeHeaders(headers: Map<String, String>): Map<String, String> = headers.entries.mapNotNull { (rawName, rawValue) ->
    val name = rawName.trim()
    val value = rawValue.trim()
    val lower = name.lowercase(Locale.US)
    if (!SAFE_PROBE_HEADER_NAME.matches(name) || '\r' in value || '\n' in value || lower !in PROBE_HEADER_ALLOWLIST && !lower.startsWith("sec-fetch-")) null else name to value
}.toMap()

private fun isSensitiveProbeHeader(name: String): Boolean {
    val lower = name.lowercase(Locale.US)
    return lower in setOf("authorization", "cookie", "x-api-key", "x-auth-token", "x-access-token", "x-csrf-token") || lower.contains("token") || lower.endsWith("-key")
}

private fun normalizeProbeUrl(value: String): String? = runCatching {
    val uri = URI(value.trim())
    val scheme = uri.scheme?.lowercase(Locale.US)
    if (scheme !in setOf("http", "https") || uri.host.isNullOrBlank() || uri.rawUserInfo != null || uri.rawFragment != null) return@runCatching null
    URI(scheme, null, uri.host.lowercase(Locale.US), uri.port, uri.rawPath?.ifBlank { "/" } ?: "/", uri.rawQuery, null).toASCIIString()
}.getOrNull()

private fun resolveProbeRedirect(base: String, location: String): String? = runCatching { URI(base).resolve(location) }.getOrNull()?.toString()?.let(::normalizeProbeUrl)
private fun originOf(value: String): String? = runCatching { URI(value) }.getOrNull()?.let { uri -> "${uri.scheme?.lowercase(Locale.US)}://${uri.host?.lowercase(Locale.US)}:${if (uri.port >= 0) uri.port else if (uri.scheme.equals("https", true)) 443 else 80}" }

private fun applyDefaultProbeHeaders(connection: HttpURLConnection, url: String, requestHeaders: Map<String, String>) {
    fun supplied(name: String) = requestHeaders.keys.any { it.equals(name, ignoreCase = true) }
    if (!supplied("User-Agent")) connection.setRequestProperty("User-Agent", DEFAULT_MEDIA_PROBE_USER_AGENT)
    if (!supplied("Accept")) connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,application/vnd.apple.mpegurl;q=0.9,application/dash+xml;q=0.9,*/*;q=0.8")
    if (!supplied("Accept-Language")) connection.setRequestProperty("Accept-Language", "en-US,en;q=0.8")
    if (!supplied("Accept-Encoding")) connection.setRequestProperty("Accept-Encoding", "identity")
    sameOriginReferer(url)?.takeIf { !supplied("Referer") }?.let { connection.setRequestProperty("Referer", it) }
    sanitizeProbeHeaders(requestHeaders).forEach { (name, value) -> connection.setRequestProperty(name, value) }
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
    private val structuredMediaValuePattern = Regex(
        """["'](?:manifest|playlist|hls|dash|m3u8|mpd|file|video|audio|media|stream|mp4)(?:Url|URL|_url|_src|url|src)?["']\s*[:=]\s*["']([^"'\r\n]{1,2200})["']""",
        RegexOption.IGNORE_CASE,
    )
    private val mediaTagPattern = Regex(
        """<(?:video|audio|source)\b[^>]*?\bsrc\s*=\s*['"]([^'"]+)['"]""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
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
            val responseBound = raw.reason == "direct-url" || raw.reason == "body-signature"
            val effectiveMime = raw.mimeType ?: input.mimeType.takeIf { responseBound }
            val effectiveLength = raw.contentLength ?: input.contentLength.takeIf { responseBound }
            val facts = MediaRequestFacts(
                url = normalized,
                mimeType = effectiveMime,
                contentLength = effectiveLength,
                pageUrl = input.pageUrl ?: input.finalUrl,
                pageTitle = input.pageTitle,
                headers = input.requestHeaders,
            )
            val kind = if (raw.reason == "body-signature") bodySignatureKind(normalized, input.bodyPrefix) else null
                ?: classifier.classify(facts)
            if (kind == MediaSourceKind.Unknown) {
                diagnostics += "page-inspection-needed ${PrivacyDiagnosticsRedactor.redactUrl(normalized)}"
                return@forEach
            }
            val rank = rankFor(kind, normalized, raw.reason, effectiveLength)
            candidates += MediaSniffingCandidate(
                url = normalized,
                kind = kind,
                rank = rank,
                reason = reasonFor(kind, raw.reason, effectiveMime, input.bodyPrefix),
                mimeType = effectiveMime ?: mimeFor(kind),
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
            structuredMediaValuePattern.findAll(decoded).forEach { match ->
                raw += RawCandidate(match.groupValues[1].cleanExtractedUrl(), "structured-media-key")
            }
            mediaTagPattern.findAll(decoded).forEach { match ->
                raw += RawCandidate(match.groupValues[1].cleanExtractedUrl(), "media-dom-source")
            }
            if (input.source in setOf(MediaSniffingSource.BatchInput, MediaSniffingSource.SharedText, MediaSniffingSource.ManualPage)) {
                urlPattern.findAll(decoded).forEach { match -> raw += RawCandidate(match.value.cleanExtractedUrl(), "user-supplied-url") }
            }
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
        if (resolved.rawUserInfo != null) return null
        return URI(
            scheme,
            null,
            host,
            resolved.port,
            resolved.rawPath?.ifBlank { "/" } ?: "/",
            resolved.rawQuery,
            null,
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
        if (reason == "media-dom-source") rank += 12
        if (reason == "structured-media-key") rank += 8
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
