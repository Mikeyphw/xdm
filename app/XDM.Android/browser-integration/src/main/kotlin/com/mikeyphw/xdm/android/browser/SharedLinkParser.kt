package com.mikeyphw.xdm.android.browser

import com.mikeyphw.xdm.android.model.ExternalUrlPolicy

object BrowserHandoffContract {
    const val ExtraOriginPackage = "com.mikeyphw.xdm.android.extra.ORIGIN_PACKAGE"
    const val ExtraDownloadUrl = "com.mikeyphw.xdm.android.extra.DOWNLOAD_URL"
    const val ExtraFileName = "com.mikeyphw.xdm.android.extra.FILE_NAME"
    const val ExtraMimeType = "com.mikeyphw.xdm.android.extra.MIME_TYPE"
    const val ExtraContentLength = "com.mikeyphw.xdm.android.extra.CONTENT_LENGTH"
    const val ExtraPageUrl = "com.mikeyphw.xdm.android.extra.PAGE_URL"
    const val ExtraPageTitle = "com.mikeyphw.xdm.android.extra.PAGE_TITLE"
    const val ExtraRequestHeaders = "com.mikeyphw.xdm.android.extra.REQUEST_HEADERS"
    const val ExtraCookieHeader = "com.mikeyphw.xdm.android.extra.COOKIE_HEADER"
    const val ExtraStableMediaId = "com.mikeyphw.xdm.android.extra.STABLE_MEDIA_ID"
    const val ExtraSessionRevision = "com.mikeyphw.xdm.android.extra.SESSION_REVISION"
    const val ExtraFrameUrl = "com.mikeyphw.xdm.android.extra.FRAME_URL"
    const val ExtraProposedRequestHeaders = "com.mikeyphw.xdm.android.extra.PROPOSED_REQUEST_HEADERS"
    const val ExtraFinalRequestHeaders = "com.mikeyphw.xdm.android.extra.FINAL_REQUEST_HEADERS"
    const val ExtraPageObservationNonce = "com.mikeyphw.xdm.android.extra.PAGE_OBSERVATION_NONCE"
    const val ExtraPageObservationCreatedAt = "com.mikeyphw.xdm.android.extra.PAGE_OBSERVATION_CREATED_AT"
    const val ExtraPageObservationExpiresAt = "com.mikeyphw.xdm.android.extra.PAGE_OBSERVATION_EXPIRES_AT"

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
    fun parse(text: String): List<String> = ExternalUrlPolicy.urlsInText(text)
}
