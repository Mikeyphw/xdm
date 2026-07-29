package com.mikeyphw.xdm.android.media

import com.mikeyphw.xdm.android.model.MediaSourceKind
import com.mikeyphw.xdm.android.model.PrivacyDiagnosticsRedactor
import java.net.URI
import java.util.Locale

/** D3 static lab wrapper around the shared MediaSniffingEngine. It never fetches pages or starts downloads. */
data class MediaSniffingLabRequest(
    val rawInput: String,
    val baseUrl: String? = null,
    val mimeTypeHint: String? = null,
    val source: MediaSniffingSource = MediaSniffingSource.SharedText,
)

data class MediaSniffingLabCandidateRow(
    val redactedUrl: String,
    val kindLabel: String,
    val rank: Int,
    val reason: String,
    val mimeType: String?,
)

data class MediaSniffingLabReport(
    val statusLabel: String,
    val summary: String,
    val primaryCandidateLabel: String,
    val candidateRows: List<MediaSniffingLabCandidateRow>,
    val diagnostics: List<String>,
    val copyText: String,
) {
    val hasCandidates: Boolean get() = candidateRows.isNotEmpty()
}

object MediaSniffingLab {
    val allowedSources: List<MediaSniffingSource> = listOf(
        MediaSniffingSource.ManualPage,
        MediaSniffingSource.BatchInput,
        MediaSniffingSource.SharedText,
        MediaSniffingSource.BrowserExtension,
    )

    fun inspect(
        request: MediaSniffingLabRequest,
        engine: MediaSniffingEngine = MediaSniffingEngine(),
    ): MediaSniffingLabReport {
        val rawInput = request.rawInput.trim()
        if (rawInput.isBlank()) {
            return MediaSniffingLabReport(
                statusLabel = "Waiting for input",
                summary = "Paste a URL, HTML, JSON, or script snippet to run the shared static sniffer.",
                primaryCandidateLabel = "No candidate yet",
                candidateRows = emptyList(),
                diagnostics = listOf("static lab idle", safePolicyLine),
                copyText = buildString {
                    appendLine("XDM Media Sniffing Lab")
                    appendLine("Status: Waiting for input")
                    appendLine(copyPolicyLine)
                }.trimEnd(),
            )
        }

        val trimmedBaseUrl = request.baseUrl?.trim()?.takeIf { it.isNotBlank() }
        val sourceUrl = directHttpUrl(rawInput) ?: firstHttpUrl(rawInput) ?: trimmedBaseUrl
        val plan = engine.sniff(
            MediaSniffingInput(
                url = sourceUrl,
                pageUrl = trimmedBaseUrl,
                mimeType = request.mimeTypeHint?.trim()?.takeIf { it.isNotBlank() },
                bodyPrefix = rawInput,
                source = request.source,
            ),
        )
        val rows = plan.candidates.map { candidate ->
            MediaSniffingLabCandidateRow(
                redactedUrl = PrivacyDiagnosticsRedactor.redactUrl(candidate.url) ?: candidate.url,
                kindLabel = candidate.kind.debugLabel(),
                rank = candidate.rank,
                reason = candidate.reason,
                mimeType = candidate.mimeType,
            )
        }
        val primary = rows.firstOrNull()?.let { row ->
            "${row.kindLabel} • rank ${row.rank}"
        } ?: "No supported media candidate"
        val status = if (rows.isEmpty()) "No candidates" else "${rows.size} candidate(s)"
        val diagnostics = buildList {
            add("Media Sniffing Lab uses shared MediaSniffingEngine")
            add("source=${request.source.name}")
            add("input=${inputShape(rawInput)}")
            trimmedBaseUrl?.let { add("base=${PrivacyDiagnosticsRedactor.redactUrl(it)}") }
            addAll(plan.diagnostics)
            add(safePolicyLine)
        }.mapNotNull(PrivacyDiagnosticsRedactor::redactText).distinct()
        return MediaSniffingLabReport(
            statusLabel = status,
            summary = "Static sniff only • ${plan.candidates.size} candidates • ${plan.records.size} captures • ${plan.variants.size} variants",
            primaryCandidateLabel = primary,
            candidateRows = rows,
            diagnostics = diagnostics,
            copyText = buildCopyText(status, primary, rows, diagnostics),
        )
    }

    private const val safePolicyLine = "No network page probe, no arbitrary JavaScript execution, no DRM bypass, redacted diagnostics only"
    private const val copyPolicyLine = "Policy: no network page probe, no arbitrary JavaScript execution, no DRM bypass, redacted diagnostics only"

    private fun buildCopyText(
        status: String,
        primary: String,
        rows: List<MediaSniffingLabCandidateRow>,
        diagnostics: List<String>,
    ): String = buildString {
        appendLine("XDM Media Sniffing Lab")
        appendLine("Status: $status")
        appendLine("Primary: $primary")
        appendLine(copyPolicyLine)
        appendLine("Candidates:")
        if (rows.isEmpty()) appendLine("- none")
        rows.forEachIndexed { index, row ->
            appendLine("- #${index + 1} ${row.kindLabel} rank=${row.rank} reason=${row.reason} url=${row.redactedUrl}")
        }
        appendLine("Diagnostics:")
        diagnostics.forEach { appendLine("- $it") }
    }.trimEnd()

    private fun directHttpUrl(value: String): String? {
        if (value.any(Char::isWhitespace)) return null
        return value.takeIf { isHttpUrl(it) }
    }

    private fun firstHttpUrl(value: String): String? = Regex("""https?://[^\s<>"'`]+""", RegexOption.IGNORE_CASE)
        .find(value)
        ?.value
        ?.trimEnd(')', ']', '}', '>', ',', '.', ';', ':')
        ?.takeIf { isHttpUrl(it) }

    private fun isHttpUrl(value: String): Boolean {
        val uri = runCatching { URI(value.trim()) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase(Locale.US) ?: return false
        return (scheme == "http" || scheme == "https") && !uri.host.isNullOrBlank()
    }

    private fun inputShape(value: String): String = when {
        value.startsWith("<", ignoreCase = false) -> "html"
        value.startsWith("{") || value.startsWith("[") -> "json"
        directHttpUrl(value) != null -> "url"
        firstHttpUrl(value) != null -> "text-with-url"
        else -> "text"
    }

    private fun MediaSourceKind.debugLabel(): String = when (this) {
        MediaSourceKind.HlsPlaylist -> "HLS manifest"
        MediaSourceKind.DashManifest -> "DASH manifest"
        MediaSourceKind.ProgressiveMedia -> "Progressive media"
        MediaSourceKind.VideoStream -> "Video stream"
        MediaSourceKind.AudioStream -> "Audio stream"
        MediaSourceKind.DirectFile -> "Direct file"
        MediaSourceKind.Unknown -> "Unknown"
    }
}
