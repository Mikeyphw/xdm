package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IntermediateOverlayCompileRepairContractTest {
    @Test
    fun intermediateOverlayCompileAndContractRootRegressionsStayClosed() {
        val root = androidRoot()
        val locator = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/MediaLocatorActivity.kt").readText()
        val artwork = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/media/XdmMediaArtwork.kt").readText()
        val primitives = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/XdmPrimitives.kt").readText()
        val thumbContract = File(root, "app/src/test/kotlin/com/mikeyphw/xdm/android/MediaThumbnailMimePresentationContractTest.kt").readText()
        val obsContract = File(root, "app/src/test/kotlin/com/mikeyphw/xdm/android/ObservabilityProblemReportingContractTest.kt").readText()

        assertTrue(locator.contains("lastMainFrameError = \"\$title\\n\$detail\""))
        assertFalse(locator.contains("lastMainFrameError = \"\$title\n\$detail\""))
        assertTrue(artwork.contains("DebugArea.Thumbnail"))
        assertFalse(artwork.contains("DebugArea.Media,"))
        assertTrue(primitives.contains("Icons.AutoMirrored.Rounded.QueueMusic"))
        assertTrue(thumbContract.contains("generateSequence(cwd) { it.parentFile }"))
        assertTrue(obsContract.contains("generateSequence(cwd) { it.parentFile }"))
        assertFalse(thumbContract.contains("File(cwd, \"app/XDM.Android\")"))
        assertFalse(obsContract.contains("File(cwd, \"app/XDM.Android\")"))
    }

    private fun androidRoot(): File {
        val cwd = File(System.getProperty("user.dir") ?: ".").canonicalFile
        return generateSequence(cwd) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile && File(it, "core-model").isDirectory }
            ?: error("Could not locate XDM.Android root from $cwd")
    }
}
