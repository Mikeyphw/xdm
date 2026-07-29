package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldBugfixDownloadActionsStorageContractTest {
    private val root = androidRoot()

    @Test
    fun downloadItemMenuActionsNoLongerCollapseIntoDetails() {
        val screen = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadsScreen.kt")
        assertFalse(
            "File/share/delete/cancel actions must not be grouped into a single details fallback.",
            screen.contains("DownloadActionKind.OpenFile,\n        DownloadActionKind.OpenDetails"),
        )
        listOf(
            "DownloadActionKind.OpenFile -> openCompletedFile",
            "DownloadActionKind.ShareFile -> shareCompletedFile",
            "DownloadActionKind.Cancel -> onCancelDownload",
            "DownloadActionKind.Redownload -> onRedownload",
            "DownloadActionKind.DeleteRecord -> onDeleteRecord",
            "DownloadActionKind.DeleteFileAndRecord -> deleteSavedFileAndRecord",
            "DownloadActionKind.MoveToTop,",
            "-> onMoveDownloadInQueue(download, action.kind)",
        ).forEach { expected -> assertTrue("Missing action dispatch: $expected", screen.contains(expected)) }
    }

    @Test
    fun destinationCopyUsesHumanLabelsInNormalUi() {
        val details = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadDetails.kt")
        val labels = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadDestinationUi.kt")
        assertFalse(details.contains("DownloadDetailRow(\"Save to\", download.destinationUri)"))
        assertTrue(details.contains("DownloadDetailRow(\"Save to\", destinationUiLabel(download.destinationUri))"))
        assertTrue(details.contains("DownloadDetailRow(\"Storage\", destinationUiHint(download.destinationUri))"))
        assertTrue(labels.contains("content://"))
        assertTrue(labels.contains("Saved in Android shared storage"))
        assertTrue(labels.contains("Android stores shared files with access-safe content links instead of raw paths."))
    }

    @Test
    fun viewModelBacksFieldActions() {
        val app = source("app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApp.kt")
        val viewModel = source("app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt")
        listOf("viewModel::cancelDownload", "viewModel::redownload", "viewModel::moveDownloadInQueue").forEach { expected ->
            assertTrue("Downloads route must pass $expected", app.contains(expected))
        }
        listOf("fun cancelDownload", "fun redownload", "fun moveDownloadInQueue", "transferRuntime.cancel", "repository.saveAll(reprioritized)").forEach { expected ->
            assertTrue("MainViewModel must implement $expected", viewModel.contains(expected))
        }
    }

    private fun source(relative: String): String = File(root, relative).readText()

    private fun androidRoot(): File {
        var cursor = File(System.getProperty("user.dir") ?: ".").canonicalFile
        repeat(8) {
            if (File(cursor, "settings.gradle.kts").isFile && File(cursor, "app/src/main").isDirectory) return cursor
            cursor = cursor.parentFile ?: cursor
        }
        error("Android root not found")
    }
}
