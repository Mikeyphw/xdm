package com.mikeyphw.xdm.android.scheduler

import com.mikeyphw.xdm.android.model.ExternalUrlPolicy
import java.util.concurrent.ConcurrentHashMap

/** Encrypted, scoped process-local handoff for browser sessions and signed URLs.
 * The process cache is only an optimization; AndroidSecureRequestEnvelopeStore is authoritative
 * after initialization so pause, process death, and retry do not silently lose authentication. */
data class MediaRequestHandoff(
    val exactUrl: String? = null,
    val boundHost: String? = null,
    val pageUrl: String? = null,
    val headers: Map<String, String>,
    val redactedSummary: String,
    val isExpiringUrl: Boolean,
    val expiresAtEpochMs: Long,
    val attemptGeneration: Long = 0L,
    val privateNetworkApproved: Boolean = false,
    val cleartextCredentialsApproved: Boolean = false,
    val cleanupActions: List<String> = emptyList(),
    val tempCookieFileName: String? = null,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
)

object MediaRequestHandoffStore {
    private const val MaxEntries = 128
    private const val DefaultExpiryMs = 24L * 60L * 60L * 1000L
    private val cache = ConcurrentHashMap<String, MediaRequestHandoff>()
    @Volatile private var durableStore: SecureRequestEnvelopeStore = InMemorySecureRequestEnvelopeStore()

    fun initialize(store: SecureRequestEnvelopeStore) {
        durableStore = store
        durableStore.deleteExpired()
    }

    fun remember(
        downloadId: String,
        headers: Map<String, String>,
        redactedSummary: String,
        isExpiringUrl: Boolean,
        exactUrl: String? = null,
        pageUrl: String? = null,
        expiresAtEpochMs: Long = defaultExpiry(isExpiringUrl),
        attemptGeneration: Long = 0L,
        privateNetworkApproved: Boolean = false,
        cleartextCredentialsApproved: Boolean = false,
        cleanupActions: List<String> = emptyList(),
        tempCookieFileName: String? = null,
    ) = rememberSubject(
        subjectId = subject(DOWNLOAD_PREFIX, downloadId),
        headers = headers,
        redactedSummary = redactedSummary,
        isExpiringUrl = isExpiringUrl,
        exactUrl = exactUrl,
        pageUrl = pageUrl,
        expiresAtEpochMs = expiresAtEpochMs,
        attemptGeneration = attemptGeneration,
        privateNetworkApproved = privateNetworkApproved,
        cleartextCredentialsApproved = cleartextCredentialsApproved,
        cleanupActions = cleanupActions,
        tempCookieFileName = tempCookieFileName,
    )

    fun rememberCapture(
        captureId: String,
        headers: Map<String, String>,
        redactedSummary: String,
        isExpiringUrl: Boolean,
        exactUrl: String? = null,
        pageUrl: String? = null,
        expiresAtEpochMs: Long = defaultExpiry(isExpiringUrl),
        privateNetworkApproved: Boolean = false,
        cleartextCredentialsApproved: Boolean = false,
    ) = rememberSubject(
        subjectId = subject(CAPTURE_PREFIX, captureId),
        headers = headers,
        redactedSummary = redactedSummary,
        isExpiringUrl = isExpiringUrl,
        exactUrl = exactUrl,
        pageUrl = pageUrl,
        expiresAtEpochMs = expiresAtEpochMs,
        privateNetworkApproved = privateNetworkApproved,
        cleartextCredentialsApproved = cleartextCredentialsApproved,
    )

    fun rememberVariant(
        variantId: String,
        exactUrl: String,
        headers: Map<String, String> = emptyMap(),
        redactedSummary: String = "",
        expiresAtEpochMs: Long = defaultExpiry(true),
    ) = rememberSubject(
        subjectId = subject(VARIANT_PREFIX, variantId),
        headers = headers,
        redactedSummary = redactedSummary,
        isExpiringUrl = true,
        exactUrl = exactUrl,
        expiresAtEpochMs = expiresAtEpochMs,
    )

