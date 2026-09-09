package com.mikeyphw.xdm.android.scheduler

import com.mikeyphw.xdm.android.model.DestinationHealthStatus
import com.mikeyphw.xdm.android.model.DestinationSpaceState
import com.mikeyphw.xdm.android.model.DestinationType
import com.mikeyphw.xdm.android.storage.DestinationHealth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DestinationSpacePolicyTest {
    @Test
    fun healthyKnownCapacityIsKnown() {
        val probe = DestinationSpacePolicy.fromHealth(
            DestinationHealth("xdm://filesystem/downloads", DestinationType.FileSystem, DestinationHealthStatus.Healthy, "Download/XDM", 64L * 1024L * 1024L),
        )
        assertEquals(DestinationSpaceState.Known, probe.state)
        assertEquals(64L * 1024L * 1024L, probe.availableBytes)
    }

    @Test
    fun healthyProviderWithoutCapacityIsUnknownNotUnavailable() {
        val probe = DestinationSpacePolicy.fromHealth(
            DestinationHealth("content://provider/tree/primary", DestinationType.SafTree, DestinationHealthStatus.Healthy, "Selected folder", null),
        )
        assertEquals(DestinationSpaceState.Unknown, probe.state)
        assertNull(probe.availableBytes)
    }

    @Test
    fun permissionFailureIsUnavailable() {
        val probe = DestinationSpacePolicy.fromHealth(
            DestinationHealth("content://provider/tree/primary", DestinationType.SafTree, DestinationHealthStatus.PermissionMissing, "Selected folder", null),
        )
        assertEquals(DestinationSpaceState.Unavailable, probe.state)
    }
}
