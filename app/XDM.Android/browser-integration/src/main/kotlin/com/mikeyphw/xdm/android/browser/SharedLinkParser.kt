package com.mikeyphw.xdm.android.browser

import com.mikeyphw.xdm.android.model.BrowserHandoffPolicy

object BrowserHandoffContract {
    const val ExtraOriginPackage = "com.mikeyphw.xdm.android.extra.ORIGIN_PACKAGE"
    const val ExtraRequestHeaders = "com.mikeyphw.xdm.android.extra.REQUEST_HEADERS"
    const val ExtraCookieHeader = "com.mikeyphw.xdm.android.extra.COOKIE_HEADER"
}

object SharedLinkParser {
    fun parse(text: String): List<String> = BrowserHandoffPolicy.urlsInText(text)
}
