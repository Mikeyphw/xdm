package com.mikeyphw.xdm.android.model

import java.net.URI
import java.security.MessageDigest
import java.util.Locale

enum class MediaTransferShape {
    DirectFile,
    DirectMedia,
    AdaptivePlaylist,
    SiteResolver,
    LiveRecording,
    ProtectedDiagnostic,
}

enum class BrowserHeaderObservationKind { ProposedBeforeSend, FinalSent, Unavailable }

data class BrowserHeaderObservation(
    val kind: BrowserHeaderObservationKind,
    val headers: Map<String, String>,
    val unavailableReason: String? = null,
) {
    val honestSummary: String get() = when (kind) {
        BrowserHeaderObservationKind.ProposedBeforeSend -> "proposed=${headers.size}"
        BrowserHeaderObservationKind.FinalSent -> "final=${headers.size}"
        BrowserHeaderObservationKind.Unavailable -> "unavailable=${unavailableReason ?: "unknown"}"
    }
}

data class BrowserFrameContext(
    val topPageUrl: String?,
    val frameUrl: String?,
    val requestUrl: String,
) {
    val effectiveReferer: String? get() = frameUrl?.takeIf(String::isNotBlank) ?: topPageUrl?.takeIf(String::isNotBlank)
    val preservesIframeContext: Boolean get() = !frameUrl.isNullOrBlank() && frameUrl != topPageUrl
}

data class BrowserMediaSessionRevision(
    val stableMediaId: String,
    val exactRequestUrl: String,
    val pageUrl: String?,
    val frameUrl: String?,
    val proposedHeaders: BrowserHeaderObservation,
    val finalHeaders: BrowserHeaderObservation,
    val revision: Long,
    val expiresAtEpochMs: Long,
    val acknowledgedByAndroid: Boolean = false,
) {
    val usableHeaders: Map<String, String>
        get() = if (finalHeaders.kind == BrowserHeaderObservationKind.FinalSent && finalHeaders.headers.isNotEmpty()) finalHeaders.headers else proposedHeaders.headers
    val isExpired: Boolean get() = expiresAtEpochMs <= System.currentTimeMillis()
    val redactedSummary: String get() = listOf(
        "media=$stableMediaId",
        "revision=$revision",
        "url=${PrivacyDiagnosticsRedactor.redactUrl(exactRequestUrl)}",
        "page=${PrivacyDiagnosticsRedactor.redactUrl(pageUrl)}",
        "frame=${PrivacyDiagnosticsRedactor.redactUrl(frameUrl)}",
        proposedHeaders.honestSummary,
        finalHeaders.honestSummary,
        if (acknowledgedByAndroid) "ack=android" else "ack=pending",
    ).joinToString(" • ")
}

enum class MediaSessionEvictionReason { Expired, Capacity, TerminalCapture, ManualForget }

data class BrowserMediaSessionEviction(
    val stableMediaId: String,
    val revision: Long,
    val reason: MediaSessionEvictionReason,
    val markCaptureSessionLost: Boolean,
)

data class PageObservationProof(
    val nonce: String,
    val originPackage: String?,
    val createdAtEpochMs: Long,
    val expiresAtEpochMs: Long,
) {
    fun accepts(nowEpochMs: Long = System.currentTimeMillis()): Boolean = nonce.length >= 16 && nowEpochMs in createdAtEpochMs..expiresAtEpochMs
}

enum class DrmEvidenceKind { BrowserEncryptionEvent, HlsKeyMetadata, DashContentProtection, ResolverReport }

data class DrmEvidence(
    val kind: DrmEvidenceKind,
    val scheme: String?,
    val sourceLabel: String,
)

data class ProtectedMediaClassification(
    val protected: Boolean,
    val evidence: List<DrmEvidence>,
    val diagnosticOnly: Boolean,
) {
    val reason: String get() = if (protected) "protected by ${evidence.joinToString { it.kind.name }}" else "no authoritative DRM evidence"
}

enum class BackendPreparationFailureCategory {
    RuntimeUnavailable,
    SourceUnsupported,
    DestinationUnsupported,
    PermissionRequired,
    TemporaryInitializationFailure,
    FatalConfiguration,
    SessionRefreshRequired,
    MediaResolutionRequired,
}

