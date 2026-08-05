package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase65DiagnosticExportDownloadActionContractTest {
    @Test
    fun diagnosticsCanBeExportedThroughAndroidShareSheet() {
        val helper = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/common/UiTextHelpers.kt")
        val debugCard = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/developer/DeveloperToolsWorkspace.kt")
        val debugScreen = debugCard
        val settings = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/settings/SettingsScreen.kt")
        val model = source("app/src/main/kotlin/com/mikeyphw/xdm/android/DebugWorkbenchD6RuntimeSelfTestModels.kt")

        assertTrue(helper.contains("shareTextReport"))
        assertTrue(helper.contains("Intent.ACTION_SEND"))
        assertTrue(debugCard.contains("Export self-test report"))
        assertTrue(debugScreen.contains("Export support report"))
        assertTrue(settings.contains("Export support report"))
        assertTrue(model.contains("Ran check IDs:"))
        assertTrue(model.contains("[${'$'}{check.id}]"))
    }

    @Test
    fun downloadListActionsExposeCancelAndDeleteFromThreeDotMenu() {
        val planner = source("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DownloadActionPlanner.kt")
        val screen = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadsScreen.kt")
        val viewModel = source("app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt")
        val repository = source("persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/DownloadRepository.kt")
        val dao = source("persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/DownloadDao.kt")

        assertTrue(planner.contains("deleteHistory(download, label = \"Delete download entry\")"))
        assertTrue(planner.contains("DownloadActionKind.Cancel"))
        assertTrue(screen.contains("DownloadActionKind.Cancel -> onCancelDownload(download)"))
        assertTrue(screen.contains("DownloadActionKind.DeleteRecord -> onDeleteRecord(download)"))
        assertTrue(viewModel.contains("fun cancelDownload(download: Download)"))
        assertTrue(viewModel.contains("runCatching { transferRuntime.cancel(download.id) }"))
        assertTrue(viewModel.contains("repository.deleteDownload"))
        assertTrue(repository.contains("database.downloadGraphTransactionDao().deleteDownloadGraph(id)"))
        assertTrue(dao.contains("DELETE FROM recovery_records WHERE downloadId = :downloadId"))
    }

    @Test
    fun phase65KeepsRcBoundaries() {
        val manifest = source("PROJECT_MANIFEST.json")
        assertTrue(manifest.contains("\"field_bugfix_phase_65\""))
        assertTrue(manifest.contains("\"room_schema_unchanged\": 14"))
        assertTrue(manifest.contains("\"automatic_transfer_start\": false"))
        assertTrue(manifest.contains("\"automatic_upload\": false"))
        assertTrue(manifest.contains("\"all_files_permission_added\": false"))
        assertFalse(manifest.contains("MANAGE_EXTERNAL_STORAGE"))
    }

    private fun source(path: String): String = File(projectRoot(), path).readText()

    private fun projectRoot(): File {
        var current = File(System.getProperty("user.dir")).canonicalFile
        while (true) {
            if (File(current, "PROJECT_MANIFEST.json").isFile) {
                return current
            }
            val nestedProject = File(current, "app/XDM.Android/PROJECT_MANIFEST.json")
            if (nestedProject.isFile) {
                return nestedProject.parentFile
            }
            val parent = current.parentFile ?: break
            current = parent.canonicalFile
        }
        throw IllegalStateException("Could not locate XDM Android project root")
    }
}
