package com.mikeyphw.xdm.android.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineEscalationPlannerTest {
    @Test
    fun protectedSignedMediaUsesNativeWithoutLeakingSecrets() {
        val draft = DownloadIntakeDraft(
            id = "external-review-54a",
            url = "https://media.example.test/signed/video.mp4?token=secret&expires=999",
            fileName = "video.mp4",
            sourceLabel = "Browser extension",
            origin = DownloadIntakeOrigin.BrowserExtension,
            pageUrl = "https://media.example.test/watch/abc",
            mimeType = "video/mp4",
            requestHeaders = mapOf(
                "Cookie" to "sid=secret",
                "Authorization" to "Bearer secret",
                "Referer" to "https://media.example.test/watch/abc",
                "User-Agent" to "Browser",
            ),
        )

        val plan = EngineEscalationPlanner.evaluate(draft) ?: error("missing plan")

        assertEquals("XDM Native with captured session", plan.recommendedMethodLabel)
        assertEquals("Queue before link expires", plan.nextActionLabel)
        assertTrue(plan.reasonLabel.contains("temporary") || plan.reasonLabel.contains("sign-in"))
        val rendered = listOf(
            plan.title,
            plan.recommendedMethodLabel,
            plan.nextActionLabel,
            plan.reasonLabel,
            plan.guidance,
            plan.steps.joinToString { it.label + it.status + it.guidance },
            plan.alternatives.joinToString { it.methodLabel + it.whenToUse },
        ).joinToString("\n")
        assertFalse(rendered.contains("sid=secret"))
        assertFalse(rendered.contains("Bearer secret"))
        assertFalse(rendered.contains("token=secret"))
        assertFalse(rendered.contains("https://media.example.test/signed/video.mp4"))
    }

    @Test
    fun pageHandoffPrefersMediaInspectionAndYtDlp() {
        val draft = DownloadIntakeDraft(
            id = "external-review-54b",
            url = "https://video.example.test/watch/123",
            sourceLabel = "External browser",
            origin = DownloadIntakeOrigin.BrowserExtension,
        )

        val plan = EngineEscalationPlanner.evaluate(draft) ?: error("missing plan")

        assertEquals("Inspect media before queueing", plan.recommendedMethodLabel)
        assertEquals("Inspect media first", plan.nextActionLabel)
        assertTrue(plan.alternatives.any { it.methodLabel == "yt-dlp/media resolver" })
        assertTrue(plan.steps.any { it.label == "Request shape" && it.status == "Page or unknown" })
    }

    @Test
    fun largeDirectFilePrefersAria2WhenNoSessionContextExists() {
        val draft = DownloadIntakeDraft(
            id = "external-review-54c",
            url = "https://files.example.test/release.iso",
            fileName = "release.iso",
            sourceLabel = "External browser",
            origin = DownloadIntakeOrigin.ExternalView,
            mimeType = "application/octet-stream",
            contentLength = 900L * 1024L * 1024L,
        )

        val plan = EngineEscalationPlanner.evaluate(draft) ?: error("missing plan")

        assertEquals("aria2 segmented transfer", plan.recommendedMethodLabel)
        assertEquals("Large direct file benefits from segments", plan.reasonLabel)
        assertTrue(plan.alternatives.any { it.methodLabel == "aria2" })
    }

    @Test
    fun forbiddenStatusRecommendsRecaptureInsteadOfBlindRetry() {
        val draft = DownloadIntakeDraft(
            id = "external-review-54d",
            url = "https://protected.example.test/watch/123",
            sourceLabel = "External browser",
            origin = DownloadIntakeOrigin.BrowserExtension,
        )

        val plan = EngineEscalationPlanner.evaluate(draft, lastHttpStatus = 403) ?: error("missing plan")

        assertEquals("Refresh browser capture or inspect with yt-dlp", plan.recommendedMethodLabel)
        assertEquals("Refresh from browser", plan.nextActionLabel)
        assertEquals("Server asked for browser access", plan.reasonLabel)
        assertTrue(plan.guidance.contains("refused a direct probe"))
    }
}
