package com.mikeyphw.xdm.android.browser

import com.mikeyphw.xdm.android.model.BrowserHandoffPolicy

object BrowserHandoffContract {
    const val ExtraOriginPackage = "com.mikeyphw.xdm.android.extra.ORIGIN_PACKAGE"
    const val ExtraRequestHeaders = "com.mikeyphw.xdm.android.extra.REQUEST_HEADERS"
    const val ExtraCookieHeader = "com.mikeyphw.xdm.android.extra.COOKIE_HEADER"
}

object SharedLinkParser {
    private val urlPattern = Regex("""https?://[^\s<>()\[\]{}\"']+""", RegexOption.IGNORE_CASE)
    fun parse(text: String): List<String> = urlPattern.findAll(text)
        .mapNotNull { BrowserHandoffPolicy.normalizedUrl(it.value) }
        .distinct()
        .toList()
}
