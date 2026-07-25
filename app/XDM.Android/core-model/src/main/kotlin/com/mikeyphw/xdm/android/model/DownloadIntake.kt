package com.mikeyphw.xdm.android.model

import java.net.URI
import java.util.Locale
import java.util.UUID

/** Origin of a review-first download draft. The intake model is deliberately independent of
 * external activities, Compose, persistence, and transfer execution. */
enum class DownloadIntakeOrigin {
    ExternalShare,
    ExternalView,
    ExternalDownloadManager,
    Automation,
    Clipboard,
    BuiltInBrowserPage,
    BuiltInBrowserDownload,
}

/** Semantic handoff classification used by Add Download and the media workbench.
 * Classification is advisory only: it never starts a transfer or bypasses user review. */
enum class DownloadIntakeKind {
    DirectFile,
    DirectMedia,
    AdaptiveMedia,
    Torrent,
    PageOrUnknown,
}

object DownloadIntakeClassifier {
    fun classify(url: String, fileName: String? = null, mimeType: String? = null): DownloadIntakeKind {
        val normalizedMime = mimeType
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase(Locale.US)
        val lowerPath = runCatching { URI(url).path.orEmpty().lowercase(Locale.US) }
            .getOrDefault(url.lowercase(Locale.US))
        val lowerName = fileName?.trim()?.lowercase(Locale.US).orEmpty()
        val searchableName = lowerName.ifBlank { lowerPath }

        return when {
            normalizedMime in AdaptiveMimeTypes || searchableName.hasAnySuffix(AdaptiveExtensions) -> DownloadIntakeKind.AdaptiveMedia
            normalizedMime == "application/x-bittorrent" || searchableName.hasAnySuffix(setOf(".torrent")) -> DownloadIntakeKind.Torrent
            normalizedMime?.startsWith("video/") == true ||
                normalizedMime?.startsWith("audio/") == true ||
                searchableName.hasAnySuffix(MediaExtensions) -> DownloadIntakeKind.DirectMedia
            normalizedMime in DirectFileMimeTypes || searchableName.hasAnySuffix(DirectFileExtensions) -> DownloadIntakeKind.DirectFile
            else -> DownloadIntakeKind.PageOrUnknown
        }
    }

    private fun String.hasAnySuffix(suffixes: Set<String>): Boolean = suffixes.any { suffix -> endsWith(suffix) }

    private val AdaptiveMimeTypes = setOf(
        "application/vnd.apple.mpegurl",
        "application/x-mpegurl",
        "audio/mpegurl",
        "audio/x-mpegurl",
        "application/dash+xml",
        "video/vnd.mpeg.dash.mpd",
        "application/mpd",
    )
    private val AdaptiveExtensions = setOf(".m3u8", ".mpd")
    private val MediaExtensions = setOf(
        ".mp4", ".m4v", ".mkv", ".webm", ".mov", ".avi", ".ts",
        ".mp3", ".m4a", ".aac", ".flac", ".ogg", ".opus", ".wav",
    )
    private val DirectFileExtensions = setOf(
        ".apk", ".apks", ".xapk", ".zip", ".7z", ".rar", ".tar", ".gz", ".xz", ".bz2",
        ".pdf", ".epub", ".iso", ".img", ".bin", ".exe", ".msi", ".deb", ".rpm",
        ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx", ".txt", ".json", ".xml",
    )
    private val DirectFileMimeTypes = setOf(
        "application/octet-stream",
        "application/vnd.android.package-archive",
        "application/zip",
        "application/x-7z-compressed",
        "application/x-rar-compressed",
        "application/pdf",
        "application/epub+zip",
    )
}

/** Browser-neutral handoff presented by Add Download before any transfer is created. */
data class DownloadIntakeDraft(
    val id: String,
    val url: String,
    val fileName: String = "",
    val sourceLabel: String = "External app",
    val origin: DownloadIntakeOrigin,
    val pageTitle: String? = null,
    val pageUrl: String? = null,
    val mimeType: String? = null,
    val contentLength: Long? = null,
    val kind: DownloadIntakeKind = DownloadIntakeClassifier.classify(url, fileName, mimeType),
) {
    val host: String?
        get() = runCatching { URI(url).host?.lowercase(Locale.US)?.takeIf(String::isNotBlank) }.getOrNull()

    val canInspectAsMedia: Boolean
        get() = kind == DownloadIntakeKind.DirectMedia ||
            kind == DownloadIntakeKind.AdaptiveMedia ||
            kind == DownloadIntakeKind.PageOrUnknown
}

