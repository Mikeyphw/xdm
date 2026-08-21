package com.mikeyphw.xdm.android.browser

import com.mikeyphw.xdm.android.model.AutomationCommandAction
import com.mikeyphw.xdm.android.model.ExternalUrlPolicy
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/** Parses XDM browser custom-scheme links. v3 capture is intentionally direct/keyless. */
object XdmBrowserDeepLinkParser {
    private val allowedCaptureHeaders = setOf(
        "authorization", "cookie", "referer", "user-agent", "origin", "accept", "accept-language", "range",
    )

    fun parse(rawDeepLink: String?, expectedScheme: String): XdmBrowserDeepLinkPayload? =
        (parseDetailed(rawDeepLink, expectedScheme) as? XdmBrowserDeepLinkParseResult.Accepted)?.payload

    fun parseDetailed(rawDeepLink: String?, expectedScheme: String): XdmBrowserDeepLinkParseResult {
        val raw = rawDeepLink?.trim()?.takeIf(String::isNotBlank) ?: return XdmBrowserDeepLinkParseResult.NotApplicable
        val schemePrefix = raw.substringBefore(':', missingDelimiterValue = "").lowercase(Locale.US)
        if (schemePrefix !in XdmBrowserDeepLinkContract.BuildVariantSchemes) return XdmBrowserDeepLinkParseResult.NotApplicable
        if (raw.utf8Size() > XdmBrowserDeepLinkContract.MaxDeepLinkBytes) return XdmBrowserDeepLinkParseResult.Rejected(XdmBrowserDeepLinkRejection.PayloadTooLarge)

        val normalizedExpectedScheme = expectedScheme.trim().lowercase(Locale.US)
        if (normalizedExpectedScheme !in XdmBrowserDeepLinkContract.BuildVariantSchemes) return XdmBrowserDeepLinkParseResult.Rejected(XdmBrowserDeepLinkRejection.InvalidExpectedScheme)
        val uri = runCatching { URI(raw) }.getOrNull() ?: return XdmBrowserDeepLinkParseResult.Rejected(XdmBrowserDeepLinkRejection.MalformedUri)
        val incomingScheme = uri.scheme?.lowercase(Locale.US) ?: return XdmBrowserDeepLinkParseResult.Rejected(XdmBrowserDeepLinkRejection.MalformedUri)
        if (incomingScheme != normalizedExpectedScheme) return XdmBrowserDeepLinkParseResult.Rejected(XdmBrowserDeepLinkRejection.VariantMismatch)
        if (uri.rawUserInfo != null || uri.port != -1 || uri.rawFragment != null) return XdmBrowserDeepLinkParseResult.Rejected(XdmBrowserDeepLinkRejection.UnsafeEnvelope)
        if (!uri.rawPath.isNullOrBlank() && uri.rawPath != "/") return XdmBrowserDeepLinkParseResult.Rejected(XdmBrowserDeepLinkRejection.UnsafeEnvelope)

        val action = when (uri.host?.lowercase(Locale.US)) {
            XdmBrowserDeepLinkContract.CaptureHost -> AutomationCommandAction.CaptureMedia
            XdmBrowserDeepLinkContract.AddHost -> AutomationCommandAction.PromptAddDownload
            else -> return XdmBrowserDeepLinkParseResult.Rejected(XdmBrowserDeepLinkRejection.UnsupportedAction)
        }
        val parameters = parseQuery(uri.rawQuery) ?: return XdmBrowserDeepLinkParseResult.Rejected(XdmBrowserDeepLinkRejection.MalformedQuery)
        val version = parameters.singleValue(XdmBrowserDeepLinkContract.VersionParameter)?.toIntOrNull()
            ?: return XdmBrowserDeepLinkParseResult.Rejected(XdmBrowserDeepLinkRejection.UnsupportedContract)
        if (version !in XdmBrowserDeepLinkContract.SupportedVersions) return XdmBrowserDeepLinkParseResult.Rejected(XdmBrowserDeepLinkRejection.UnsupportedContract)

        if (action == AutomationCommandAction.CaptureMedia) {
            return when (version) {
                XdmBrowserDeepLinkContract.EncryptedCaptureVersion -> parseEncryptedCaptureEnvelope(version, action, parameters)
                XdmBrowserDeepLinkContract.CurrentVersion -> parseDirectPayload(version, action, parameters, allowHeaders = true)
                else -> XdmBrowserDeepLinkParseResult.Rejected(XdmBrowserDeepLinkRejection.UnsafeEnvelope)
            }
        }
        return parseDirectPayload(version, action, parameters, allowHeaders = false)
    }

