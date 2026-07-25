package com.mikeyphw.xdm.android.media

import com.mikeyphw.xdm.android.model.DownloadIntakeDraft
import com.mikeyphw.xdm.android.model.DownloadIntakeKind
import com.mikeyphw.xdm.android.model.DownloadIntakeOrigin
import com.mikeyphw.xdm.android.model.MediaSourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalMediaReviewPlannerTest {
    private val planner = ExternalMediaReviewPlanner(clock = { 42L })

    @Test
    fun directMediaUsesNormalCandidateAndVariants() {
        val intake = planner.plan(draft("https://cdn.example/video.mp4", DownloadIntakeKind.DirectMedia))
        assertNotNull(intake)
        assertFalse(intake!!.isPageProbe)
        assertEquals(MediaSourceKind.ProgressiveMedia, intake.record.kind)
        assertTrue(intake.variants.isNotEmpty())
    }

    @Test
    fun reviewedPageCreatesYtDlpProbeRecordWithoutStartingWork() {
        val intake = planner.plan(
            draft(
                url = "https://example.com/watch?id=7",
                kind = DownloadIntakeKind.PageOrUnknown,
                title = "Example clip",
            ),
        )
        assertNotNull(intake)
        assertTrue(intake!!.isPageProbe)
        assertEquals(MediaSourceKind.Unknown, intake.record.kind)
        assertEquals("https://example.com/watch?id=7", intake.record.pageUrl)
        assertEquals(0, intake.record.variantCount)
        assertTrue(intake.variants.isEmpty())
        assertEquals(42L, intake.record.createdAtEpochMs)
    }

    @Test
    fun ordinaryFileAndFtpPageCannotEnterMediaReview() {
        assertNull(planner.plan(draft("https://example.com/archive.zip", DownloadIntakeKind.DirectFile)))
        assertNull(planner.plan(draft("ftp://example.com/folder", DownloadIntakeKind.PageOrUnknown)))
    }

    private fun draft(url: String, kind: DownloadIntakeKind, title: String? = null) = DownloadIntakeDraft(
        id = "draft",
        url = url,
        origin = DownloadIntakeOrigin.ExternalShare,
        pageTitle = title,
        kind = kind,
    )
}
