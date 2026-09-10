package com.mikeyphw.xdm.android

import com.mikeyphw.xdm.android.model.DestinationHealthStatus
import com.mikeyphw.xdm.android.storage.DestinationHealth
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class DownloadPreflightState { Idle, Checking, Ready, Partial, Failed }
enum class DownloadFileNameSuggestionSource { ServerContentDisposition, RedirectTargetPath, LinkPath }

data class DownloadUrlPreflightUi(
    val requestedUrl: String = "",
    val state: DownloadPreflightState = DownloadPreflightState.Idle,
    val suggestedFileName: String? = null,
    val suggestedFileNameSource: DownloadFileNameSuggestionSource? = null,
    val mimeType: String? = null,
    val contentLength: Long? = null,
    val resumable: Boolean? = null,
    val redirected: Boolean = false,
    val finalHost: String? = null,
    val message: String? = null,
)

data class DestinationPreflightUi(
    val destinationUri: String = "",
    val state: DownloadPreflightState = DownloadPreflightState.Idle,
    val displayName: String = "",
    val status: DestinationHealthStatus? = null,
    val availableBytes: Long? = null,
    val message: String? = null,
) {
    val writable: Boolean
        get() = status == DestinationHealthStatus.Healthy || status == DestinationHealthStatus.LowSpace
}

internal fun DestinationHealth.toPreflightUi(): DestinationPreflightUi = DestinationPreflightUi(
    destinationUri = uri,
    state = DownloadPreflightState.Ready,
    displayName = displayName,
    status = status,
    availableBytes = availableBytes,
    message = message,
)

/** Advisory metadata probe for the New download review screen. It never blocks admission. */
internal class DownloadPreflightProbe {
    suspend fun probe(
        url: String,
        providedMimeType: String? = null,
        providedContentLength: Long? = null,
        allowNetwork: Boolean = true,
    ): DownloadUrlPreflightUi = withContext(Dispatchers.IO) {
        val raw = url.trim()
        if (raw.isBlank()) return@withContext DownloadUrlPreflightUi()
        val uri = runCatching { URI(raw) }.getOrNull()
        val scheme = uri?.scheme?.lowercase()
        val inferredName = suggestedNameFromUrl(raw)
        if (scheme !in setOf("http", "https") || !allowNetwork) {
            return@withContext DownloadUrlPreflightUi(
                requestedUrl = raw,
                state = if (providedMimeType != null || providedContentLength != null || inferredName != null) DownloadPreflightState.Partial else DownloadPreflightState.Ready,
                suggestedFileName = inferredName,
                suggestedFileNameSource = inferredName?.let { DownloadFileNameSuggestionSource.LinkPath },
                mimeType = providedMimeType,
                contentLength = providedContentLength,
                message = if (!allowNetwork) "Using metadata already supplied by the browser. XDM will verify the remote file when the download starts." else null,
            )
        }

        var connection: HttpURLConnection? = null
        try {
            connection = (URI(raw).toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                instanceFollowRedirects = true
                connectTimeout = 4_000
                readTimeout = 4_000
                useCaches = false
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("User-Agent", "XDM Android preflight")
            }
            val code = connection.responseCode
            val finalUrl = connection.url.toString()
            val contentType = connection.contentType?.substringBefore(';')?.trim()?.takeIf(String::isNotBlank) ?: providedMimeType
            val length = connection.getHeaderFieldLong("Content-Length", -1L).takeIf { it >= 0L } ?: providedContentLength
            val ranges = connection.getHeaderField("Accept-Ranges")?.contains("bytes", ignoreCase = true)
                ?.takeIf { connection.getHeaderField("Accept-Ranges") != null }
            val disposition = connection.getHeaderField("Content-Disposition")
            val finalHost = runCatching { URI(finalUrl).host }.getOrNull()
            val dispositionName = suggestedNameFromDisposition(disposition)
            val finalUrlName = suggestedNameFromUrl(finalUrl)
            val suggestedName = dispositionName ?: finalUrlName ?: inferredName
            val suggestedSource = when {
                dispositionName != null -> DownloadFileNameSuggestionSource.ServerContentDisposition
                finalUrlName != null && finalUrl != raw -> DownloadFileNameSuggestionSource.RedirectTargetPath
                suggestedName != null -> DownloadFileNameSuggestionSource.LinkPath
                else -> null
            }
            DownloadUrlPreflightUi(
                requestedUrl = raw,
                state = if (code in 200..399) DownloadPreflightState.Ready else DownloadPreflightState.Partial,
                suggestedFileName = suggestedName,
                suggestedFileNameSource = suggestedSource,
                mimeType = contentType,
                contentLength = length,
                resumable = ranges,
                redirected = finalUrl != raw,
                finalHost = finalHost,
                message = if (code in 200..399) null else "The server did not return normal metadata (HTTP $code). XDM will verify again when the transfer starts.",
            )
        } catch (error: Exception) {
            DownloadUrlPreflightUi(
                requestedUrl = raw,
                state = DownloadPreflightState.Partial,
                suggestedFileName = inferredName,
                suggestedFileNameSource = inferredName?.let { DownloadFileNameSuggestionSource.LinkPath },
                mimeType = providedMimeType,
                contentLength = providedContentLength,
                message = "Remote details are unavailable right now. This does not prevent downloading; XDM will verify them when the transfer starts.",
            )
        } finally {
            connection?.disconnect()
        }
    }

    private fun suggestedNameFromDisposition(contentDisposition: String?): String? {
        val headerName = contentDisposition
            ?.substringAfter("filename*=", missingDelimiterValue = "")
            ?.takeIf(String::isNotBlank)
            ?.substringBefore(';')
            ?.trim()
            ?.trim('"')
            ?.substringAfter("''")
            ?.let { runCatching { URLDecoder.decode(it, StandardCharsets.UTF_8.name()) }.getOrDefault(it) }
            ?: contentDisposition
                ?.substringAfter("filename=", missingDelimiterValue = "")
                ?.takeIf(String::isNotBlank)
                ?.substringBefore(';')
                ?.trim()
                ?.trim('"')
        return headerName?.takeIf(String::isNotBlank)
    }

    private fun suggestedNameFromUrl(url: String): String? = runCatching {
        URI(url).path.orEmpty().substringAfterLast('/').takeIf(String::isNotBlank)?.let {
            URLDecoder.decode(it, StandardCharsets.UTF_8.name())
        }
    }.getOrNull()

}