    fun rememberCommand(
        commandId: String,
        exactUrl: String?,
        pageUrl: String?,
        headers: Map<String, String>,
        redactedSummary: String,
        privateNetworkApproved: Boolean,
        cleartextCredentialsApproved: Boolean,
        expiresAtEpochMs: Long = defaultExpiry(true),
    ) = rememberSubject(
        subjectId = subject(COMMAND_PREFIX, commandId),
        headers = headers,
        redactedSummary = redactedSummary,
        isExpiringUrl = headers.isNotEmpty() || exactUrl != null,
        exactUrl = exactUrl,
        pageUrl = pageUrl,
        expiresAtEpochMs = expiresAtEpochMs,
        privateNetworkApproved = privateNetworkApproved,
        cleartextCredentialsApproved = cleartextCredentialsApproved,
    )

    fun cloneDownload(sourceDownloadId: String, targetDownloadId: String, replacementExactUrl: String? = null): Boolean {
        val source = forDownload(sourceDownloadId) ?: return false
        remember(
            downloadId = targetDownloadId,
            headers = source.headers,
            redactedSummary = source.redactedSummary,
            isExpiringUrl = source.isExpiringUrl || replacementExactUrl != null,
            exactUrl = replacementExactUrl ?: source.exactUrl,
            pageUrl = source.pageUrl,
            expiresAtEpochMs = source.expiresAtEpochMs,
            attemptGeneration = 0L,
            privateNetworkApproved = source.privateNetworkApproved,
            cleartextCredentialsApproved = source.cleartextCredentialsApproved,
            cleanupActions = source.cleanupActions,
            tempCookieFileName = source.tempCookieFileName,
        )
        return true
    }

    fun replaceDownloadUrl(downloadId: String, exactUrl: String): Boolean {
        val source = forDownload(downloadId)
        remember(
            downloadId = downloadId,
            headers = source?.headers.orEmpty().takeIf { source?.boundHost == ExternalUrlPolicy.originHost(exactUrl) }.orEmpty(),
            redactedSummary = source?.redactedSummary.orEmpty(),
            isExpiringUrl = source?.isExpiringUrl == true || ExternalUrlPolicy.hasCredentialBearingQuery(exactUrl),
            exactUrl = exactUrl,
            pageUrl = source?.pageUrl,
            expiresAtEpochMs = source?.expiresAtEpochMs ?: defaultExpiry(true),
            attemptGeneration = source?.attemptGeneration ?: 0L,
            privateNetworkApproved = source?.privateNetworkApproved == true,
            cleartextCredentialsApproved = source?.cleartextCredentialsApproved == true,
            cleanupActions = source?.cleanupActions.orEmpty(),
            tempCookieFileName = source?.tempCookieFileName,
        )
        return true
    }

    fun forDownload(downloadId: String): MediaRequestHandoff? = readSubject(subject(DOWNLOAD_PREFIX, downloadId))
    fun forCapture(captureId: String): MediaRequestHandoff? = readSubject(subject(CAPTURE_PREFIX, captureId))
    fun forVariant(variantId: String): MediaRequestHandoff? = readSubject(subject(VARIANT_PREFIX, variantId))
    fun forCommand(commandId: String): MediaRequestHandoff? = readSubject(subject(COMMAND_PREFIX, commandId))

    fun forget(downloadId: String) = forgetSubject(subject(DOWNLOAD_PREFIX, downloadId))
    fun forgetCapture(captureId: String) = forgetSubject(subject(CAPTURE_PREFIX, captureId))
    fun forgetVariant(variantId: String) = forgetSubject(subject(VARIANT_PREFIX, variantId))
    fun forgetCommand(commandId: String) = forgetSubject(subject(COMMAND_PREFIX, commandId))

    fun verifyForgotten(downloadId: String): Boolean = forDownload(downloadId) == null

