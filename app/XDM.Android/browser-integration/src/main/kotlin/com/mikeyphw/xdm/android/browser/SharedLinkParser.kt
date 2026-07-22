package com.mikeyphw.xdm.android.browser

import com.mikeyphw.xdm.android.model.BrowserHandoffPolicy

object BrowserHandoffContract {
    const val ExtraOriginPackage = "com.mikeyphw.xdm.android.extra.ORIGIN_PACKAGE"
    const val ExtraDownloadUrl = "com.mikeyphw.xdm.android.extra.DOWNLOAD_URL"
    const val ExtraFileName = "com.mikeyphw.xdm.android.extra.FILE_NAME"
    const val ExtraMimeType = "com.mikeyphw.xdm.android.extra.MIME_TYPE"
    const val ExtraRequestHeaders = "com.mikeyphw.xdm.android.extra.REQUEST_HEADERS"
    const val ExtraCookieHeader = "com.mikeyphw.xdm.android.extra.COOKIE_HEADER"

    const val ActionDownload = "android.intent.action.DOWNLOAD"
    const val ActionDownloadUri = "android.intent.action.DOWNLOAD_URI"
    const val ActionBrowserDownload = "com.android.browser.action.DOWNLOAD"
    const val ActionBrowserIntentDownload = "com.android.browser.intent.action.DOWNLOAD"

    val DownloadManagerActions = setOf(
        ActionDownload,
        ActionDownloadUri,
        ActionBrowserDownload,
        ActionBrowserIntentDownload,
    )
}

object SharedLinkParser {
    fun parse(text: String): List<String> = BrowserHandoffPolicy.urlsInText(text)
}
