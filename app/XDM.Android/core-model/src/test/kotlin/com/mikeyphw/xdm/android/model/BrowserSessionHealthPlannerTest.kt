package com.mikeyphw.xdm.android.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserSessionHealthPlannerTest {
    @Test
    fun signedBrowserHandoffReportsHighRiskWithoutLeakingSecrets() {
        val draft = DownloadIntakeDraft(
            id = "external-review-1",
            url = "https://media.example.test/video.mp4?token=secret&expires=999",
            fileName = "video.mp4",
            sourceLabel = "External browser",
            origin = DownloadIntakeOrigin.BrowserExtension,
            pageUrl = "https://media.example.test/watch/123",
            mimeType = "video/mp4",
            requestHeaders = mapOf(
                "Cookie" to "sid=secret",
                "Authorization" to "Bearer secret",
                "User-Agent" to "Browser",
                "Referer" to "https://media.example.test/watch/123",
            ),
            redactedHeaderSummary = "headers=<redacted>",
        )

        val report = BrowserSessionHealthPlanner.evaluate(draft) ?: error("missing health report")

        assertEquals("Captured", report.browserContextLabel)
        assertEquals("Detected", report.protectedRequestLabel)
        assertEquals("High", report.expiryRiskLabel)
        assertEquals("Use captured session", report.primaryActionLabel)
        val rendered = listOf(report.contextSummary, report.guidance, report.signals.joinToString { it.label + it.value + it.guidance }).joinToString("\n")
        assertFalse(rendered.contains("sid=secret"))
        assertFalse(rendered.contains("Bearer secret"))
        assertFalse(rendered.contains("token=secret"))
    }

    @Test
    fun missingBrowserContextRecommendsMediaInspectionForUnknownPages() {
        val draft = DownloadIntakeDraft(
            id = "external-review-2",
            url = "https://example.test/watch/123",
            sourceLabel = "External browser",
            origin = DownloadIntakeOrigin.BrowserExtension,
        )

        val report = BrowserSessionHealthPlanner.evaluate(draft) ?: error("missing health report")

        assertEquals("Not captured", report.browserContextLabel)
        assertEquals("Medium", report.expiryRiskLabel)
        assertEquals("Media resolver first", report.suggestedMethodLabel)
        assertTrue(report.primaryActionLabel == "Inspect media first" || report.primaryActionLabel == "Refresh from browser")
        assertTrue(report.signals.any { it.label == "Page context" && it.value == "Missing" })
    }
}
