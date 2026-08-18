package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemediationPhase11_12ContractTest {
    private val root = androidRoot()

    @Test
    fun termuxOwnershipFailsClosedAndTransientMediaSecretsNeverEnterDurableSpec() {
        val models = source("app/src/main/kotlin/com/mikeyphw/xdm/android/termux/PostProcessingExecutionModels.kt")
        val automation = source("app/src/main/kotlin/com/mikeyphw/xdm/android/termux/PostProcessingAutomationManager.kt")
        val manager = source("app/src/main/kotlin/com/mikeyphw/xdm/android/termux/TermuxMediaPipelineManager.kt")
        val shell = source("app/src/main/kotlin/com/mikeyphw/xdm/android/termux/TermuxShellTemplates.kt")

        assertTrue(models.contains("sessionPrimaryVariantId"))
        assertTrue(models.contains("sessionVariantIds.sorted().joinToString"))
        assertTrue(models.contains("enumValueOrThrow"))
        assertTrue(automation.contains("https://xdm.invalid/media-session/"))
        assertTrue(automation.contains("downloadId = capture.downloadId.takeUnless { isYtDlp }"))
        assertTrue(manager.contains("expectedProcessToken == null || result.processToken != expectedProcessToken"))
        assertTrue(manager.contains("if (durableControl == 0)"))
        assertTrue(manager.contains("if (attached == 0)"))
        assertTrue(manager.contains("TermuxProcessControlAction.ForceCancel"))
        assertTrue(manager.contains("val sessionBound ="))
        assertTrue(manager.contains("https://xdm.invalid/media-session/"))
        assertFalse(manager.contains("probeUrl"))
        assertTrue(shell.contains("XDM_PAYLOAD_FIFO"))
        assertTrue(shell.contains("XDM_YTDLP_CONFIG"))
        assertTrue(shell.contains("--config-locations"))
        assertTrue(shell.contains("--batch-file"))
        assertTrue(shell.contains("--print") && shell.contains("formats.:.{format_id") && shell.contains("#j"))
        assertFalse(shell.contains(" -J --no-warnings > \"${'$'}XDM_METADATA\""))
        assertTrue(shell.contains("managed transient session required"))
    }

    @Test
    fun graphDeletionRootActionsAndPrivacyAuditRespectRealOwnershipBoundaries() {
        val manager = source("app/src/main/kotlin/com/mikeyphw/xdm/android/termux/TermuxMediaPipelineManager.kt")
        val dao = source("persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/PostProcessingDao.kt")
        val bridge = source("app/src/main/kotlin/com/mikeyphw/xdm/android/termux/TermuxBridgeManager.kt")
        val rootAuth = source("app/src/main/kotlin/com/mikeyphw/xdm/android/termux/TermuxRootActionAuthorizer.kt")
        val shell = source("app/src/main/kotlin/com/mikeyphw/xdm/android/termux/TermuxShellTemplates.kt")

        assertTrue(manager.contains("prepareDownloadGraphDeletion"))
        assertTrue(manager.contains("clearTerminalBridgeUris"))
        assertTrue(dao.contains("jobsForDownloadGraph"))
        assertTrue(dao.contains("clearTerminalBridgeUris"))
        assertTrue(bridge.contains("TermuxRootActionAuthorizer.authorize"))
        assertTrue(bridge.contains("runPrivacyAudit"))
        assertTrue(rootAuth.contains("File(path).canonicalFile.path"))
        assertTrue(rootAuth.contains("target == root || target.startsWith(root + File.separator)"))
        assertFalse(rootAuth.contains("contains(\"/Android/data/\")"))
        assertTrue(shell.contains("privacyAuditScript"))
        assertTrue(shell.contains("STALE_NODES"))
        assertTrue(shell.contains("SHARED_FINDINGS"))
        assertTrue(shell.contains("-name '.xdm-*'"))
        assertFalse(shell.contains("*XDM*|*Download*|*download*"))
        assertTrue(shell.contains("PID-only ownership is not accepted"))
        assertTrue(shell.contains("XDM_PRIVACY_AUDIT"))
        assertTrue(dao.contains("status NOT IN ('Completed', 'Failed', 'Cancelled', 'TimedOut')"))
    }

    @Test
    fun navigationRestorationAndAddDismissalPreserveNestedTruthButNeverPersistAdd() {
        val route = source("app/src/main/kotlin/com/mikeyphw/xdm/android/AppRoute.kt")
        val prefs = source("app/src/main/kotlin/com/mikeyphw/xdm/android/UserPreferencesStore.kt")
        val app = source("app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApp.kt")
        val viewModel = source("app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt")
        val add = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/intake/AddDownloadSurface.kt")

        assertTrue(route.contains("\"Add\" -> Downloads"))
        assertTrue(prefs.contains("if (route == AppRoute.Add) return"))
        assertTrue(prefs.contains("selectedDownloadDetailId"))
        assertTrue(prefs.contains("selectedRecoveryDownloadId"))
        assertTrue(prefs.contains("selectedRecoveryAction"))
        assertTrue(app.contains("viewModel.dismissExternalAddDraft()"))
        assertTrue(app.contains("onDetailSelectionChanged = viewModel::selectDownloadDetail"))
        assertTrue(app.contains("AppRoute.Downloads -> DownloadsScreen("))
        assertTrue(app.contains("requestedDetailDownloadId = state.selectedDownloadDetailId"))
        assertTrue(viewModel.contains("fun selectDownloadDetail(downloadId: String?)"))
        assertTrue(viewModel.contains("preferences.setDownloadsNavigation(normalized)"))
        assertTrue(viewModel.contains("status = AutomationCommandStatus.Rejected"))
        assertTrue(viewModel.contains("rejectionReason = AutomationRejectionReason.UserDeclined"))
        assertTrue(add.contains("Modifier.fillMaxSize().imePadding()"))
        assertTrue(add.contains("reviewConfirmed = false"))
        assertTrue(add.contains("XdmScreenTags.BrowserSessionHealth"))
        assertTrue(add.contains("XdmScreenTags.EngineEscalation"))
        assertTrue(add.split("XdmScreenTags.AddReview").size - 1 == 1)
    }

    @Test
    fun adaptiveLayoutUsesMeasuredPaneAndRealFoldGeometry() {
        val fold = source("app/src/main/kotlin/com/mikeyphw/xdm/android/XdmFoldPostureSource.kt")
        val window = source("app/src/main/kotlin/com/mikeyphw/xdm/android/XdmWindowClass.kt")
        val downloads = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadsScreen.kt")

        assertTrue(fold.contains("feature.bounds"))
        assertTrue(fold.contains("feature.orientation == FoldingFeature.Orientation.VERTICAL"))
        assertTrue(fold.contains("bounds.width().toDp()"))
        assertTrue(fold.contains("bounds.left.toDp()"))
        assertTrue(fold.contains("bounds.right.toDp()"))
        assertTrue(window.contains("hasVerticalSeparatingFold"))
        assertTrue(window.contains("hasHorizontalSeparatingFold"))
        assertTrue(window.contains("minimumPaneGap"))
        assertTrue(window.contains("verticalHingeSplitFor"))
        assertTrue(window.contains("preferredFoldSafePane"))
        assertTrue(downloads.contains("positionInWindow()"))
        assertTrue(downloads.contains("hingeSplit.leftPaneWidth"))
        assertTrue(downloads.contains("hingeSplit.rightPaneWidth"))
        assertTrue(downloads.contains("twoPaneLayoutActive = measuredTwoPaneDownloads"))
    }

    @Test
    fun actionsDestinationRulesAndSavedSearchesAreBehaviorallyTruthful() {
        val truth = source("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DownloadUiTruth.kt")
        val planner = source("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DownloadActionPlanner.kt")
        val desktop = source("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DesktopParityModels.kt")
        val downloads = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadsScreen.kt")
        val organize = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/OrganizeDownloadsSheet.kt")
        val viewModel = source("app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt")

        assertTrue(truth.contains("fun verificationFailed()"))
        assertTrue(truth.contains("!verificationFailed()"))
        assertTrue(truth.contains("CompletedArtifactHealth.ProviderChanged"))
        assertTrue(truth.contains("CompletedArtifactHealth.SizeMismatch"))
        assertTrue(planner.contains("context.exactRequestReplayAvailable"))
        assertTrue(planner.contains("batchActionsFor"))
        assertTrue(downloads.contains("val freshAction = DownloadActionPlanner.actionsFor"))
        assertTrue(downloads.contains("That action is no longer available because the download changed."))
        assertTrue(viewModel.contains("downloadArtifactActionManager.delete(currentForAction)"))
        assertTrue(viewModel.contains("downloadArtifactActionManager.rename(currentForAction, requestedName)"))
        assertTrue(desktop.contains("host == domain || host.endsWith(\".${'$'}domain\")"))
        assertTrue(desktop.contains("DestinationRuleMatch.Fallback -> false"))
        assertTrue(organize.contains("onApplySavedSearch"))
        assertTrue(organize.contains("DownloadActionPlanner.batchActionsFor"))
    }

    private fun source(relative: String): String = File(root, relative).readText()

    private fun androidRoot(): File {
        var cursor = File(System.getProperty("user.dir") ?: ".").canonicalFile
        repeat(8) {
            if (File(cursor, "settings.gradle.kts").isFile && File(cursor, "app/src/main").isDirectory) return cursor
            cursor = cursor.parentFile ?: return@repeat
        }
        error("Unable to locate XDM Android root")
    }
}
