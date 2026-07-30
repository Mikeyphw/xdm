package com.mikeyphw.xdm.android.scheduler

import java.util.concurrent.ConcurrentHashMap

/** Short-lived, process-local handoff for resolver-selected media and browser session requests.
 * Raw cookies and Authorization values never enter persistent storage, sidecars, or normal UI; they live only
 * long enough for the runtime to build the next DownloadRequest. */
data class MediaRequestHandoff(
    val headers: Map<String, String>,
    val redactedSummary: String,
    val isExpiringUrl: Boolean,
    val cleanupActions: List<String> = emptyList(),
    val tempCookieFileName: String? = null,
)

object MediaRequestHandoffStore {
    private const val MaxEntries = 128
    private val handoffs = ConcurrentHashMap<String, MediaRequestHandoff>()
    private val captureHandoffs = ConcurrentHashMap<String, MediaRequestHandoff>()

    fun remember(
        downloadId: String,
        headers: Map<String, String>,
        redactedSummary: String,
        isExpiringUrl: Boolean,
        cleanupActions: List<String> = emptyList(),
        tempCookieFileName: String? = null,
    ) {
        rememberInto(handoffs, downloadId, headers, redactedSummary, isExpiringUrl, cleanupActions, tempCookieFileName)
    }

    fun rememberCapture(
        captureId: String,
        headers: Map<String, String>,
        redactedSummary: String,
        isExpiringUrl: Boolean,
    ) {
        rememberInto(captureHandoffs, captureId, headers, redactedSummary, isExpiringUrl)
    }

    fun forDownload(downloadId: String): MediaRequestHandoff? = handoffs[downloadId]

    fun forCapture(captureId: String): MediaRequestHandoff? = captureHandoffs[captureId]

    fun forget(downloadId: String) {
        handoffs.remove(downloadId)
    }

    fun forgetCapture(captureId: String) {
        captureHandoffs.remove(captureId)
    }

    fun verifyForgotten(downloadId: String): Boolean = !handoffs.containsKey(downloadId)

    private fun rememberInto(
        target: ConcurrentHashMap<String, MediaRequestHandoff>,
        key: String,
        headers: Map<String, String>,
        redactedSummary: String,
        isExpiringUrl: Boolean,
        cleanupActions: List<String> = emptyList(),
        tempCookieFileName: String? = null,
    ) {
        if (key.isBlank()) return
        val safeHeaders = headers.filterKeys(::isSafeHeaderName).filterValues(::isSafeHeaderValue)
        if (safeHeaders.isEmpty() && redactedSummary.isBlank()) return
        if (target.size >= MaxEntries) target.keys.firstOrNull()?.let(target::remove)
        target[key] = MediaRequestHandoff(
            headers = safeHeaders,
            redactedSummary = redactedSummary.take(500),
            isExpiringUrl = isExpiringUrl,
            cleanupActions = cleanupActions.map { it.take(120) },
            tempCookieFileName = tempCookieFileName?.take(96),
        )
    }

    private fun isSafeHeaderName(name: String): Boolean = name.isNotBlank() && name.none { it == '\r' || it == '\n' }
    private fun isSafeHeaderValue(value: String): Boolean = value.none { it == '\r' || it == '\n' }
}
