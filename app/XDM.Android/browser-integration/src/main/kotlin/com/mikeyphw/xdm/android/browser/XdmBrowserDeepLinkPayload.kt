package com.mikeyphw.xdm.android.browser

import com.mikeyphw.xdm.android.model.AutomationCommandAction
import com.mikeyphw.xdm.android.model.AutomationCommandDraft
import com.mikeyphw.xdm.android.model.AutomationCommandSource

/** Sanitized data extracted from an XDM browser custom-scheme link. */
data class XdmBrowserDeepLinkPayload(
    val version: Int,
    val action: AutomationCommandAction,
    val url: String,
    val pageUrl: String? = null,
    val pageTitle: String? = null,
    val fileName: String? = null,
    val mimeType: String? = null,
    val mediaKind: String? = null,
    val stableMediaId: String? = null,
    val sessionRevision: Long? = null,
    val frameUrl: String? = null,
) {
    fun toAutomationCommandDraft(originPackage: String? = null): AutomationCommandDraft = AutomationCommandDraft(
        source = AutomationCommandSource.BrowserExtension,
        action = action,
        url = url,
        fileName = fileName,
        pageTitle = pageTitle,
        pageUrl = pageUrl,
        originPackage = originPackage,
        mimeType = mimeType ?: mediaKind.toMimeTypeHint(),
        mediaKind = mediaKind,
        stableMediaId = stableMediaId,
        sessionRevision = sessionRevision,
        frameUrl = frameUrl,
    )
}

private fun String?.toMimeTypeHint(): String? = when (this?.trim()?.lowercase()) {
    "hls", "m3u8", "hlsplaylist", "application/vnd.apple.mpegurl" -> "application/vnd.apple.mpegurl"
    "dash", "mpd", "dashmanifest", "application/dash+xml" -> "application/dash+xml"
    "video", "mp4", "progressive" -> "video/mp4"
    "audio" -> "audio/mp4"
    else -> null
}