    private fun parseDirectPayload(
        version: Int,
        action: AutomationCommandAction,
        parameters: Map<String, List<String>>,
        allowHeaders: Boolean,
    ): XdmBrowserDeepLinkParseResult {
        val rawMediaUrl = parameters.singleValue(XdmBrowserDeepLinkContract.UrlParameter)
            ?: return XdmBrowserDeepLinkParseResult.Rejected(XdmBrowserDeepLinkRejection.MissingMediaUrl)
        val mediaUrl = rawMediaUrl.strictExternalUrl(XdmBrowserDeepLinkContract.MaxMediaUrlBytes)
            ?: return XdmBrowserDeepLinkParseResult.Rejected(XdmBrowserDeepLinkRejection.UnsafeMediaUrl)
        val pageUrl = parameters.singleValue(XdmBrowserDeepLinkContract.PageUrlParameter)?.let {
            it.strictExternalUrl(XdmBrowserDeepLinkContract.MaxPageUrlBytes)
                ?: return XdmBrowserDeepLinkParseResult.Rejected(XdmBrowserDeepLinkRejection.UnsafePageUrl)
        }
        val frameUrl = parameters.singleValue(XdmBrowserDeepLinkContract.FrameUrlParameter)?.let {
            it.strictExternalUrl(XdmBrowserDeepLinkContract.MaxPageUrlBytes)
                ?: return XdmBrowserDeepLinkParseResult.Rejected(XdmBrowserDeepLinkRejection.UnsafePageUrl)
        }
        val rawThumbnailUrl = parameters.singleValue(XdmBrowserDeepLinkContract.ThumbnailUrlParameter)
            ?: parameters.singleValue("poster") ?: parameters.singleValue("thumbnailUrl")
        val thumbnailUrl = rawThumbnailUrl?.let {
            it.strictExternalUrl(XdmBrowserDeepLinkContract.MaxThumbnailUrlBytes)
                ?: return XdmBrowserDeepLinkParseResult.Rejected(XdmBrowserDeepLinkRejection.UnsafeMediaUrl)
        }
        val contentLength = parameters.singleValue(XdmBrowserDeepLinkContract.ContentLengthParameter)
            ?: parameters.singleValue("size") ?: parameters.singleValue("contentLength")
        val durationMs = parameters.singleValue(XdmBrowserDeepLinkContract.DurationMsParameter)
            ?: parameters.singleValue("duration")
        val proposed = if (allowHeaders) parameters.singleValue(XdmBrowserDeepLinkContract.ProposedHeadersParameter).sanitizedHeaderBlock() else null
        val final = if (allowHeaders) parameters.singleValue(XdmBrowserDeepLinkContract.FinalHeadersParameter).sanitizedHeaderBlock() else null
        val rawHeaders = if (allowHeaders) {
            parameters.singleValue(XdmBrowserDeepLinkContract.RawHeadersParameter).sanitizedHeaderBlock()
                ?: final ?: proposed
        } else null

        return XdmBrowserDeepLinkParseResult.Accepted(
            XdmBrowserDeepLinkPayload(
                version = version,
                action = action,
                url = mediaUrl,
                pageUrl = pageUrl,
                pageTitle = parameters.singleValue(XdmBrowserDeepLinkContract.PageTitleParameter).sanitizedText(XdmBrowserDeepLinkContract.MaxPageTitleCharacters),
                fileName = parameters.singleValue(XdmBrowserDeepLinkContract.FileNameParameter).sanitizedFileName(),
                mimeType = parameters.singleValue(XdmBrowserDeepLinkContract.MimeTypeParameter).sanitizedMimeType(),
                mediaKind = parameters.singleValue(XdmBrowserDeepLinkContract.MediaKindParameter).sanitizedMediaKind(),
                stableMediaId = parameters.singleValue(XdmBrowserDeepLinkContract.StableMediaIdParameter).sanitizedStableMediaId(),
                sessionRevision = parameters.singleValue(XdmBrowserDeepLinkContract.SessionRevisionParameter)?.toLongOrNull()?.takeIf { it > 0L },
                contentLength = contentLength?.toLongOrNull()?.takeIf { it > 0L },
                durationMs = durationMs?.toLongOrNull()?.takeIf { it > 0L },
                thumbnailUrl = thumbnailUrl,
                frameUrl = frameUrl,
                rawHeaders = rawHeaders,
                proposedHeaders = proposed,
                finalHeaders = final,
            ),
        )
    }

