package com.mikeyphw.xdm.android.model

import java.util.Locale

enum class MimePresentationKind {
    AdaptiveMedia,
    Video,
    Audio,
    Image,
    Pdf,
    Archive,
    Document,
    Spreadsheet,
    Presentation,
    Text,
    Code,
    Subtitle,
    Playlist,
    Package,
    Torrent,
    Font,
    Calendar,
    Contact,
    Binary,
    Generic,
    Download,
}

data class MimePresentation(
    val kind: MimePresentationKind,
    val label: String,
    val extensionLabel: String? = null,
)

/** Central, MIME-first visual classification used by downloads and media surfaces. */
object MimePresentationResolver {
    private val adaptiveMimes = setOf(
        "application/vnd.apple.mpegurl",
        "application/x-mpegurl",
        "application/dash+xml",
        "video/vnd.mpeg.dash.mpd",
        "application/mpd",
    )
    private val archiveMimes = setOf(
        "application/zip", "application/x-7z-compressed", "application/x-rar-compressed",
        "application/vnd.rar", "application/x-tar", "application/gzip", "application/x-gzip",
        "application/x-bzip2", "application/x-xz", "application/zstd",
    )
    private val documentMimes = setOf(
        "application/msword", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.oasis.opendocument.text", "application/rtf",
    )
    private val spreadsheetMimes = setOf(
        "application/vnd.ms-excel", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.oasis.opendocument.spreadsheet", "text/csv",
    )
    private val presentationMimes = setOf(
        "application/vnd.ms-powerpoint", "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "application/vnd.oasis.opendocument.presentation",
    )
    private val packageMimes = setOf(
        "application/vnd.android.package-archive", "application/x-xpinstall",
        "application/vnd.microsoft.portable-executable", "application/x-debian-package",
    )
    private val subtitleMimes = setOf(
        "text/vtt", "application/x-subrip", "application/ttml+xml", "application/x-ssa", "application/x-ass",
    )
    private val playlistMimes = setOf(
        "audio/x-mpegurl", "audio/mpegurl", "application/xspf+xml", "audio/x-scpls",
    )
    private val codeMimes = setOf(
        "application/json", "application/ld+json", "application/xml", "text/xml",
        "application/javascript", "text/javascript", "application/x-sh", "text/x-shellscript",
    )

    fun resolve(fileName: String, mimeType: String?): MimePresentation {
        val mime = mimeType.orEmpty().substringBefore(';').trim().lowercase(Locale.ROOT)
        val ext = extensionOf(fileName)
        val mimeKind = kindFromMime(mime)
        val kind = mimeKind ?: kindFromExtension(ext) ?: when {
            mime == "application/octet-stream" || mime.startsWith("application/x-binary") -> MimePresentationKind.Binary
            fileName.isBlank() && mime.isBlank() -> MimePresentationKind.Download
            else -> MimePresentationKind.Generic
        }
        val extensionLabel = ext.takeIf(String::isNotBlank)?.uppercase(Locale.ROOT)
        return MimePresentation(kind, labelFor(kind), extensionLabel)
    }

    private fun kindFromMime(mime: String): MimePresentationKind? = when {
        mime.isBlank() || mime == "application/octet-stream" || mime.startsWith("application/x-binary") -> null
        mime in adaptiveMimes -> MimePresentationKind.AdaptiveMedia
        mime.startsWith("video/") -> MimePresentationKind.Video
        mime.startsWith("audio/") -> MimePresentationKind.Audio
        mime.startsWith("image/") -> MimePresentationKind.Image
        mime == "application/pdf" -> MimePresentationKind.Pdf
        mime in archiveMimes -> MimePresentationKind.Archive
        mime in spreadsheetMimes -> MimePresentationKind.Spreadsheet
        mime in presentationMimes -> MimePresentationKind.Presentation
        mime in documentMimes -> MimePresentationKind.Document
        mime in subtitleMimes -> MimePresentationKind.Subtitle
        mime in playlistMimes -> MimePresentationKind.Playlist
        mime in packageMimes -> MimePresentationKind.Package
        mime == "application/x-bittorrent" -> MimePresentationKind.Torrent
        mime.startsWith("font/") || mime in setOf("application/font-sfnt", "application/vnd.ms-fontobject") -> MimePresentationKind.Font
        mime in setOf("text/calendar", "application/ics") -> MimePresentationKind.Calendar
        mime in setOf("text/vcard", "text/x-vcard") -> MimePresentationKind.Contact
        mime in codeMimes -> MimePresentationKind.Code
        mime.startsWith("text/") -> MimePresentationKind.Text
        else -> MimePresentationKind.Generic
    }

