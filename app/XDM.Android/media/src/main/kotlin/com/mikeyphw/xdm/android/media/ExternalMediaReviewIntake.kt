package com.mikeyphw.xdm.android.media

import com.mikeyphw.xdm.android.model.DownloadIntakeDraft
import com.mikeyphw.xdm.android.model.DownloadIntakeKind
import com.mikeyphw.xdm.android.model.MediaCaptureRecord
import com.mikeyphw.xdm.android.model.MediaCaptureStatus
import com.mikeyphw.xdm.android.model.MediaResolutionStatus
import com.mikeyphw.xdm.android.model.MediaSourceKind
import com.mikeyphw.xdm.android.model.MediaVariant
import com.mikeyphw.xdm.android.util.sanitizeFileName
import java.net.URI
import java.util.Locale

/** Browser-neutral media-workbench seed created from an explicit external review action. */
data class ExternalMediaReviewIntake(
    val record: MediaCaptureRecord,
    val variants: List<MediaVariant>,
    val isPageProbe: Boolean,
)

/** Converts a reviewed external handoff into an existing media-workbench record.
 * Direct media uses the normal classifier. Page URLs become yt-dlp probe records only after the
 * user explicitly selects Inspect as media; this planner never starts a probe or download. */
class ExternalMediaReviewPlanner(
    private val captureService: MediaCaptureService = MediaCaptureService(),
    private val sniffingEngine: MediaSniffingEngine = MediaSniffingEngine(captureService),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun plan(draft: DownloadIntakeDraft): ExternalMediaReviewIntake? {
        if (!draft.canInspectAsMedia) return null
        val sniffingPlan = sniffingEngine.sniff(
            MediaSniffingInput(
                url = draft.url,
                mimeType = draft.mimeType,
                contentLength = draft.contentLength,
                pageUrl = draft.pageUrl,
                pageTitle = draft.pageTitle,
                source = MediaSniffingSource.ManualPage,
            ),
        )
        val sniffedRecord = sniffingPlan.records.firstOrNull()
        if (sniffedRecord != null) {
            return ExternalMediaReviewIntake(
                record = sniffedRecord,
                variants = sniffingPlan.variants.filter { it.captureId == sniffedRecord.id },
                isPageProbe = false,
            )
        }
        if (draft.kind != DownloadIntakeKind.PageOrUnknown || !draft.url.isHttpUrl()) return null

        val now = clock()
        val title = draft.pageTitle
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.take(160)
            ?: draft.host?.let { "Media from $it" }
            ?: "Shared media page"
        val pageUrl = draft.pageUrl?.takeIf(String::isNotBlank) ?: draft.url
        val safeName = sanitizeFileName(title, fallback = "shared-media-page", maxLength = 120)
        return ExternalMediaReviewIntake(
            record = MediaCaptureRecord(
                id = MediaCaptureService.captureIdFor(draft.url),
                sourceUrl = draft.url,
                pageUrl = pageUrl,
                title = title,
                status = MediaCaptureStatus.MetadataMissing,
                kind = MediaSourceKind.Unknown,
                mimeType = draft.mimeType,
                container = "Page probe",
                codecs = null,
                durationMs = null,
                thumbnailUrl = null,
                fileName = "$safeName.media",
                variantCount = 0,
                downloadId = null,
                createdAtEpochMs = now,
                updatedAtEpochMs = now,
                selectedVariantId = null,
                selectedVariantUrl = null,
                resolutionStatus = MediaResolutionStatus.Unresolved,
            ),
            variants = emptyList(),
            isPageProbe = true,
        )
    }

    private fun String.isHttpUrl(): Boolean = runCatching {
        val scheme = URI(this).scheme?.lowercase(Locale.US)
        scheme == "http" || scheme == "https"
    }.getOrDefault(false)
}
