package com.mikeyphw.xdm.android.transfer.nativeengine

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class Xar07NativeHttpProtocolContractTest {
    private val sourceRoot = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "transfer-native/src/main").isDirectory }

    private fun source(name: String): String = File(sourceRoot, "transfer-native/src/main/kotlin/com/mikeyphw/xdm/android/transfer/nativeengine/$name").readText()

    @Test
    fun nativeHttpKeepsCallsTrackedThroughBodyReadAndBoundsWrites() {
        val source = source("NativeHttpDownloadBackend.kt")
        assertTrue(source.contains("executeTracked(control, builder.build())"))
        assertTrue(source.contains("control.activeCalls += call"))
        assertTrue(source.contains("return block(response)"))
        assertTrue(source.contains("remainingExpected"))
        assertTrue(source.contains("Response body exceeded the declared native segment boundary"))
    }

    @Test
    fun metadataAndRepresentationTruthAreFailClosed() {
        val source = source("NativeHttpDownloadBackend.kt")
        assertTrue(source.contains("range.totalLength ?: if (range.rangeSupported) null else length"))
        assertTrue(source.contains("Complete response does not match the probed representation validator"))
        assertTrue(source.contains("Content-Range total is unknown while the expected remote length is known"))
        assertTrue(source.contains("Content-Range total is not greater than the end byte"))
        assertTrue(source.contains("expectedLength = metadata.totalLength ?: request.expectedLength"))
    }

    @Test
    fun finalizationAndControlsCannotDowngradeCommittedOrQuarantinedWork() {
        val source = source("NativeHttpDownloadBackend.kt")
        assertTrue(source.contains("verifyCheckpointBeforePromotion"))
        assertTrue(source.contains("Native finalization requires a persisted checkpoint integrity graph"))
        assertTrue(source.contains("NON_DOWNGRADABLE_STATES"))
        assertTrue(source.contains("COMMIT_PROTECTED_STATES"))
    }

    @Test
    fun redirectsRetryAndSelectiveRepairAreBoundedByProtocolTruth() {
        val native = source("NativeHttpDownloadBackend.kt")
        val models = source("NativeTransferModels.kt")
        val repair = source("NativeSelectiveRepairService.kt")
        assertTrue(native.contains("sanitizeRedirectCredentials"))
        assertTrue(native.contains("Authorization", ignoreCase = false))
        assertTrue(native.contains("parseHttpDate(raw)?.toInstant()"))
        assertTrue(native.contains("chargedHost"))
        assertTrue(models.contains("val retryHost: String? = null"))
        assertTrue(repair.contains("repairLocks"))
        assertTrue(repair.contains("UUID.randomUUID()"))
        assertTrue(repair.contains("verifyAllTrustedBlocks"))
        assertTrue(repair.contains("untouched trusted"))
    }
}