    private fun kindFromExtension(ext: String): MimePresentationKind? = when (ext) {
        "m3u8", "mpd" -> MimePresentationKind.AdaptiveMedia
        "mp4", "mkv", "webm", "mov", "avi", "m4v", "ts", "mts", "m2ts", "3gp" -> MimePresentationKind.Video
        "mp3", "flac", "m4a", "aac", "ogg", "opus", "wav", "wma", "aiff" -> MimePresentationKind.Audio
        "png", "jpg", "jpeg", "webp", "gif", "bmp", "avif", "heic", "heif", "svg" -> MimePresentationKind.Image
        "pdf" -> MimePresentationKind.Pdf
        "zip", "7z", "rar", "tar", "gz", "tgz", "bz2", "xz", "zst" -> MimePresentationKind.Archive
        "xls", "xlsx", "ods", "csv", "tsv" -> MimePresentationKind.Spreadsheet
        "ppt", "pptx", "odp", "key" -> MimePresentationKind.Presentation
        "doc", "docx", "odt", "rtf" -> MimePresentationKind.Document
        "srt", "vtt", "ass", "ssa", "ttml" -> MimePresentationKind.Subtitle
        "m3u", "pls", "xspf" -> MimePresentationKind.Playlist
        "apk", "apks", "xapk", "aab", "exe", "msi", "deb", "rpm", "xpi" -> MimePresentationKind.Package
        "torrent" -> MimePresentationKind.Torrent
        "ttf", "otf", "woff", "woff2", "eot" -> MimePresentationKind.Font
        "ics" -> MimePresentationKind.Calendar
        "vcf", "vcard" -> MimePresentationKind.Contact
        "json", "xml", "html", "htm", "css", "js", "kt", "kts", "java", "py", "sh", "go", "rs", "c", "cpp", "h", "hpp", "yaml", "yml", "toml" -> MimePresentationKind.Code
        "txt", "md", "log", "ini", "conf" -> MimePresentationKind.Text
        "bin", "img", "iso", "dat" -> MimePresentationKind.Binary
        else -> null
    }

    fun contentDescription(fileName: String, mimeType: String?): String {
        val presentation = resolve(fileName, mimeType)
        return listOfNotNull(presentation.extensionLabel, presentation.label).joinToString(" ")
    }

    private fun extensionOf(fileName: String): String = fileName
        .substringBefore('?')
        .substringBefore('#')
        .substringAfterLast('/', fileName)
        .substringAfterLast('.', "")
        .lowercase(Locale.ROOT)
        .take(12)

    private fun labelFor(kind: MimePresentationKind): String = when (kind) {
        MimePresentationKind.AdaptiveMedia -> "adaptive media"
        MimePresentationKind.Video -> "video"
        MimePresentationKind.Audio -> "audio"
        MimePresentationKind.Image -> "image"
        MimePresentationKind.Pdf -> "PDF document"
        MimePresentationKind.Archive -> "archive"
        MimePresentationKind.Document -> "document"
        MimePresentationKind.Spreadsheet -> "spreadsheet"
        MimePresentationKind.Presentation -> "presentation"
        MimePresentationKind.Text -> "text document"
        MimePresentationKind.Code -> "code file"
        MimePresentationKind.Subtitle -> "subtitle file"
        MimePresentationKind.Playlist -> "playlist"
        MimePresentationKind.Package -> "application package"
        MimePresentationKind.Torrent -> "torrent"
        MimePresentationKind.Font -> "font"
        MimePresentationKind.Calendar -> "calendar file"
        MimePresentationKind.Contact -> "contact file"
        MimePresentationKind.Binary -> "binary file"
        MimePresentationKind.Generic -> "file"
        MimePresentationKind.Download -> "download"
    }
}
