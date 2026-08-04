package com.mikeyphw.xdm.android.browser

import com.mikeyphw.xdm.android.model.AutomationCommandAction
import com.mikeyphw.xdm.android.model.ExternalUrlPolicy
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Parses the non-sensitive custom-scheme envelope used by the XDM Firefox bridge.
 *
 * Cookies, authorization headers, proxy credentials, request bodies, and unrestricted
 * header blocks are deliberately not part of this contract. Signed query values that
 * already belong to the media URL are preserved by [ExternalUrlPolicy].
 */
object XdmBrowserDeepLinkParser {
    fun parse(rawDeepLink: String?, expectedScheme: String): XdmBrowserDeepLinkPayload? =
        (parseDetailed(rawDeepLink, expectedScheme) as? XdmBrowserDeepLinkParseResult.Accepted)?.payload

    fun parseDetailed(rawDeepLink: String?, expectedScheme: String): XdmBrowserDeepLinkParseResult {
        val raw = rawDeepLink?.trim()?.takeIf(String::isNotBlank)
            ?: return XdmBrowserDeepLinkParseResult.NotApplicable
        val schemePrefix = raw.substringBefore(':', missingDelimiterValue = "").lowercase(Locale.US)
        if (schemePrefix !in XdmBrowserDeepLinkContract.BuildVariantSchemes) {
            return XdmBrowserDeepLinkParseResult.NotApplicable
        }
        if (raw.utf8Size() > XdmBrowserDeepLinkContract.MaxDeepLinkBytes) {
            return XdmBrowserDeepLinkParseResult.Rejected(XdmBrowserDeepLinkRejection.PayloadTooLarge)
        }

        val normalizedExpectedScheme = expectedScheme.trim().lowercase(Locale.US)
        if (normalizedExpectedScheme !in XdmBrowserDeepLinkContract.BuildVariantSchemes) {
            return XdmBrowserDeepLinkParseResult.Rejected(XdmBrowserDeepLinkRejection.InvalidExpectedScheme)
        }

        val uri = runCatching { URI(raw) }.getOrNull()
            ?: return XdmBrowserDeepLinkParseResult.Rejected(XdmBrowserDeepLinkRejection.MalformedUri)
        val incomingScheme = uri.scheme?.lowercase(Locale.US)
            ?: return XdmBrowserDeepLinkParseResult.Rejected(XdmBrowserDeepLinkRejection.MalformedUri)
        if (incomingScheme != normalizedExpectedScheme) {
            return XdmBrowserDeepLinkParseResult.Rejected(XdmBrowserDeepLinkRejection.VariantMismatch)
        }
        if (uri.rawUserInfo != null || uri.port != -1 || uri.rawFragment != null) {
            return XdmBrowserDeepLinkParseResult.Rejected(XdmBrowserDeepLinkRejection.UnsafeEnvelope)
        }
        if (!uri.rawPath.isNullOrBlank() && uri.rawPath != "/") {
            return XdmBrowserDeepLinkParseResult.Rejected(XdmBrowserDeepLinkRejection.UnsafeEnvelope)
        }

        val action = when (uri.host?.lowercase(Locale.US)) {
            XdmBrowserDeepLinkContract.CaptureHost -> AutomationCommandAction.CaptureMedia
            XdmBrowserDeepLinkContract.AddHost -> AutomationCommandAction.PromptAddDownload
            else -> return XdmBrowserDeepLinkParseResult.Rejected(XdmBrowserDeepLinkRejection.UnsupportedAction)
        }

        val parameters = parseQuery(uri.rawQuery)
            ?: return XdmBrowserDeepLinkParseResult.Rejected(XdmBrowserDeepLinkRejection.MalformedQuery)
        val versionValue = parameters.singleValue(XdmBrowserDeepLinkContract.VersionParameter)
        val version = versionValue?.toIntOrNull()
            ?: return XdmBrowserDeepLinkParseResult.Rejected(XdmBrowserDeepLinkRejection.UnsupportedContract)
        if (version != XdmBrowserDeepLinkContract.CurrentVersion) {
            return XdmBrowserDeepLinkParseResult.Rejected(XdmBrowserDeepLinkRejection.UnsupportedContract)
        }

        val rawMediaUrl = parameters.singleValue(XdmBrowserDeepLinkContract.UrlParameter)
            ?: return XdmBrowserDeepLinkParseResult.Rejected(XdmBrowserDeepLinkRejection.MissingMediaUrl)
        val mediaUrl = rawMediaUrl.strictExternalUrl(XdmBrowserDeepLinkContract.MaxMediaUrlBytes)
            ?: return XdmBrowserDeepLinkParseResult.Rejected(XdmBrowserDeepLinkRejection.UnsafeMediaUrl)

        val rawPageUrl = parameters.singleValue(XdmBrowserDeepLinkContract.PageUrlParameter)
        val pageUrl = when {
            rawPageUrl == null -> null
            else -> rawPageUrl.strictExternalUrl(XdmBrowserDeepLinkContract.MaxPageUrlBytes)
                ?: return XdmBrowserDeepLinkParseResult.Rejected(XdmBrowserDeepLinkRejection.UnsafePageUrl)
        }
        val rawFrameUrl = parameters.singleValue(XdmBrowserDeepLinkContract.FrameUrlParameter)
        val frameUrl = when {
            rawFrameUrl == null -> null
            else -> rawFrameUrl.strictExternalUrl(XdmBrowserDeepLinkContract.MaxPageUrlBytes)
                ?: return XdmBrowserDeepLinkParseResult.Rejected(XdmBrowserDeepLinkRejection.UnsafePageUrl)
        }

        return XdmBrowserDeepLinkParseResult.Accepted(
            XdmBrowserDeepLinkPayload(
                version = version,
                action = action,
                url = mediaUrl,
                pageUrl = pageUrl,
                pageTitle = parameters.singleValue(XdmBrowserDeepLinkContract.PageTitleParameter)
                    .sanitizedText(XdmBrowserDeepLinkContract.MaxPageTitleCharacters),
                fileName = parameters.singleValue(XdmBrowserDeepLinkContract.FileNameParameter)
                    .sanitizedFileName(),
                mimeType = parameters.singleValue(XdmBrowserDeepLinkContract.MimeTypeParameter)
                    .sanitizedMimeType(),
                mediaKind = parameters.singleValue(XdmBrowserDeepLinkContract.MediaKindParameter)
                    .sanitizedMediaKind(),
                stableMediaId = parameters.singleValue(XdmBrowserDeepLinkContract.StableMediaIdParameter)
                    .sanitizedStableMediaId(),
                sessionRevision = parameters.singleValue(XdmBrowserDeepLinkContract.SessionRevisionParameter)
                    ?.toLongOrNull()
                    ?.takeIf { it > 0L },
                frameUrl = frameUrl,
            ),
        )
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

    private fun Map<String, List<String>>.singleValue(name: String): String? {
        val values = this[name] ?: return null
        return values.singleOrNull()
    }

    private fun decode(value: String): String? = runCatching {
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    }.getOrNull()

    private fun String.strictExternalUrl(maxBytes: Int): String? {
        val candidate = trim().takeIf(String::isNotBlank) ?: return null
        if (candidate.utf8Size() > maxBytes) return null
        val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.US) ?: return null
        if (scheme !in setOf("http", "https", "ftp")) return null
        if (uri.rawUserInfo != null || uri.host.isNullOrBlank()) return null
        return ExternalUrlPolicy.normalizedUrl(candidate)
    }