/** Pure planner that normalizes untrusted handoff values and creates review-only drafts.
 * It never persists data, chooses a backend, creates a Download, or starts execution. */
class DownloadIntakePlanner(
    private val idFactory: (String) -> String = { prefix -> "$prefix-${UUID.randomUUID()}" },
) {
    fun fromBuiltInBrowserPage(
        url: String,
        pageTitle: String? = null,
    ): DownloadIntakeDraft? = create(
        prefix = "page-review",
        url = url,
        fileName = null,
        sourceLabel = pageTitle.cleanLabel()?.let { "Page: $it" } ?: "Page URL",
        origin = DownloadIntakeOrigin.BuiltInBrowserPage,
        pageTitle = pageTitle,
        pageUrl = url,
        allowedSchemes = HttpSchemes,
    )

    fun fromBuiltInBrowserDownload(
        url: String,
        fileName: String? = null,
        pageTitle: String? = null,
        pageUrl: String? = null,
        mimeType: String? = null,
        contentLength: Long? = null,
    ): DownloadIntakeDraft? = create(
        prefix = "detected-download",
        url = url,
        fileName = fileName,
        sourceLabel = pageTitle.cleanLabel()?.let { "Detected download: $it" } ?: "Detected download",
        origin = DownloadIntakeOrigin.BuiltInBrowserDownload,
        pageTitle = pageTitle,
        pageUrl = pageUrl,
        mimeType = mimeType,
        contentLength = contentLength,
        allowedSchemes = HttpSchemes,
    )

    fun fromExternal(
        id: String? = null,
        url: String,
        fileName: String? = null,
        sourceLabel: String = "External app",
        origin: DownloadIntakeOrigin,
        pageTitle: String? = null,
        pageUrl: String? = null,
        mimeType: String? = null,
        contentLength: Long? = null,
    ): DownloadIntakeDraft? = create(
        prefix = "external-review",
        explicitId = id,
        url = url,
        fileName = fileName,
        sourceLabel = sourceLabel,
        origin = origin,
        pageTitle = pageTitle,
        pageUrl = pageUrl,
        mimeType = mimeType,
        contentLength = contentLength,
        allowedSchemes = DownloadSchemes,
    )

    private fun create(
        prefix: String,
        explicitId: String? = null,
        url: String,
        fileName: String?,
        sourceLabel: String,
        origin: DownloadIntakeOrigin,
        pageTitle: String?,
        pageUrl: String?,
        mimeType: String? = null,
        contentLength: Long? = null,
        allowedSchemes: Set<String>,
    ): DownloadIntakeDraft? {
        val normalizedUrl = ExternalUrlPolicy.normalizedUrl(url) ?: return null
        val scheme = runCatching { URI(normalizedUrl).scheme?.lowercase(Locale.US) }.getOrNull() ?: return null
        if (scheme !in allowedSchemes) return null
        val cleanFileName = fileName.cleanFileName()
        val cleanMimeType = mimeType.cleanMimeType()
        return DownloadIntakeDraft(
            id = explicitId?.trim()?.takeIf(String::isNotBlank) ?: idFactory(prefix),
            url = normalizedUrl,
            fileName = cleanFileName,
            sourceLabel = sourceLabel.cleanLabel() ?: "External app",
            origin = origin,
            pageTitle = pageTitle.cleanText(160),
            pageUrl = ExternalUrlPolicy.normalizedUrl(pageUrl),
            mimeType = cleanMimeType,
            contentLength = contentLength?.takeIf { it > 0L },
            kind = DownloadIntakeClassifier.classify(normalizedUrl, cleanFileName, cleanMimeType),
        )
    }

    private fun String?.cleanFileName(): String = this
        ?.trim()
        ?.substringAfterLast('/')
        ?.substringAfterLast('\\')
        ?.take(160)
        .orEmpty()

    private fun String?.cleanLabel(): String? = cleanText(64)

    private fun String?.cleanText(maxLength: Int): String? = this
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.take(maxLength)

    private fun String?.cleanMimeType(): String? = this
        ?.substringBefore(';')
        ?.trim()
        ?.lowercase(Locale.US)
        ?.takeIf { '/' in it && it.length <= 120 }

    private companion object {
        val HttpSchemes = setOf("http", "https")
        val DownloadSchemes = setOf("http", "https", "ftp")
    }
}