    private fun parseEncryptedCaptureEnvelope(version: Int, action: AutomationCommandAction, parameters: Map<String, List<String>>): XdmBrowserDeepLinkParseResult {
        fun required(name: String, maxCharacters: Int, pattern: Regex): String? = parameters.singleValue(name)
            ?.trim()?.takeIf { it.isNotBlank() && it.length <= maxCharacters && pattern.matches(it) }
        val token = Regex("[A-Za-z0-9_-]+")
        val sessionId = required(XdmBrowserDeepLinkContract.CaptureSessionIdParameter, XdmBrowserDeepLinkContract.MaxCaptureSessionIdCharacters, Regex("[A-Za-z0-9._:-]+"))
            ?: return XdmBrowserDeepLinkParseResult.Rejected(XdmBrowserDeepLinkRejection.UnsafeEnvelope)
        val keyId = required(XdmBrowserDeepLinkContract.CaptureKeyIdParameter, XdmBrowserDeepLinkContract.MaxCaptureKeyIdCharacters, Regex("[A-Za-z0-9._:-]+"))
            ?: return XdmBrowserDeepLinkParseResult.Rejected(XdmBrowserDeepLinkRejection.UnsafeEnvelope)
        val wrappedKey = required(XdmBrowserDeepLinkContract.WrappedKeyParameter, XdmBrowserDeepLinkContract.MaxWrappedKeyCharacters, token)
            ?: return XdmBrowserDeepLinkParseResult.Rejected(XdmBrowserDeepLinkRejection.UnsafeEnvelope)
        val iv = required(XdmBrowserDeepLinkContract.EnvelopeIvParameter, XdmBrowserDeepLinkContract.MaxEnvelopeIvCharacters, token)
            ?: return XdmBrowserDeepLinkParseResult.Rejected(XdmBrowserDeepLinkRejection.UnsafeEnvelope)
        val ciphertext = required(XdmBrowserDeepLinkContract.EnvelopeCiphertextParameter, XdmBrowserDeepLinkContract.MaxEnvelopeCiphertextCharacters, token)
            ?: return XdmBrowserDeepLinkParseResult.Rejected(XdmBrowserDeepLinkRejection.UnsafeEnvelope)
        return XdmBrowserDeepLinkParseResult.Accepted(XdmBrowserDeepLinkPayload(version, action, captureSessionId = sessionId, captureKeyId = keyId, wrappedKey = wrappedKey, envelopeIv = iv, envelopeCiphertext = ciphertext))
    }

    private fun parseQuery(rawQuery: String?): Map<String, List<String>>? {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        val output = linkedMapOf<String, MutableList<String>>()
        for (part in rawQuery.split('&')) {
            if (part.isBlank()) continue
            val separator = part.indexOf('=')
            val rawName = if (separator >= 0) part.substring(0, separator) else part
            val rawValue = if (separator >= 0) part.substring(separator + 1) else ""
            val name = decode(rawName)?.lowercase(Locale.US) ?: return null
            val value = decode(rawValue) ?: return null
            output.getOrPut(name) { mutableListOf() }.add(value)
        }
        return output
    }

    private fun Map<String, List<String>>.singleValue(name: String): String? = this[name.lowercase(Locale.US)]?.singleOrNull()
    private fun decode(value: String): String? = runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8.name()) }.getOrNull()

    private fun String.strictExternalUrl(maxBytes: Int): String? {
        val candidate = trim().takeIf(String::isNotBlank) ?: return null
        if (candidate.utf8Size() > maxBytes) return null
        val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.US) ?: return null
        if (scheme !in setOf("http", "https", "ftp") || uri.rawUserInfo != null || uri.host.isNullOrBlank()) return null
        return ExternalUrlPolicy.normalizedUrl(candidate)
    }

    private fun String?.sanitizedHeaderBlock(): String? {
        val raw = this?.take(XdmBrowserDeepLinkContract.MaxHeaderBlockCharacters) ?: return null
        val lines = raw.lineSequence().take(XdmBrowserDeepLinkContract.MaxHeaderLines).mapNotNull { line ->
            val split = line.indexOf(':')
            if (split <= 0) return@mapNotNull null
            val name = line.substring(0, split).trim().lowercase(Locale.US)
            if (name !in allowedCaptureHeaders) return@mapNotNull null
            val value = line.substring(split + 1).replace(Regex("[\r\n\u0000-\u001F\u007F]+"), " ").trim()
                .take(XdmBrowserDeepLinkContract.MaxHeaderValueCharacters)
            if (value.isBlank()) null else "$name: $value"
        }.toList()
        return lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    private fun String?.sanitizedText(maxCharacters: Int): String? = this?.replace(Regex("[\u0000-\u001F\u007F]"), " ")?.trim()?.takeIf(String::isNotBlank)?.take(maxCharacters)
    private fun String?.sanitizedFileName(): String? = sanitizedText(XdmBrowserDeepLinkContract.MaxFileNameCharacters * 2)?.substringAfterLast('/')?.substringAfterLast('\\')?.trim()?.takeIf(String::isNotBlank)?.take(XdmBrowserDeepLinkContract.MaxFileNameCharacters)
    private fun String?.sanitizedMimeType(): String? = this?.substringBefore(';')?.trim()?.lowercase(Locale.US)?.take(XdmBrowserDeepLinkContract.MaxMimeTypeCharacters)?.takeIf { it.matches(Regex("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+")) }
    private fun String?.sanitizedMediaKind(): String? = this?.trim()?.lowercase(Locale.US)?.take(XdmBrowserDeepLinkContract.MaxMediaKindCharacters)?.takeIf { it.matches(Regex("[a-z0-9_-]+")) }
    private fun String?.sanitizedStableMediaId(): String? = this?.trim()?.take(XdmBrowserDeepLinkContract.MaxStableMediaIdCharacters)?.takeIf { it.matches(Regex("[A-Za-z0-9._:-]{8,160}")) }
    private fun String.utf8Size(): Int = toByteArray(StandardCharsets.UTF_8).size
}
