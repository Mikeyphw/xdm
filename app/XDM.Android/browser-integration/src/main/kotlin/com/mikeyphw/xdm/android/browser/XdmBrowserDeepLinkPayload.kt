package com.mikeyphw.xdm.android.browser

import com.mikeyphw.xdm.android.model.AutomationCommandAction
import com.mikeyphw.xdm.android.model.AutomationCommandDraft
import com.mikeyphw.xdm.android.model.AutomationCommandSource

/** Sanitized data extracted from an XDM browser custom-scheme link. */
data class XdmBrowserDeepLinkPayload(
    val version: Int,
    val action: AutomationCommandAction,
    val url: String? = null,
    val pageUrl: String? = null,
    val pageTitle: String? = null,
    val fileName: String? = null,
    val mimeType: String? = null,
    val mediaKind: String? = null,
    val stableMediaId: String? = null,
    val sessionRevision: Long? = null,
    val contentLength: Long? = null,
    val durationMs: Long? = null,
    val thumbnailUrl: String? = null,
    val frameUrl: String? = null,
    val rawHeaders: String? = null,
    val proposedHeaders: String? = null,
    val finalHeaders: String? = null,
    val captureSessionId: String? = null,
    val captureKeyId: String? = null,
    val wrappedKey: String? = null,
    val envelopeIv: String? = null,
    val envelopeCiphertext: String? = null,
) {
    val hasEncryptedCaptureEnvelope: Boolean
        get() = version == XdmBrowserDeepLinkContract.EncryptedCaptureVersion &&
            !captureSessionId.isNullOrBlank() && !captureKeyId.isNullOrBlank() &&
            !wrappedKey.isNullOrBlank() && !envelopeIv.isNullOrBlank() && !envelopeCiphertext.isNullOrBlank()

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
        contentLength = contentLength,
        durationMs = durationMs,
        thumbnailUrl = thumbnailUrl,
        stableMediaId = stableMediaId,
        sessionRevision = sessionRevision,
        frameUrl = frameUrl,
        rawHeaders = rawHeaders,
        proposedHeaders = proposedHeaders,
        finalHeaders = finalHeaders,
    )
}

private fun String?.toMimeTypeHint(): String? = when (this?.trim()?.lowercase()) {
    "hls", "m3u8", "hlsplaylist", "application/vnd.apple.mpegurl" -> "application/vnd.apple.mpegurl"
    "dash", "mpd", "dashmanifest", "application/dash+xml" -> "application/dash+xml"
    "video", "mp4", "progressive" -> "video/mp4"
    "audio" -> "audio/mp4"
    else -> null
}
