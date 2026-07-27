package com.mikeyphw.xdm.android

import com.mikeyphw.xdm.android.model.DownloadIntakeOrigin
import com.mikeyphw.xdm.android.model.DownloadReviewPlanner
import com.mikeyphw.xdm.android.model.DownloadReviewReadiness
import com.mikeyphw.xdm.android.model.MediaInspectionRecommendation
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserExtensionPhase43BContractTest {
    @Test
    fun addDownloadDemotesMediaAnalysisForOrdinaryUnknownManualLinks() {
        val manual = DownloadReviewPlanner.plan(
            url = "https://example.test/watch/123",
            destinationUri = "xdm://downloads",
            origin = DownloadIntakeOrigin.ManualEntry,
        )

        assertEquals(MediaInspectionRecommendation.Hidden, manual.mediaInspectionRecommendation)
        assertFalse(manual.canInspectAsMedia)
        assertFalse(manual.mediaInspectionRecommended)
        assertEquals(DownloadReviewReadiness.Ready, manual.readiness)
        assertEquals("Add to queue", manual.primaryActionLabel)
    }

    @Test
    fun browserExtensionAndAdaptiveMediaStillSurfaceStrongInspection() {
        val extensionMedia = DownloadReviewPlanner.plan(
            url = "https://cdn.example.test/video.mp4",
            destinationUri = "xdm://downloads",
            origin = DownloadIntakeOrigin.BrowserExtension,
        )
        assertEquals(MediaInspectionRecommendation.Recommended, extensionMedia.mediaInspectionRecommendation)
        assertTrue(extensionMedia.canInspectAsMedia)
        assertTrue(extensionMedia.mediaInspectionRecommended)
        assertEquals("Inspect media (recommended)", extensionMedia.mediaInspectionActionLabel)

        val playlist = DownloadReviewPlanner.plan(
            url = "https://cdn.example.test/live/master.m3u8",
            destinationUri = "xdm://downloads",
            origin = DownloadIntakeOrigin.ManualEntry,
        )
        assertEquals(MediaInspectionRecommendation.Recommended, playlist.mediaInspectionRecommendation)
        assertEquals(DownloadReviewReadiness.ChoiceRecommended, playlist.readiness)
    }

    @Test
    fun externalPageAnalysisUsesNeutralLanguage() {
        val external = DownloadReviewPlanner.plan(
            url = "https://example.test/watch/123",
            destinationUri = "xdm://downloads",
            origin = DownloadIntakeOrigin.ExternalView,
        )
        assertEquals(MediaInspectionRecommendation.Optional, external.mediaInspectionRecommendation)
        assertEquals("Analyze page for media", external.mediaInspectionActionLabel)
        assertEquals("Use page analysis only when this link is a watch page or playlist, not a normal file.", external.mediaInspectionGuidance)
    }

    @Test
    fun composeSurfaceReceivesOriginAndUsesPlannerLabels() {
        val root = androidRoot()
        val addSurface = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/intake/AddDownloadSurface.kt").readText()
        val shell = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApp.kt").readText()
        val viewModel = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt").readText()

        assertTrue(addSurface.contains("externalOrigin: DownloadIntakeOrigin? = null"))
        assertTrue(addSurface.contains("origin = if (externalDraftId != null && url == initialUrl) externalOrigin"))
        assertTrue(addSurface.contains("Text(review.mediaInspectionActionLabel)"))
        assertTrue(addSurface.contains("XdmMetadataText(review.mediaInspectionGuidance)"))
        assertFalse(addSurface.contains("Media inspection opens the resolver"))
        assertTrue(shell.contains("externalOrigin = state.externalAddDraft?.origin"))
        assertTrue(viewModel.contains("AutomationCommandSource.BrowserExtension -> DownloadIntakeOrigin.BrowserExtension"))
    }

    private fun androidRoot(): File {
        var cursor = File(System.getProperty("user.dir")).canonicalFile
        repeat(8) {
            if (File(cursor, "settings.gradle.kts").isFile && File(cursor, "app/src/main").isDirectory) return cursor
            cursor = cursor.parentFile ?: return@repeat
        }
        error("Unable to locate XDM Android root")
    }
}
