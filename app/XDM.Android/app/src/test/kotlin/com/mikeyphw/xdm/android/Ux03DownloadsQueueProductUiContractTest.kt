package com.mikeyphw.xdm.android

import com.mikeyphw.xdm.android.model.DownloadActionIcon
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Ux03DownloadsQueueProductUiContractTest {
    @Test
    fun downloadsUsesFourUserFacingFiltersAndSeparateWaitingMetrics() {
        val root = androidRoot()
        val workspace = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadsWorkspace.kt").readText()
        val screen = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadsScreen.kt").readText()

        listOf(
            "All(\"All\")",
            "Downloading(\"Downloading\")",
            "Waiting(\"Waiting\")",
            "Finished(\"Finished\")",
            "val waitingCount: Int",
            "val queuedCount: Int",
            "queueIssue(queueIntelligence)",
            "XdmMetric(\"waiting\"",
            "XdmMetric(\"queued\"",
        ).forEach { assertTrue("UX03 missing $it", workspace.contains(it) || screen.contains(it)) }
        assertFalse(workspace.contains("Active(\"Active\")"))
        assertFalse(workspace.contains("Paused(\"Paused\")"))
    }

    @Test
    fun queueErrorsAreGroupedAndRowsStayConcise() {
        val root = androidRoot()
        val workspace = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadsWorkspace.kt").readText()
        val screen = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadsScreen.kt").readText()
        val row = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadRow.kt").readText()

        assertTrue(workspace.contains("fun queueIssue(summary: QueueIntelligenceSummary)"))
        assertTrue(workspace.contains("fun rowStatus(download: Download, truth: DownloadUiTruth)"))
        assertTrue(workspace.contains("Storage check failed"))
        assertTrue(workspace.contains("Waiting — \$policy"))
        assertFalse(screen.contains("text = queueIntelligence.message"))
        assertTrue(row.contains("val rowStatus = DownloadsWorkspacePlanner.rowStatus(download, truth)"))
        assertTrue(row.contains("val destination = destinationUiLabel(download.destinationUri)"))
    }

    @Test
    fun resumeAndStartDownloadActionsDoNotUseTheMediaPlayGlyph() {
        val root = androidRoot()
        val planner = File(root, "core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DownloadActionPlanner.kt").readText()
        val row = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadRow.kt").readText()

        assertTrue(DownloadActionIcon.entries.any { it.name == "Resume" })
        assertTrue(planner.contains("DownloadActionIcon.Resume"))
        assertTrue(row.contains("DownloadActionIcon.Resume -> Icons.Rounded.Download"))
        assertTrue(row.contains("XdmProgressLine("))
        assertTrue(row.contains("destinationUiLabel(download.destinationUri)"))
    }

    private fun androidRoot(): File {
        var cursor = File(System.getProperty("user.dir") ?: ".").canonicalFile
        repeat(8) {
            if (File(cursor, "settings.gradle.kts").isFile && File(cursor, "app/src/main").isDirectory) return cursor
            cursor = cursor.parentFile ?: return@repeat
        }
        error("Unable to locate XDM Android root")
    }
}
