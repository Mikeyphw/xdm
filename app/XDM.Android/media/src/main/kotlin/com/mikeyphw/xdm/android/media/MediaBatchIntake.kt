package com.mikeyphw.xdm.android.media

import com.mikeyphw.xdm.android.model.DebugArea
import com.mikeyphw.xdm.android.model.DebugEventRecorder
import com.mikeyphw.xdm.android.model.MediaCaptureRecord
import com.mikeyphw.xdm.android.model.NoOpDebugEventRecorder
import com.mikeyphw.xdm.android.model.MediaSourceKind
import com.mikeyphw.xdm.android.model.MediaVariant
import java.net.URI
import java.util.Locale

/** Classification of a normalized URL discovered in pasted batch text. */
enum class MediaBatchUrlDisposition { MediaReady, NeedsPageInspection }

data class MediaBatchAcceptedUrl(
    val normalizedUrl: String,
    val sourceLine: Int,
    val disposition: MediaBatchUrlDisposition,
    val kind: MediaSourceKind,
) {
    val needsPageInspection: Boolean get() = disposition == MediaBatchUrlDisposition.NeedsPageInspection
}

data class MediaBatchRejectedLine(
    val lineNumber: Int,
    val text: String,
    val reason: String,
)

data class MediaBatchParseResult(
    val accepted: List<MediaBatchAcceptedUrl>,
    val duplicates: List<String>,
    val rejected: List<MediaBatchRejectedLine>,
    val inputTruncated: Boolean,
    val maxInputChars: Int,
) {
    val acceptedCount: Int get() = accepted.size
    val duplicateCount: Int get() = duplicates.size
    val invalidCount: Int get() = rejected.size
    val mediaReadyCount: Int get() = accepted.count { it.disposition == MediaBatchUrlDisposition.MediaReady }
    val pageInspectionCount: Int get() = accepted.count(MediaBatchAcceptedUrl::needsPageInspection)
    val summaryLabel: String
        get() = listOf(
            "$acceptedCount accepted",
            "$duplicateCount duplicates",
            "$invalidCount invalid",
            "$pageInspectionCount need page inspection",
        ).joinToString(" • ")

    val rejectedLinesText: String
        get() = rejected.joinToString("\n") { "${it.lineNumber}: ${it.text}" }
}

data class MediaBatchIntakePlan(
    val parse: MediaBatchParseResult,
    val records: List<MediaCaptureRecord>,
    val variants: List<MediaVariant>,
    val sniffingCandidates: List<MediaSniffingCandidate> = emptyList(),
    val sniffingDiagnostics: List<String> = emptyList(),
) {
    val hasMediaReady: Boolean get() = records.isNotEmpty()
    val summaryLabel: String get() = parse.summaryLabel
}

/**
 * Review-first parser for pasted media batches.
 *
 * The parser intentionally does not execute JavaScript, does not fetch network resources, and does
 * not copy credentials. It extracts HTTP(S) URLs from one-per-line input or pasted HTML/JSON/text,
 * deduplicates by normalized URL while preserving signed query strings, and labels watch/page URLs
 * for a later page-inspection phase instead of fabricating media records.
 */
