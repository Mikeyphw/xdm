package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeFoundationPhase57_58ContractTest {
    private val root = projectRoot()

    @Test
    fun destinationPublicationFailuresPreserveRecoveryContract() {
        val provider = source("storage/src/main/kotlin/com/mikeyphw/xdm/android/storage/DestinationProvider.kt")
        val androidWriter = source("storage/src/main/kotlin/com/mikeyphw/xdm/android/storage/AndroidDestinationWriter.kt")
        val fileWriter = source("storage/src/main/kotlin/com/mikeyphw/xdm/android/storage/FileDestinationWriter.kt")
        assertTrue(provider.contains("DestinationPublicationException"))
        assertTrue(provider.contains("stagingPreserved"))
        assertTrue(provider.contains("Retry save after fixing destination access"))
        assertTrue(androidWriter.contains("target.openForCommit()"))
        assertTrue(androidWriter.contains("Content destination could not be opened or created; completed staging bytes retained for retry."))
        assertTrue(androidWriter.contains("incomplete provider output was rolled back when possible"))
        assertTrue(androidWriter.contains("throw DestinationPublicationException"))
        assertTrue(fileWriter.contains("staging preservation was checked before recovery"))
        assertTrue(fileWriter.contains("targetExistedBeforePromotion"))
        assertFalse(androidWriter.contains("File(uri.path)"))
    }

    @Test
    fun finalSaveRecoveryIsActuallyRetryableWithoutRedownloading() {
        val nativeBackend = source("transfer-native/src/main/kotlin/com/mikeyphw/xdm/android/transfer/nativeengine/NativeHttpDownloadBackend.kt")
        val nativeTest = source("transfer-native/src/test/kotlin/com/mikeyphw/xdm/android/transfer/nativeengine/NativeHttpDownloadBackendTest.kt")
        val aria2Backend = source("transfer-aria2/src/main/kotlin/com/mikeyphw/xdm/android/transfer/aria2/EmbeddedAria2Backend.kt")
        val runtime = source("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferExecutionRuntime.kt")
        val planner = source("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DownloadActionPlanner.kt")
        val mediaScreen = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/media/MediaInboxScreen.kt")
        assertTrue(nativeBackend.contains("launchFinalizationRetry(control)"))
        assertTrue(nativeBackend.contains("control.preparedDestination.promote()"))
        assertTrue(nativeBackend.contains("runCatching { checkpointStore.delete"))
        assertTrue(nativeBackend.contains("isFinalSaveRecovery"))
        assertTrue(nativeTest.contains("finalSaveRecoveryRetriesPublicationWithoutNetworkRedownload"))
        assertTrue(nativeTest.contains("server.stop(0)"))
        assertTrue(aria2Backend.contains("MAPPING_FINALIZATION_FAILED"))
        assertTrue(aria2Backend.contains("code = \"DESTINATION_PUBLICATION\""))
        assertTrue(aria2Backend.contains("state = DownloadState.RecoveryRequired"))
        assertTrue(aria2Backend.contains("runCatching { updateMapping(mapping, MAPPING_COMPLETED) }"))
        assertTrue(runtime.contains("mapping.first == BackendType.Native"))
        assertTrue(runtime.contains("current.errorMessage.orEmpty().startsWith(\"Final save failed\")"))
        assertTrue(runtime.contains("backend.resume(mapping.second)"))
        assertTrue(planner.contains("\"Retry save\""))
        assertTrue(planner.contains("preserved completed staging file without intentionally redownloading"))
        assertTrue(mediaScreen.contains("DownloadState.RecoveryRequired -> if (download.errorMessage.orEmpty().startsWith(\"Final save failed\")) \"Retry save\""))
    }

    @Test
    fun mediaUserActionsPublishVisibleSanitizedIntakeFeedbackInsteadOfSilentReturn() {
        val viewModel = source("app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt")
        val screen = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/media/MediaInboxScreen.kt")
        val app = source("app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApp.kt")
        assertTrue(viewModel.contains("MediaIntakeFeedbackKind.NeedsBrowserCapture"))
        assertTrue(viewModel.contains("No media found in the static page probe"))
        assertTrue(viewModel.contains("Firefox capture rejected"))
        assertTrue(viewModel.contains("Firefox capture authentication failed"))
        assertTrue(viewModel.contains("Firefox capture inspection failed"))
        assertTrue(viewModel.contains("runCatching { mediaCaptureIntakePlanner.plan(facts) }"))
        assertTrue(viewModel.contains("No URLs to inspect"))
        assertTrue(viewModel.contains("Checking media again"))
        assertTrue(viewModel.contains("Check again failed"))
        assertTrue(viewModel.contains("Unsupported media URL"))
        assertTrue(viewModel.contains("Media input inspection failed"))
        assertTrue(viewModel.contains("No reviewable media found"))
        assertFalse(viewModel.contains("downloadIntakePlanner.fromManual(url = url, fileName = fileName) ?: return"))
        assertFalse(viewModel.contains("externalMediaReviewPlanner.plan(draft) ?: return"))
        assertFalse(viewModel.contains("if (plan.records.isEmpty()) return@launch"))
        assertFalse(viewModel.contains("if (sniffingPlan.records.isEmpty()) return"))
        assertTrue(viewModel.contains("BrowserBridgeDiagnosticsRedactor.sanitize(feedback.title)"))
        assertTrue(viewModel.contains("BrowserBridgeDiagnosticsRedactor.sanitize(feedback.detail)"))
        assertTrue(viewModel.contains("feedback.diagnostics.map(BrowserBridgeDiagnosticsRedactor::sanitize)"))
        assertTrue(screen.contains("Firefox capture recommended"))
        assertTrue(screen.contains("intakeFeedback.visible"))
        assertTrue(app.contains("intakeFeedback = state.mediaIntakeFeedback"))
    }

    @Test
    fun phase55_56NullableSystemPropertyWarningIsClosed() {
        val contract = source("app/src/test/kotlin/com/mikeyphw/xdm/android/RuntimeFoundationPhase55_56ContractTest.kt")
        assertTrue(contract.contains("System.getProperty(\"user.dir\") ?: \".\""))
        assertFalse(contract.contains("File(System.getProperty(\"user.dir\"))"))
    }

    private fun source(path: String): String = File(root, path).readText()

    private fun projectRoot(): File {
        var cursor = File(System.getProperty("user.dir") ?: ".").canonicalFile
        repeat(8) {
            if (File(cursor, "settings.gradle.kts").isFile && File(cursor, "app/src/main").isDirectory) return cursor
            cursor = cursor.parentFile ?: return@repeat
        }
        error("Unable to locate XDM Android root")
    }
}
