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
    fun parse(rawDeepLink: String?, expectedScheme: String): XdmBrowserDeepLinkPayload? {
        val raw = rawDeepLink?.trim()?.takeIf(String::isNotBlank) ?: return null
        if (raw.utf8Size() > XdmBrowserDeepLinkContract.MaxDeepLinkBytes) return null

        val normalizedExpectedScheme = expectedScheme.trim().lowercase(Locale.US)
        if (normalizedExpectedScheme !in XdmBrowserDeepLinkContract.BuildVariantSchemes) return null

        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        if (!uri.scheme.equals(normalizedExpectedScheme, ignoreCase = true)) return null
        if (uri.rawUserInfo != null || uri.port != -1 || uri.rawFragment != null) return null
        if (!uri.rawPath.isNullOrBlank() && uri.rawPath != "/") return null

        val action = when (uri.host?.lowercase(Locale.US)) {
            XdmBrowserDeepLinkContract.CaptureHost -> AutomationCommandAction.CaptureMedia
            XdmBrowserDeepLinkContract.AddHost -> AutomationCommandAction.PromptAddDownload
            else -> return null
        }

        val parameters = parseQuery(uri.rawQuery) ?: return null
        val version = parameters.singleValue(XdmBrowserDeepLinkContract.VersionParameter)?.toIntOrNull()
            ?: return null
        if (version != XdmBrowserDeepLinkContract.CurrentVersion) return null

        val mediaUrl = parameters.singleValue(XdmBrowserDeepLinkContract.UrlParameter)
            ?.strictExternalUrl(XdmBrowserDeepLinkContract.MaxMediaUrlBytes)
            ?: return null

        val rawPageUrl = parameters.singleValue(XdmBrowserDeepLinkContract.PageUrlParameter)
        val pageUrl = when {
            rawPageUrl == null -> null
            else -> rawPageUrl.strictExternalUrl(XdmBrowserDeepLinkContract.MaxPageUrlBytes) ?: return null
        }

        val pageTitle = parameters.singleValue(XdmBrowserDeepLinkContract.PageTitleParameter)
            .sanitizedText(XdmBrowserDeepLinkContract.MaxPageTitleCharacters)
        val fileName = parameters.singleValue(XdmBrowserDeepLinkContract.FileNameParameter)
            .sanitizedFileName()
        val mimeType = parameters.singleValue(XdmBrowserDeepLinkContract.MimeTypeParameter)
            .sanitizedMimeType()
        val mediaKind = parameters.singleValue(XdmBrowserDeepLinkContract.MediaKindParameter)
            .sanitizedMediaKind()

        return XdmBrowserDeepLinkPayload(
            version = version,
            action = action,
            url = mediaUrl,
            pageUrl = pageUrl,
            pageTitle = pageTitle,
            fileName = fileName,
            mimeType = mimeType,
            mediaKind = mediaKind,
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
        ?.replace(Regex("[\\u0000-\\u001F\\u007F]"), " ")
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

    private fun String.utf8Size(): Int = toByteArray(StandardCharsets.UTF_8).size
}