class MediaBatchInputParser(
    private val classifier: MediaCandidateClassifier = MediaCandidateClassifier(),
    private val maxInputChars: Int = 256 * 1024,
    private val maxUrls: Int = 200,
) {
    private val urlPattern = Regex("""https?://[^\s<>\"'`]+""", RegexOption.IGNORE_CASE)
    private val explicitSchemePattern = Regex("""^[A-Za-z][A-Za-z0-9+.-]*:""")

    fun parse(input: String): MediaBatchParseResult {
        val truncated = input.length > maxInputChars
        val text = input.take(maxInputChars)
        val seen = linkedSetOf<String>()
        val accepted = mutableListOf<MediaBatchAcceptedUrl>()
        val duplicates = mutableListOf<String>()
        val rejected = mutableListOf<MediaBatchRejectedLine>()
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').lines()

        lines.forEachIndexed { index, rawLine ->
            val lineNumber = index + 1
            val line = rawLine.trim()
            if (line.isBlank()) return@forEachIndexed
            val urls = urlPattern.findAll(line)
                .map { it.value.cleanExtractedUrl() }
                .filter(String::isNotBlank)
                .toList()
            if (urls.isEmpty()) {
                if (explicitSchemePattern.containsMatchIn(line)) {
                    rejected += MediaBatchRejectedLine(lineNumber, line, "Only HTTP and HTTPS media links are supported")
                } else {
                    rejected += MediaBatchRejectedLine(lineNumber, line, "No supported URL found")
                }
                return@forEachIndexed
            }
            urls.forEach { url ->
                if (accepted.size >= maxUrls) {
                    rejected += MediaBatchRejectedLine(lineNumber, url, "Batch limit reached at $maxUrls URLs")
                    return@forEach
                }
                val normalized = normalizeUrl(url) ?: run {
                    rejected += MediaBatchRejectedLine(lineNumber, url, "Invalid HTTP(S) URL")
                    return@forEach
                }
                if (!seen.add(normalized)) {
                    duplicates += normalized
                    return@forEach
                }
                val kind = classifier.classify(MediaRequestFacts(normalized))
                accepted += MediaBatchAcceptedUrl(
                    normalizedUrl = normalized,
                    sourceLine = lineNumber,
                    disposition = if (kind == MediaSourceKind.Unknown) {
                        MediaBatchUrlDisposition.NeedsPageInspection
                    } else {
                        MediaBatchUrlDisposition.MediaReady
                    },
                    kind = kind,
                )
            }
        }

        return MediaBatchParseResult(
            accepted = accepted,
            duplicates = duplicates,
            rejected = rejected,
            inputTruncated = truncated,
            maxInputChars = maxInputChars,
        )
    }

    private fun normalizeUrl(value: String): String? {
        val cleaned = value.cleanExtractedUrl()
        val uri = runCatching { URI(cleaned) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        val rebuilt = URI(
            scheme,
            uri.userInfo,
            host,
            uri.port,
            uri.rawPath?.ifBlank { "/" } ?: "/",
            uri.rawQuery,
            uri.rawFragment,
        ).toASCIIString()
        return rebuilt.cleanExtractedUrl()
    }

    private fun String.cleanExtractedUrl(): String = trim()
        .trimEnd(')', ']', '}', '>', ',', '.', ';', ':')
        .trimEnd('"', '\'', '`')
}

class MediaBatchIntakePlanner(
    private val captureService: MediaCaptureService = MediaCaptureService(),
    private val parser: MediaBatchInputParser = MediaBatchInputParser(),
    private val sniffingEngine: MediaSniffingEngine = MediaSniffingEngine(captureService),
    private val debugRecorder: DebugEventRecorder = NoOpDebugEventRecorder,
) {
    fun plan(input: String, pageTitle: String? = null, pageUrl: String? = null): MediaBatchIntakePlan {
        val parse = parser.parse(input)
        val sniffingPlan = sniffingEngine.sniff(
            MediaSniffingInput(
                url = pageUrl,
                bodyPrefix = input,
                pageUrl = pageUrl,
                pageTitle = pageTitle,
                source = MediaSniffingSource.BatchInput,
            ),
        )
        debugRecorder.record(
            area = DebugArea.MediaSniffing,
            action = "batch-intake",
            result = if (sniffingPlan.records.isEmpty()) "review-only" else "captures-created",
            safeDetails = mapOf(
                "acceptedCount" to parse.acceptedCount.toString(),
                "duplicateCount" to parse.duplicateCount.toString(),
                "invalidCount" to parse.invalidCount.toString(),
                "pageInspectionCount" to parse.pageInspectionCount.toString(),
                "recordCount" to sniffingPlan.records.size.toString(),
            ),
        )
        return MediaBatchIntakePlan(
            parse = parse,
            records = sniffingPlan.records,
            variants = sniffingPlan.variants,
            sniffingCandidates = sniffingPlan.candidates,
            sniffingDiagnostics = sniffingPlan.diagnostics,
        )
    }
}
