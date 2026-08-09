package com.mikeyphw.xdm.android.model

/** Non-secret browser capture metadata safe for UI and durable app-private indexing. */
data class BrowserCaptureCandidateSummary(
    val captureId: String,
    val stableMediaId: String,
    val quality: String,
    val reason: String,
    val mediaKind: String,
    val evidence: List<String> = emptyList(),
)

data class BrowserCaptureSessionSummary(
    val sessionId: String,
    val revision: Long,
    val pageTitle: String,
    val pageHost: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val totalCandidateCount: Int,
    val importedCandidateCount: Int,
    val truncated: Boolean,
    val candidates: List<BrowserCaptureCandidateSummary>,
) {
    val captureIds: Set<String> get() = candidates.mapTo(linkedSetOf(), BrowserCaptureCandidateSummary::captureId)
}
