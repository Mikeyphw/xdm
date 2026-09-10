package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ListQuickActionsFinalSealContractTest {
    private val root = androidRoot()

    @Test
    fun downloadCardsExposePlannerOwnedContextualShortcutsWithoutBypassingConfirmation() {
        val planner = source("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DownloadActionPlanner.kt")
        val row = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadRow.kt")
        val screen = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadsScreen.kt")

        assertTrue(planner.contains("fun quickActionsFor(download: Download"))
        assertTrue(planner.contains("DownloadActionKind.Cancel"))
        assertTrue(planner.contains("DownloadActionKind.RefreshLink"))
        assertTrue(planner.contains("DownloadActionKind.ShareFile"))
        assertTrue(planner.contains("DownloadActionKind.DeleteRecord"))
        assertTrue(row.contains("DownloadActionPlanner.quickActionsFor(download, actionContext)"))
        assertTrue(row.contains("quickActions.forEach"))
        assertTrue(row.contains("onQuickAction: (DownloadAction) -> Unit"))
        assertTrue(screen.contains("onQuickAction = { download, action -> runDownloadAction(download, action) }"))
        assertTrue(screen.contains("action.requiresConfirmation"))
        assertTrue(screen.contains("confirmationAction = action"))
    }

    @Test
    fun mediaCardsExposeEditCancelManageShareAndConfirmedRemoval() {
        val capture = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/media/MediaCaptureCard.kt")
        val inbox = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/media/MediaInboxScreen.kt")
        val library = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/library/MediaLibraryScreen.kt")
        val app = source("app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApp.kt")

        assertTrue(capture.contains("Text(\"Edit\")"))
        assertTrue(capture.contains("Text(\"Remove\")"))
        assertTrue(capture.contains("Remove captured media?"))
        assertTrue(inbox.contains("onCancelDownload: (Download) -> Unit"))
        assertTrue(inbox.contains("onOpenDownload: (Download) -> Unit"))
        assertTrue(inbox.contains("Text(\"Cancel\")"))
        assertTrue(inbox.contains("Text(\"Manage\")"))
        assertTrue(app.contains("onCancelDownload = viewModel::cancelDownload"))
        assertTrue(app.contains("viewModel.navigate(AppRoute.Downloads)"))
        assertTrue(library.contains("Text(\"Share\")"))
        assertTrue(library.contains("Text(\"Manage\")"))
        assertTrue(library.contains("Remove from Library?"))
        assertTrue(library.contains("downloaded file is kept on your device"))
    }

    @Test
    fun finalSealCarriesEveryRequestedExperiencePromiseAndOwnsValidationClosure() {
        val manifest = source("PROJECT_MANIFEST.json")
        val gate = source("tools/run-final-release-gate.sh")

        assertTrue(manifest.contains("\"list_quick_actions_final_seal_act01_act02\""))
        assertTrue(manifest.contains("\"roadmap_promises_complete\": true"))
        assertTrue(manifest.contains("\"validation_deferred\": false"))
        assertTrue(manifest.contains("\"full_validation_required\": true"))
        assertTrue(gate.contains("validate-observability-problem-reporting.py"))
        assertTrue(gate.contains("validate-media-thumbnail-mime-presentation.py"))
        assertTrue(gate.contains("validate-live-locator-title-naming.py"))
        assertTrue(gate.contains("validate-list-quick-actions-final-seal.py"))
    }

    private fun source(relative: String): String = File(root, relative).readText()

    private fun androidRoot(): File = generateSequence(File(requireNotNull(System.getProperty("user.dir"))).canonicalFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile && File(it, "app/src/main").isDirectory }
}