    private fun String?.sanitizedText(maxCharacters: Int): String? = this
        ?.replace(Regex("[\u0000-\u001F\u007F]"), " ")
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.take(maxCharacters)

    private fun String?.sanitizedFileName(): String? = sanitizedText(
        XdmBrowserDeepLinkContract.MaxFileNameCharacters * 2,
    )
        ?.substringAfterLast('/')
        ?.substringAfterLast('\\')
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.take(XdmBrowserDeepLinkContract.MaxFileNameCharacters)

    private fun String?.sanitizedMimeType(): String? = this
        ?.substringBefore(';')
        ?.trim()
        ?.lowercase(Locale.US)
        ?.take(XdmBrowserDeepLinkContract.MaxMimeTypeCharacters)
        ?.takeIf { value -> value.matches(Regex("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+")) }

    private fun String?.sanitizedMediaKind(): String? = this
        ?.trim()
        ?.lowercase(Locale.US)
        ?.take(XdmBrowserDeepLinkContract.MaxMediaKindCharacters)
        ?.takeIf { value -> value.matches(Regex("[a-z0-9_-]+")) }

    private fun String?.sanitizedStableMediaId(): String? = this
        ?.trim()
        ?.take(XdmBrowserDeepLinkContract.MaxStableMediaIdCharacters)
        ?.takeIf { value -> value.matches(Regex("[A-Za-z0-9._:-]{8,160}")) }

    private fun String.utf8Size(): Int = toByteArray(StandardCharsets.UTF_8).size
}
