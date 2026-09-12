package com.mikeyphw.xdm.android.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaRuntimeNoTermuxFf04Test {
    @Test
    fun freshInstallNeedsNoTermuxWhenEmbeddedRuntimeIsHealthy() {
        val decision = MediaFfmpegRuntimeRoutingPolicy.decide(
            MediaFfmpegRuntimeContext(
                preference = MediaFfmpegRuntimePreference.Automatic,
                embeddedReady = true,
                termuxBridgeReady = false,
                termuxFfmpegReady = false,
                termuxFfprobeReady = false,
                termuxNetworkFallbackEligible = false,
            ),
        )

        assertEquals(MediaFfmpegRuntimeSource.Embedded, decision.source)
        assertTrue(decision.runnable)
        assertFalse(decision.fallbackUsed)
        assertFalse(decision.reason.contains("Termux", ignoreCase = true) && decision.reason.contains("required", ignoreCase = true))
    }
}
