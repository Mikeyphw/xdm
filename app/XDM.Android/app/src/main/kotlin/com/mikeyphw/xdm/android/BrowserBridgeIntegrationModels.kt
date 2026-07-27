package com.mikeyphw.xdm.android

import com.mikeyphw.xdm.android.browser.XdmBrowserDeepLinkPayload
import com.mikeyphw.xdm.android.browser.XdmBrowserDeepLinkRejection
import com.mikeyphw.xdm.android.browserextension.BrowserExtensionSourceContract
import java.net.URI
import java.util.Locale

enum class BrowserBridgeSchemeState {
    Ready,
    Missing,
    WrongHandler,
}

enum class BrowserBridgeSafState {
    NotConfigured,
    Ready,
    PermissionRevoked,
    ExportMissing,
    Unreadable,
    ChecksumMismatch,
}

data class BrowserBridgeDiagnosticsPreferences(
    val lastAcceptedSummary: String = "",
    val lastAcceptedEpochMs: Long = 0L,
    val lastRejectedCode: String = "",
    val lastRejectedSummary: String = "",
    val lastRejectedEpochMs: Long = 0L,
    val lastGenerationPhase: String = "idle",
    val lastGenerationMessage: String = "",
    val lastGenerationEpochMs: Long = 0L,
)

data class BrowserBridgeIntegrationStatus(
    val schemeState: BrowserBridgeSchemeState = BrowserBridgeSchemeState.Missing,
    val schemeDetail: String = "Checking XDM browser scheme registration.",
    val safState: BrowserBridgeSafState = BrowserBridgeSafState.NotConfigured,
    val safDetail: String = "Choose an export folder.",
    val compatibilityIssues: List<String> = emptyList(),
    val canOpenExport: Boolean = false,
    val currentExportUri: String = "",
    val detectorVersion: String = BrowserExtensionSourceContract.DevelopmentVersion,
    val contractVersion: Int = BrowserExtensionSourceContract.ContractVersion,
) {
    val isReady: Boolean
        get() = schemeState == BrowserBridgeSchemeState.Ready &&
            safState == BrowserBridgeSafState.Ready &&
            compatibilityIssues.isEmpty()

    fun redactedReport(diagnostics: BrowserBridgeDiagnosticsPreferences): String = buildString {
        appendLine("XDM Browser Bridge")
        appendLine("Scheme: ${schemeState.name.lowercase(Locale.US)} • $schemeDetail")
        appendLine("Export access: ${safState.name.lowercase(Locale.US)} • $safDetail")
        appendLine("Extension: $detectorVersion • contract $contractVersion")
        if (compatibilityIssues.isEmpty()) appendLine("Compatibility: current")
        else compatibilityIssues.forEach { appendLine("Compatibility: ${BrowserBridgeDiagnosticsRedactor.sanitize(it)}") }
        diagnostics.lastAcceptedSummary.takeIf(String::isNotBlank)?.let {
            appendLine("Last accepted link: ${BrowserBridgeDiagnosticsRedactor.sanitize(it)}")
        }
        diagnostics.lastRejectedSummary.takeIf(String::isNotBlank)?.let {
            appendLine("Last rejected link: ${BrowserBridgeDiagnosticsRedactor.sanitize(it)}")
        }
        diagnostics.lastGenerationMessage.takeIf(String::isNotBlank)?.let {
            appendLine("Last generation: ${BrowserBridgeDiagnosticsRedactor.sanitize(it)}")
        }
    }.trimEnd()
}

object BrowserBridgeDiagnosticsRedactor {
    private val sensitiveAssignment = Regex(
        "(?i)(authorization|cookie|set-cookie|proxy-authorization|token|access_token|refresh_token|signature|sig|session|credential|password|passwd|secret)\\s*[:=]\\s*([^&\\s]+)",
    )
    private val bearer = Regex("(?i)bearer\\s+[a-z0-9._~+/-]+={0,2}")
    private val uriWithQuery = Regex("(?i)\\b(https?|ftp)://[^\\s]+")

    fun acceptedSummary(payload: XdmBrowserDeepLinkPayload): String {
        val action = when (payload.action.name) {
            "CaptureMedia" -> "capture"
            "PromptAddDownload" -> "add"
            else -> "handoff"
        }
        val media = safeEndpoint(payload.url)
        val kind = payload.mediaKind ?: payload.mimeType ?: "media"
        return sanitize("$action • $kind • $media").take(320)
    }

    fun rejectedSummary(reason: XdmBrowserDeepLinkRejection): String =
        "${reason.code} • ${reason.userMessage}".take(320)

    fun sanitize(value: String): String = value
        .replace(bearer, "Bearer <redacted>")
        .replace(sensitiveAssignment) { match -> "${match.groupValues[1]}=<redacted>" }
        .replace(uriWithQuery) { match -> safeEndpoint(match.value) }
        .replace(Regex("[\\u0000-\\u001F\\u007F]"), " ")
        .trim()
        .take(512)

    private fun safeEndpoint(raw: String): String = runCatching {
        val uri = URI(raw)
        val scheme = uri.scheme?.lowercase(Locale.US).orEmpty()
        val host = uri.host?.lowercase(Locale.US).orEmpty()
        val path = uri.path.orEmpty().take(96)
        if (scheme in setOf("http", "https", "ftp") && host.isNotBlank()) "$scheme://$host$path" else "<redacted-url>"
    }.getOrDefault("<redacted-url>")
}

fun browserBridgeIronFoxInstructions(scheme: String): String = """
IronFox setup for $scheme

1. Open Settings → Advanced → Open links in apps → Always.
2. Open about:config and set these Boolean preferences:
   network.protocol-handler.expose-all = true
   network.protocol-handler.expose.$scheme = true
   network.protocol-handler.external.$scheme = true
   network.protocol-handler.warn-external.$scheme = false
3. Force-stop IronFox, reopen it, then reload the video page.
4. Install the generated XPI from IronFox's extension-from-file menu.

The extension uses a real in-page link. It never places cookies, authorization headers, or raw request-header blocks in the custom URI.
""".trimIndent()
