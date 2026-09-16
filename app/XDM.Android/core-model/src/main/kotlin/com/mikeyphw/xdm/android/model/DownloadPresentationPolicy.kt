package com.mikeyphw.xdm.android.model

import java.net.URI
import java.util.Locale

/**
 * User-facing identity and lifecycle wording for a Download.
 *
 * Internal ids, attempt invariants, and publication exceptions belong in Details/diagnostics. The
 * Downloads list and lock-screen notifications should instead explain which user-visible phase
 * succeeded and which phase needs attention.
 */
object DownloadPresentationPolicy {
    private val uuidName = Regex("(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
    private val opaqueHexName = Regex("(?i)^[0-9a-f]{32,64}$")

    fun displayName(download: Download): String = humanReadableName(
        requestedName = download.fileName,
        sourceUrl = download.sourceUrl,
        pageTitle = download.userLabel,
        mimeType = download.mimeType,
    )

    fun resolvedFileName(
        sourceUrl: String,
        requestedName: String,
        pageTitle: String? = null,
        mimeType: String? = null,
    ): String = humanReadableName(requestedName, sourceUrl, pageTitle, mimeType)

    fun sourceHost(sourceUrl: String): String = runCatching {
        URI(sourceUrl).host?.lowercase(Locale.US)?.removePrefix("www.")?.takeIf(String::isNotBlank)
    }.getOrNull().orEmpty()

    fun isOpaqueIdentifierName(value: String): Boolean {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return false
        val base = splitExtension(trimmed).first
        return uuidName.matches(base) || opaqueHexName.matches(base)
    }

    fun isFinalSaveRecovery(download: Download): Boolean =
        download.state == DownloadState.RecoveryRequired && isRetryableFinalSaveMessage(download.errorMessage)

    fun isFinalizationFailure(download: Download): Boolean {
        val message = download.errorMessage.orEmpty()
        if (isRetryableFinalSaveMessage(message)) return true
        if (message.contains("Publication generation requires a positive attempt generation", ignoreCase = true)) return true
        if (message.contains("final publication", ignoreCase = true) && transferPayloadComplete(download)) return true
        return false
    }

    fun finalizationFailureSummary(download: Download): String? = if (isFinalizationFailure(download)) {
        "The file was transferred, but XDM couldn't finish saving it."
    } else null

    fun transferPayloadComplete(download: Download): Boolean {
        val total = download.totalBytes ?: return false
        return total > 0L && download.bytesReceived >= total
    }

    fun isRetryableFinalSaveMessage(message: String?): Boolean =
        message.orEmpty().trim().startsWith("Final save failed", ignoreCase = true)

    fun isFinalizationFailureMessage(message: String?): Boolean {
        val value = message.orEmpty()
        return isRetryableFinalSaveMessage(value) ||
            value.contains("Publication generation requires a positive attempt generation", ignoreCase = true)
    }

    private fun humanReadableName(
        requestedName: String,
        sourceUrl: String,
        pageTitle: String?,
        mimeType: String?,
    ): String {
        val requested = requestedName.trim().substringAfterLast('/').substringAfterLast('\\')
        if (requested.isNotBlank() && !isOpaqueIdentifierName(requested)) return requested

        val pathName = runCatching {
            URI(sourceUrl).path.orEmpty().substringAfterLast('/').trim()
        }.getOrNull().orEmpty()
        if (pathName.isNotBlank() && !isOpaqueIdentifierName(pathName)) return pathName

        val extension = sequenceOf(requested, pathName)
            .map(::splitExtension)
            .map { it.second }
            .firstOrNull(String::isNotBlank)
            ?: extensionForMime(mimeType)

        val title = pageTitle
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.takeUnless(::isOpaqueIdentifierName)
            ?.take(120)
        if (title != null) return appendExtensionIfMissing(title, extension)

        val host = sourceHost(sourceUrl)
        val hostBase = host
            .substringBefore(':')
            .replace(Regex("[^A-Za-z0-9._-]+"), "-")
            .trim('-', '.', '_')
            .takeIf(String::isNotBlank)
            ?: "download"
        return appendExtensionIfMissing("download-$hostBase", extension)
    }

    private fun splitExtension(name: String): Pair<String, String> {
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.lastIndex) return name to ""
        val extension = name.substring(dot + 1)
        if (extension.length !in 1..10 || extension.any { !it.isLetterOrDigit() }) return name to ""
        return name.substring(0, dot) to ".${extension.lowercase(Locale.US)}"
    }

    private fun appendExtensionIfMissing(value: String, extension: String): String = when {
        extension.isBlank() -> value
        value.endsWith(extension, ignoreCase = true) -> value
        else -> "$value$extension"
    }

    private fun extensionForMime(mimeType: String?): String = when (mimeType?.substringBefore(';')?.trim()?.lowercase(Locale.US)) {
        "application/zip" -> ".zip"
        "application/pdf" -> ".pdf"
        "application/vnd.android.package-archive" -> ".apk"
        "video/mp4" -> ".mp4"
        "video/x-matroska" -> ".mkv"
        "audio/mpeg" -> ".mp3"
        "audio/mp4" -> ".m4a"
        else -> ""
    }
}
