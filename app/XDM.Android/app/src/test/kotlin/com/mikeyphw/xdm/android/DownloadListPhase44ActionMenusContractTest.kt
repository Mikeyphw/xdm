package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadListPhase44ActionMenusContractTest {
    @Test
    fun downloadRowsRenderPlannerPrimaryAndMoreActions() {
        val root = androidRoot()
        val row = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadRow.kt").readText()
        val screen = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadsScreen.kt").readText()

        assertTrue(row.contains("DownloadActionPlanner.primaryActionFor(download)"))
        assertTrue(row.contains("onMoreActions: () -> Unit"))
        assertTrue(row.contains("More actions for"))
        assertFalse(row.contains("private data class DownloadRowAction"))
        assertFalse(row.contains("private fun Download.primaryRowAction"))

        assertTrue(screen.contains("actionDownloadId"))
        assertTrue(screen.contains("DownloadActionsContent"))
        assertTrue(screen.contains("DownloadActionPlanner.actionsFor(download)"))
        assertTrue(screen.contains("performDownloadAction"))
        assertTrue(screen.contains("XdmListRow("))
    }

    @Test
    fun phase44PlannerIsPureModelWithStateMatrixAndBatchActions() {
        val root = androidRoot()
        val planner = File(root, "core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DownloadActionPlanner.kt").readText()

        assertTrue(planner.contains("enum class DownloadActionKind"))
        assertTrue(planner.contains("OpenFile"))
        assertTrue(planner.contains("MoveToTop"))
        assertTrue(planner.contains("DeleteFileAndRecord"))
        assertTrue(planner.contains("data class DownloadAction"))
        assertTrue(planner.contains("destructive: Boolean = false"))
        assertTrue(planner.contains("requiresConfirmation: Boolean = false"))
        assertTrue(planner.contains("object DownloadActionPlanner"))
        assertTrue(planner.contains("fun actionsFor(download: Download): List<DownloadAction>"))
        assertTrue(planner.contains("fun primaryActionFor(download: Download): DownloadAction"))
        assertTrue(planner.contains("fun batchActionsFor(downloads: List<Download>): List<DownloadAction>"))
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
