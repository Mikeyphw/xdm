package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UixR3DownloadsAddContractTest {
    @Test
    fun downloadsIsAnAdaptiveTransferFirstWorkspace() {
        val root = androidRoot()
        val downloads = listOf("DownloadsScreen.kt", "DownloadRow.kt", "DownloadDetails.kt", "OrganizeDownloadsSheet.kt", "DownloadsWorkspace.kt")
            .joinToString("\n") { File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/$it").readText() }
        listOf(
            "DownloadWorkspaceFilter",
            "Active(\"Active\")",
            "Queued(\"Queued\")",
            "Paused(\"Paused\")",
            "Finished(\"Finished\")",
            "All(\"All\")",
            "XdmMetricStrip",
            "Organize downloads",
            "combinedClickable",
            "onLongClick",
            "XdmAdaptiveSheet",
            "XdmTechnicalDetails",
            "XdmWindowClass.Expanded",
        ).forEach { assertTrue("Downloads R3 missing $it", downloads.contains(it)) }
        assertFalse("Rows must not carry a permanent Select chip", downloads.contains("label = { Text(\"Select\") }"))
        assertFalse("User details must not render raw request headers", downloads.contains("Text(download.requestHeaders"))
    }

    @Test
    fun addIsSingleActionAndNeverAutoQueuesMediaInspection() {
        val root = androidRoot()
        val add = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/intake/AddDownloadSurface.kt").readText()
        listOf(
            "DownloadReviewPlanner.plan(",
            "Advanced options",
            "destinationUiLabel(destinationUri)",
            "preferMediaInspection",
            "Media options",
            "else -> \"Download\"",
            "onCancel",
        ).forEach { assertTrue("Add R3 current UX missing $it", add.contains(it)) }
        listOf("reviewConfirmed", "Review download", "Add to queue", "Step 1 of 2", "Step 2 of 2").forEach { stale ->
            assertFalse("Add must not retain obsolete two-step token $stale", add.contains(stale))
        }
        assertTrue("Inspect media must use its dedicated callback", add.contains("onInspectMedia(url, name)"))
        assertFalse("Inspect media callback must not directly enqueue", add.contains("onInspectMedia(url, name); onAdd"))
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
