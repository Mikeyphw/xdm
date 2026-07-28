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
            requestHeaders
                .filterKeys { !PrivacyDiagnosticsRedactor.isSensitiveHeaderName(it) }
                .forEach { (name, value) -> connection.setRequestProperty(name, value) }
            val body = BufferedInputStream(connection.inputStream).use { stream ->
                val buffer = ByteArray(policy.bodyPrefixBytes)
                val read = stream.read(buffer)
                if (read <= 0) "" else buffer.decodeToString(endIndex = read)
            }
            val finalUrl = connection.url?.toString() ?: normalized
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
                    "candidateCount" to plan.candidates.size.toString(),
                    "recordCount" to plan.records.size.toString(),
                    "policy" to "bounded-get-no-js-no-drm",
                ),
            )
            plan.copy(
                diagnostics = plan.diagnostics + listOf(
                    "page-probe bounded GET • timeout=${policy.connectTimeoutMillis}ms • prefix=${policy.bodyPrefixBytes} bytes • no-js=${!policy.executeJavaScript} • no-drm-bypass=${!policy.bypassDrm}",
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
            )
        }
        val records = captureService.recordsFor(captureCandidates)
        val variants = captureCandidates.flatMap(MediaCaptureCandidate::variants).distinctBy(MediaVariant::id)
        val safeDiagnostics = diagnostics + diagnosticSummary(input, ranked)
        recordDebugSniff(input, ranked, records, variants, safeDiagnostics)
        return MediaSniffingPlan(
            candidates = ranked,
            records = records,
            variants = variants,
            diagnostics = safeDiagnostics.mapNotNull(PrivacyDiagnosticsRedactor::redactText).distinct(),
        )
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
                "source" to input.source.name,
                "url" to input.url.orEmpty(),
                "pageUrl" to (input.pageUrl ?: input.finalUrl).orEmpty(),
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

    private fun diagnosticSummary(input: MediaSniffingInput, candidates: List<MediaSniffingCandidate>): String = listOfNotNull(
        "shared app-side media sniffing engine",
        "source=${input.source.name}",
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
