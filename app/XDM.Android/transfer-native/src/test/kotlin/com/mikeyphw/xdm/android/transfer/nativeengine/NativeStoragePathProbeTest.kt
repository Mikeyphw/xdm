package com.mikeyphw.xdm.android.transfer.nativeengine

import com.mikeyphw.xdm.android.storage.FileDestinationWriter
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeStoragePathProbeTest {
    @Test
    fun probeUsesNativeDestinationStagingAndPromotionContract() = runTest {
        val directory = Files.createTempDirectory("xdm-native-storage-probe").toFile()
        try {
            val probe = NativeStoragePathProbe(FileDestinationWriter())
            val result = probe.run(directory.toURI().toString())
            assertTrue(result.summary, result.successful)
            assertTrue(directory.listFiles().orEmpty().none { it.name.startsWith(".xdm-native-storage-probe-") })
        } finally {
            directory.deleteRecursively()
        }
    }
}
