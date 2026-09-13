package com.mikeyphw.xdm.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiMutationConcurrencyCoordinatorTest {
    @Test
    fun destinationIntentIsLastIntentWinsRatherThanLastCompletionWins() {
        val coordinator = UiMutationConcurrencyCoordinator()
        val slowOldIntent = coordinator.nextDestinationIntent()
        val fastNewIntent = coordinator.nextDestinationIntent()

        assertFalse(coordinator.isCurrentDestinationIntent(slowOldIntent))
        assertTrue(coordinator.isCurrentDestinationIntent(fastNewIntent))
    }

    @Test
    fun diagnosticGateIsAtomicAndReusable() {
        val gate = ExclusiveOperationGate()
        assertTrue(gate.tryAcquire())
        assertFalse(gate.tryAcquire())
        assertTrue(gate.isHeld())
        gate.release()
        assertFalse(gate.isHeld())
        assertTrue(gate.tryAcquire())
    }
}
