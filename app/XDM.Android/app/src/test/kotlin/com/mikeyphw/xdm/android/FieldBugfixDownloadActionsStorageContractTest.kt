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
            "DownloadActionKind.DeleteRecord -> onDeleteRecord(download)",
            "DownloadActionKind.DeleteFileAndRecord -> onDeleteSavedFile(download, true)",
            "DownloadActionKind.MoveToTop,",
            "-> onMoveDownloadInQueue(download, action.kind)",
        ).forEach { expected -> assertTrue("Missing action dispatch: $expected", screen.contains(expected)) }
    }

    @Test
    fun destinationCopyUsesHumanLabelsInNormalUi() {
        val details = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadDetails.kt")
        val labels = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadDestinationUi.kt")
        assertFalse(details.contains("DownloadDetailRow(\"Saved location\", download.destinationUri)"))
        assertTrue(details.contains("DownloadDetailRow(\"Saved location\", actionContext.artifact.friendlyLocation)"))
        assertTrue(details.contains("DownloadDetailRow(\"Provider\", actionContext.artifact.providerLabel)"))
        assertTrue(labels.contains("content://"))
        assertTrue(labels.contains("Saved in Android shared storage"))
        assertTrue(labels.contains("Android stores shared files with access-safe content links instead of raw paths."))
    }

    @Test
    fun externalBrowserSessionHandoffStaysTransientAndFeedsRuntimeRequests() {
        val intake = source("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DownloadIntake.kt")
        val viewModel = source("app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt")
        val handoff = source("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/MediaRequestHandoffStore.kt")
        val runtime = source("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferExecutionRuntime.kt")
        val native = source("transfer-native/src/main/kotlin/com/mikeyphw/xdm/android/transfer/nativeengine/NativeHttpDownloadBackend.kt")
        assertTrue(intake.contains("val requestHeaders: Map<String, String> = emptyMap()"))
        assertTrue(viewModel.contains("transientSessionHeaders(draft.rawHeaders, draft.pageUrl)"))
        assertTrue(viewModel.contains("MediaRequestHandoffStore.rememberCapture"))
        assertTrue(viewModel.contains("MediaRequestHandoffStore.forgetCapture(record.id)"))
        assertTrue(viewModel.contains("headers = externalSessionHeaders"))
        assertTrue(handoff.contains("process-local handoff") && handoff.contains("captureHandoffs"))
        assertTrue(runtime.contains("headers = mediaHandoff?.headers.orEmpty()"))
        assertTrue(native.contains("applyBrowserLikeDefaults(request)") && native.contains("Authentication required"))
    }

    @Test
    fun viewModelBacksFieldActions() {
        val app = source("app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApp.kt")
        val viewModel = source("app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt")
        listOf("viewModel::cancelDownload", "viewModel::redownload", "viewModel::moveDownloadInQueue", "viewModel::startNow", "viewModel::deleteSavedFile").forEach { expected ->
            assertTrue("Downloads route must pass $expected", app.contains(expected))
        }
        listOf("fun cancelDownload", "fun redownloadPreserving", "fun moveDownloadInQueue", "fun startNow", "fun deleteSavedFile", "transferRuntime.cancel", "repository.saveAll(reprioritized)").forEach { expected ->
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
