package com.mikeyphw.xdm.android.scheduler

import java.util.concurrent.ConcurrentHashMap

/** Short-lived, process-local handoff for resolver-selected media requests. */
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

    fun remember(
        downloadId: String,
        headers: Map<String, String>,
        redactedSummary: String,
        isExpiringUrl: Boolean,
        cleanupActions: List<String> = emptyList(),
        tempCookieFileName: String? = null,
    ) {
        if (downloadId.isBlank()) return
        if (handoffs.size >= MaxEntries) handoffs.keys.firstOrNull()?.let(handoffs::remove)
        handoffs[downloadId] = MediaRequestHandoff(
            headers = headers.filterKeys(::isSafeHeaderName).filterValues(::isSafeHeaderValue),
            redactedSummary = redactedSummary.take(500),
            isExpiringUrl = isExpiringUrl,
            cleanupActions = cleanupActions.map { it.take(120) },
            tempCookieFileName = tempCookieFileName?.take(96),
        )
    }

    fun forDownload(downloadId: String): MediaRequestHandoff? = handoffs[downloadId]

    fun forget(downloadId: String) {
        handoffs.remove(downloadId)
    }

    fun verifyForgotten(downloadId: String): Boolean = !handoffs.containsKey(downloadId)

    private fun isSafeHeaderName(name: String): Boolean = name.isNotBlank() && name.none { it == '\r' || it == '\n' }
    private fun isSafeHeaderValue(value: String): Boolean = value.none { it == '\r' || it == '\n' }
}
