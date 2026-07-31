package com.mikeyphw.xdm.android.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportBundleReleaseReadinessPlannerTest {
    @Test
    fun supportBundleSealIsReadyWhenAllReleaseSectionsAreRedactedAndPresent() {
        val seal = SupportBundleReleaseReadinessPlanner.evaluate(
            operationalDiagnosticsIncluded = true,
            releaseSecurityIncluded = true,
            installUpdateReadinessIncluded = true,
            finalReleaseWarningsExplained = true,
            realDeviceSmokeStatusIncluded = true,
            redactedReportsOnly = true,
            rawUrlsExcluded = true,
            rawHeadersExcluded = true,
            sessionValuesPersisted = false,
            copyReportAvailable = true,
        )

        assertTrue(seal.readyForSupportHandoff)
        assertTrue(seal.summary.contains("ready", ignoreCase = true))
        assertTrue(seal.checks.any { it.owner == "PrivacyDiagnosticsRedactor" })
        assertTrue(seal.redactedSummary().contains("XDM Android support bundle seal"))
        assertTrue(seal.redactedSummary().contains("Final-release warning explanations"))
        assertTrue(seal.redactedSummary().contains("Real-device smoke status"))
        assertFalse(seal.redactedSummary().contains("https://"))
        assertFalse(seal.redactedSummary().contains("Cookie:"))
        assertFalse(seal.redactedSummary().contains("Authorization:"))
        assertFalse(seal.redactedSummary().contains("Bearer secret"))
    }

    @Test
    fun supportBundleSealBlocksWhenWarningsAreBareOrSessionValuesWouldPersist() {
        val seal = SupportBundleReleaseReadinessPlanner.evaluate(
            operationalDiagnosticsIncluded = true,
            releaseSecurityIncluded = true,
            installUpdateReadinessIncluded = true,
            finalReleaseWarningsExplained = false,
            realDeviceSmokeStatusIncluded = true,
            redactedReportsOnly = true,
            rawUrlsExcluded = true,
            rawHeadersExcluded = true,
            sessionValuesPersisted = true,
            copyReportAvailable = true,
        )

        assertFalse(seal.readyForSupportHandoff)
        assertTrue(seal.issueCount == 2)
        assertTrue(seal.redactedSummary().contains("needs attention"))
        assertTrue(seal.checks.any { it.title == "Final-release warning explanations" && it.status == SupportBundleSealStatus.NeedsAttention })
        assertTrue(seal.checks.any { it.title == "Privacy redaction boundary" && it.status == SupportBundleSealStatus.NeedsAttention })
    }
}