    private fun rememberSubject(
        subjectId: String,
        headers: Map<String, String>,
        redactedSummary: String,
        isExpiringUrl: Boolean,
        exactUrl: String? = null,
        pageUrl: String? = null,
        expiresAtEpochMs: Long = defaultExpiry(isExpiringUrl),
        attemptGeneration: Long = 0L,
        privateNetworkApproved: Boolean = false,
        cleartextCredentialsApproved: Boolean = false,
        cleanupActions: List<String> = emptyList(),
        tempCookieFileName: String? = null,
    ) {
        if (subjectId.substringAfter(':').isBlank()) return
        val safeHeaders = headers.filterKeys(::isSafeHeaderName).filterValues(::isSafeHeaderValue)
        val handoff = MediaRequestHandoff(
            exactUrl = exactUrl?.trim()?.takeIf(String::isNotBlank),
            boundHost = ExternalUrlPolicy.originHost(exactUrl),
            pageUrl = pageUrl?.trim()?.takeIf(String::isNotBlank),
            headers = safeHeaders,
            redactedSummary = redactedSummary.take(500),
            isExpiringUrl = isExpiringUrl,
            expiresAtEpochMs = expiresAtEpochMs,
            attemptGeneration = attemptGeneration,
            privateNetworkApproved = privateNetworkApproved,
            cleartextCredentialsApproved = cleartextCredentialsApproved,
            cleanupActions = cleanupActions.map { it.take(120) },
            tempCookieFileName = tempCookieFileName?.take(96),
        )
        if (handoff.headers.isEmpty() && handoff.exactUrl == null && handoff.pageUrl == null && handoff.redactedSummary.isBlank()) return
        evictOldestIfNeeded()
        cache[subjectId] = handoff
        durableStore.put(handoff.toEnvelope(subjectId))
    }

    private fun readSubject(subjectId: String): MediaRequestHandoff? {
        val now = System.currentTimeMillis()
        cache[subjectId]?.let { cached ->
            if (cached.expiresAtEpochMs > now) return cached
            cache.remove(subjectId)
        }
        val envelope = durableStore.get(subjectId, now) ?: return null
        val handoff = envelope.toHandoff()
        evictOldestIfNeeded()
        cache[subjectId] = handoff
        return handoff
    }

    private fun forgetSubject(subjectId: String) {
        cache.remove(subjectId)
        durableStore.delete(subjectId)
    }

    private fun evictOldestIfNeeded() {
        while (cache.size >= MaxEntries) {
            val oldest = cache.entries.minByOrNull { it.value.createdAtEpochMs } ?: return
            cache.remove(oldest.key)
            // Cache eviction is not credential deletion. Durable envelopes remain until expiry or
            // explicit terminal cleanup, so process pressure cannot silently break a transfer.
        }
    }

    private fun MediaRequestHandoff.toEnvelope(subjectId: String) = SecureRequestEnvelope(
        subjectId = subjectId,
        exactUrl = exactUrl,
        boundHost = boundHost,
        pageUrl = pageUrl,
        headers = headers,
        redactedSummary = redactedSummary,
        isExpiringUrl = isExpiringUrl,
        expiresAtEpochMs = expiresAtEpochMs,
        attemptGeneration = attemptGeneration,
        privateNetworkApproved = privateNetworkApproved,
        cleartextCredentialsApproved = cleartextCredentialsApproved,
        cleanupActions = cleanupActions,
        tempCookieFileName = tempCookieFileName,
        createdAtEpochMs = createdAtEpochMs,
    )

    private fun SecureRequestEnvelope.toHandoff() = MediaRequestHandoff(
        exactUrl = exactUrl,
        boundHost = boundHost,
        pageUrl = pageUrl,
        headers = headers,
        redactedSummary = redactedSummary,
        isExpiringUrl = isExpiringUrl,
        expiresAtEpochMs = expiresAtEpochMs,
        attemptGeneration = attemptGeneration,
        privateNetworkApproved = privateNetworkApproved,
        cleartextCredentialsApproved = cleartextCredentialsApproved,
        cleanupActions = cleanupActions,
        tempCookieFileName = tempCookieFileName,
        createdAtEpochMs = createdAtEpochMs,
    )

    private fun subject(prefix: String, id: String) = "$prefix:$id"
    private fun isSafeHeaderName(name: String): Boolean = name.isNotBlank() && name.none { it == '\r' || it == '\n' }
    private fun isSafeHeaderValue(value: String): Boolean = value.none { it == '\r' || it == '\n' }
    private fun defaultExpiry(expiring: Boolean): Long = System.currentTimeMillis() + if (expiring) DefaultExpiryMs else 7L * DefaultExpiryMs

    private const val DOWNLOAD_PREFIX = "download"
    private const val CAPTURE_PREFIX = "capture"
    private const val VARIANT_PREFIX = "variant"
    private const val COMMAND_PREFIX = "command"
}