data class BackendFallbackDecision(
    val safeToFallback: Boolean,
    val category: BackendPreparationFailureCategory,
    val preservePartialBytes: Boolean,
    val reviewFirst: Boolean,
    val explanation: String,
) {
    companion object {
        fun forCategory(category: BackendPreparationFailureCategory, bytesWritten: Long): BackendFallbackDecision = when (category) {
            BackendPreparationFailureCategory.TemporaryInitializationFailure,
            BackendPreparationFailureCategory.RuntimeUnavailable -> BackendFallbackDecision(
                safeToFallback = bytesWritten == 0L,
                category = category,
                preservePartialBytes = bytesWritten > 0L,
                reviewFirst = bytesWritten > 0L,
                explanation = if (bytesWritten == 0L) "Safe pre-start fallback." else "Review required because bytes were already written.",
            )
            BackendPreparationFailureCategory.SessionRefreshRequired -> BackendFallbackDecision(false, category, bytesWritten > 0L, true, "Refresh browser session; do not switch engines blindly.")
            BackendPreparationFailureCategory.MediaResolutionRequired -> BackendFallbackDecision(false, category, bytesWritten > 0L, true, "Resolve playlist/site media before backend migration.")
            BackendPreparationFailureCategory.SourceUnsupported,
            BackendPreparationFailureCategory.DestinationUnsupported,
            BackendPreparationFailureCategory.PermissionRequired,
            BackendPreparationFailureCategory.FatalConfiguration -> BackendFallbackDecision(false, category, bytesWritten > 0L, true, "Review required for incompatible source, destination, permission, or configuration.")
        }
    }
}

data class BackendRejectionProvenance(
    val backendName: String,
    val rejectedRequirement: String,
    val phase: String,
    val trigger: String,
)

data class BackendFallbackProvenance(
    val requestedBackend: String,
    val selectedBackend: String,
    val rejected: List<BackendRejectionProvenance>,
    val fallbackPhase: String,
    val trigger: String,
    val bytesAlreadyWritten: Long,
    val partialDataDisposition: String,
    val sessionDataPreserved: Boolean,
) {
    val safeSummary: String get() = listOf(
        "requested=$requestedBackend",
        "selected=$selectedBackend",
        "fallback=$fallbackPhase",
        "trigger=$trigger",
        "bytes=$bytesAlreadyWritten",
        "partial=$partialDataDisposition",
        "sessionPreserved=$sessionDataPreserved",
        "rejected=${rejected.joinToString { it.backendName + ':' + it.rejectedRequirement }}",
    ).joinToString(" • ")
}

object BrowserHandoffMediaPolicy {
    fun stableMediaId(pageUrl: String?, frameUrl: String?, requestUrl: String, shape: MediaTransferShape): String {
        val host = runCatching { URI(requestUrl).host?.lowercase(Locale.US) }.getOrNull().orEmpty()
        val path = runCatching { URI(requestUrl).path?.lowercase(Locale.US) }.getOrNull().orEmpty()
        val frameHost = runCatching { URI(frameUrl ?: pageUrl ?: requestUrl).host?.lowercase(Locale.US) }.getOrNull().orEmpty()
        val key = listOf(shape.name, host, path, frameHost).joinToString("|")
        return "media-session-" + MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)
    }

    fun classifyShape(kind: MediaSourceKind, pageUrl: String?, mimeType: String?, live: Boolean, protected: Boolean): MediaTransferShape = when {
        protected -> MediaTransferShape.ProtectedDiagnostic
        live -> MediaTransferShape.LiveRecording
        kind == MediaSourceKind.HlsPlaylist || kind == MediaSourceKind.DashManifest -> MediaTransferShape.AdaptivePlaylist
        kind == MediaSourceKind.ProgressiveMedia || kind == MediaSourceKind.VideoStream || kind == MediaSourceKind.AudioStream -> MediaTransferShape.DirectMedia
        kind == MediaSourceKind.DirectFile -> MediaTransferShape.DirectFile
        !pageUrl.isNullOrBlank() || mimeType?.startsWith("text/html", ignoreCase = true) == true -> MediaTransferShape.SiteResolver
        else -> MediaTransferShape.DirectFile
    }

    fun classifyProtection(
        hlsKeyMetadata: String?,
        dashContentProtection: String?,
        browserEncryptionEvent: String?,
        resolverReport: String?,
    ): ProtectedMediaClassification {
        val evidence = buildList {
            if (!browserEncryptionEvent.isNullOrBlank()) add(DrmEvidence(DrmEvidenceKind.BrowserEncryptionEvent, browserEncryptionEvent.take(80), "browser"))
            if (!hlsKeyMetadata.isNullOrBlank()) add(DrmEvidence(DrmEvidenceKind.HlsKeyMetadata, hlsKeyMetadata.take(80), "hls"))
            if (!dashContentProtection.isNullOrBlank()) add(DrmEvidence(DrmEvidenceKind.DashContentProtection, dashContentProtection.take(80), "dash"))
            if (!resolverReport.isNullOrBlank()) add(DrmEvidence(DrmEvidenceKind.ResolverReport, resolverReport.take(80), "resolver"))
        }
        return ProtectedMediaClassification(evidence.isNotEmpty(), evidence, evidence.isNotEmpty())
    }

    fun shouldReplaceSession(existingRevision: Long?, incomingRevision: Long): Boolean = existingRevision == null || incomingRevision >= existingRevision
}
