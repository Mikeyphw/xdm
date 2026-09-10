package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Ux01DestinationStorageTruthContractTest {
    private val root = File(requireNotNull(System.getProperty("user.dir"))).let { cwd ->
        generateSequence(cwd) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
    }

    @Test
    fun queueConsumesSharedDestinationWriterHealthInsteadOfParsingUris() {
        val reader = File(root, "scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/AndroidQueueConditionsReader.kt").readText()
        val app = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApplication.kt").readText()
        assertTrue(reader.contains("destinationWriter.health(raw)"))
        assertFalse(reader.contains("public-downloads://"))
        assertFalse(reader.contains("app-private://"))
        assertTrue(app.contains("destinationWriter = destinationWriter"))
    }

    @Test
    fun unknownCapacityIsNotRenderedAsDestinationUnavailablePolicy() {
        val model = File(root, "core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/QueueIntelligence.kt").readText()
        assertTrue(model.contains("DestinationSpaceState.Unknown -> Unit"))
        assertTrue(model.contains("DestinationSpaceState.Unavailable -> return hold"))
        assertFalse(model.contains("XDM cannot verify free space for this destination, so storage-pressure policy is held closed."))
    }
}
