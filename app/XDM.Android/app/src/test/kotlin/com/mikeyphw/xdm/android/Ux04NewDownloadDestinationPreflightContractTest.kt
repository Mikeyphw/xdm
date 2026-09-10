package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Ux04NewDownloadDestinationPreflightContractTest {
    private fun source(relative: String): String {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        val root = generateSequence(cwd) { it.parentFile }.first { File(it, "app/src/main").isDirectory }
        return File(root, relative).readText()
    }

    @Test
    fun newDownloadUsesOneDestinationPickerAndAdvisoryPreflight() {
        val add = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/intake/AddDownloadSurface.kt")
        val probe = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/intake/DownloadPreflightProbe.kt")
        assertTrue(add.contains("headline = \"Save to\""))
        assertTrue(add.contains("DestinationPickerSheet("))
        assertTrue(add.contains("Text(\"Change\")"))
        assertTrue(add.contains("DestinationPreflightSummary"))
        assertTrue(add.contains("DownloadUrlPreflightSummary"))
        assertFalse(add.contains("DestinationCatalog.available(Build.VERSION.SDK_INT).forEach { choice ->\n                                FilterChip"))
        assertTrue(probe.contains("requestMethod = \"HEAD\""))
        assertTrue(probe.contains("instanceFollowRedirects = true"))
        assertTrue(probe.contains("Remote details are unavailable right now. This does not prevent downloading"))
    }

    @Test
    fun advancedOptionsLeadWithCommonConflictChoices() {
        val add = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/intake/AddDownloadSurface.kt")
        assertTrue(add.contains("FilenameConflictPolicy.Rename"))
        assertTrue(add.contains("FilenameConflictPolicy.Resume"))
        assertTrue(add.contains("FilenameConflictPolicy.Overwrite"))
        assertTrue(add.contains("FilenameConflictPolicy.Skip"))
        assertTrue(add.contains("FilenameConflictPolicy.Compare"))
        assertTrue(add.contains("Try another engine if needed"))
        assertFalse(add.contains("Text(\"Compatible fallback\""))
    }
}
