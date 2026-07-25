package com.mikeyphw.xdm.android.media

import com.mikeyphw.xdm.android.model.MediaCaptureRecord

/** Browser-neutral result of classifying a network/media handoff. */
data class MediaCaptureIntake(
    val facts: MediaRequestFacts,
    val candidate: MediaCaptureCandidate,
    val record: MediaCaptureRecord,
)

/** Pure media intake seam shared by external handoff, shares, clipboard intake, and future
 * external integrations. Repository merging remains in the application layer. */
class MediaCaptureIntakePlanner(
    private val captureService: MediaCaptureService = MediaCaptureService(),
) {
    fun plan(facts: MediaRequestFacts): MediaCaptureIntake? {
        val candidate = captureService.candidateFor(
            url = facts.url,
            pageTitle = facts.pageTitle,
            pageUrl = facts.pageUrl,
            mimeTypeHint = facts.mimeType,
            contentLength = facts.contentLength,
            headers = facts.headers,
        ) ?: return null
        return MediaCaptureIntake(
            facts = facts,
            candidate = candidate,
            record = captureService.recordFor(candidate),
        )
    }
}
