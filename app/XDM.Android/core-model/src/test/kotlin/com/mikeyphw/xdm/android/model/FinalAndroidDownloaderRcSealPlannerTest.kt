package com.mikeyphw.xdm.android.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FinalAndroidDownloaderRcSealPlannerTest {
    @Test
    fun finalRcSealIsReadyWhenAllDownloaderReadinessSignalsArePresent() {
        val seal = FinalAndroidDownloaderRcSealPlanner.evaluate(
            debugWorkbenchSealed = true,
            operationalFieldFixesSealed = true,
            runtimeRecoveryFlowSealed = true,
            finalGateValidatorsHarmonized = true,
            realDeviceSmokeRepresented = true,
            supportBundleSealed = true,
            browserRuntimeAbsent = true,
            roomSchemaUnchanged = true,
            noBroadStoragePermission = true,
            noAutomaticWork = true,
            noAutomaticDeletion = true,
            noAutomaticUpload = true,
            noPersistedSessionValues = true,
            redactedDiagnosticsOnly = true,
            signedArtifactsExpected = true,
            checksumsExpected = true,
            deferredFullValidationExpected = true,
        )

        assertTrue(seal.readyForRcHandoff)
        assertTrue(seal.summary.contains("ready", ignoreCase = true))
        assertTrue(seal.redactedSummary().contains("XDM Android final downloader RC seal"))
        assertTrue(seal.redactedSummary().contains("Runtime recovery flow sealed"))
        assertTrue(seal.redactedSummary().contains("Browser-free downloader boundary"))
        assertFalse(seal.redactedSummary().contains("https://"))
        assertFalse(seal.redactedSummary().contains("Cookie:"))
        assertFalse(seal.redactedSummary().contains("Authorization:"))
        assertFalse(seal.redactedSummary().contains("Bearer secret"))
    }

    @Test
    fun finalRcSealHoldsWhenValidationOrPrivacyBoundariesAreMissing() {
        val seal = FinalAndroidDownloaderRcSealPlanner.evaluate(
            debugWorkbenchSealed = true,
            operationalFieldFixesSealed = true,
            runtimeRecoveryFlowSealed = true,
            finalGateValidatorsHarmonized = true,
            realDeviceSmokeRepresented = false,
            supportBundleSealed = true,
            browserRuntimeAbsent = true,
            roomSchemaUnchanged = true,
            noBroadStoragePermission = false,
            noAutomaticWork = true,
            noAutomaticDeletion = true,
            noAutomaticUpload = true,
            noPersistedSessionValues = false,
            redactedDiagnosticsOnly = true,
            signedArtifactsExpected = true,
            checksumsExpected = false,
            deferredFullValidationExpected = false,
        )

        assertFalse(seal.readyForRcHandoff)
        assertTrue(seal.holdCount == 3)
        assertTrue(seal.checks.any { it.title == "Real-device smoke represented" && it.status == FinalAndroidDownloaderRcSealStatus.Hold })
        assertTrue(seal.checks.any { it.title == "Runtime side-effect boundary" && it.status == FinalAndroidDownloaderRcSealStatus.Hold })
        assertTrue(seal.checks.any { it.title == "Privacy and artifact handoff" && it.status == FinalAndroidDownloaderRcSealStatus.Hold })
    }
}
