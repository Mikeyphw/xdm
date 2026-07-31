package com.mikeyphw.xdm.android.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealDeviceOperationalSmokeSealPlannerTest {
    @Test
    fun smokeSealRequiresManualDeviceRunBeforeRcCandidate() {
        val seal = RealDeviceOperationalSmokeSealPlanner.build(manualResultsCaptured = false)

        assertTrue(seal.manualDeviceRunRequired)
        assertFalse(seal.readyForRcCandidate)
        assertTrue(seal.checks.size == 5)
        assertTrue(seal.checks.any { it.flow == RealDeviceSmokeFlow.ExternalBrowserHandoff })
        assertTrue(seal.checks.any { it.flow == RealDeviceSmokeFlow.ExtensionMediaCapture })
        assertTrue(seal.checks.any { it.flow == RealDeviceSmokeFlow.AuthenticatedFailureRecovery })
        assertTrue(seal.checks.any { it.flow == RealDeviceSmokeFlow.CompletedStorageVisibility })
        assertTrue(seal.checks.any { it.flow == RealDeviceSmokeFlow.RecoveryDoctorReview })
        assertTrue(seal.redactedSummary.contains("manual device run: required", ignoreCase = true))
        assertFalse(seal.redactedSummary.contains("https://"))
        assertFalse(seal.redactedSummary.contains("Cookie:"))
        assertFalse(seal.redactedSummary.contains("Authorization:"))
    }

    @Test
    fun capturedSmokeRunCanSealRcCandidate() {
        val seal = RealDeviceOperationalSmokeSealPlanner.build(manualResultsCaptured = true)

        assertFalse(seal.manualDeviceRunRequired)
        assertTrue(seal.readyForRcCandidate)
        assertTrue(seal.checks.all { it.status == RealDeviceSmokeStatus.Ready })
        assertTrue(seal.redactedSummary.contains("RC candidate: ready"))
        assertTrue(seal.redactedSummary.contains("External browser handoff"))
        assertTrue(seal.redactedSummary.contains("Recovery Doctor review"))
    }
}
