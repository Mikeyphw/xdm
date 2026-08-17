#!/usr/bin/env python3
"""Source contract for combined remediation roadmap Overlays 11 + 12.

This deliberately checks ownership/security and UI-behavior boundaries rather than exact layouts.
It is safe to run without Gradle and complements the final campaign validation.
"""
from __future__ import annotations

import argparse
from pathlib import Path
import sys


def locate_android_root(start: Path) -> Path:
    start = start.resolve()
    candidates = [start, start / "app" / "XDM.Android"]
    candidates += [parent / "app" / "XDM.Android" for parent in start.parents]
    for candidate in candidates:
        if (candidate / "settings.gradle.kts").is_file() and (candidate / "app").is_dir():
            return candidate
    raise SystemExit(f"Unable to locate app/XDM.Android from {start}")


def read(root: Path, relative: str, errors: list[str]) -> str:
    path = root / relative
    if not path.is_file():
        errors.append(f"missing file: {relative}")
        return ""
    data = path.read_bytes()
    if b"\x00" in data:
        errors.append(f"embedded NUL byte: {relative}")
    return data.decode("utf-8")


def require(condition: bool, message: str, errors: list[str]) -> None:
    if not condition:
        errors.append(message)


def require_all(source: str, markers: tuple[str, ...], label: str, errors: list[str]) -> None:
    for marker in markers:
        require(marker in source, f"{label} missing marker: {marker}", errors)


def validate(root: Path) -> list[str]:
    errors: list[str] = []

    spec = read(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/termux/PostProcessingExecutionModels.kt", errors)
    automation = read(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/termux/PostProcessingAutomationManager.kt", errors)
    manager = read(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/termux/TermuxMediaPipelineManager.kt", errors)
    shell = read(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/termux/TermuxShellTemplates.kt", errors)
    bridge_manager = read(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/termux/TermuxBridgeManager.kt", errors)
    root_auth = read(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/termux/TermuxRootActionAuthorizer.kt", errors)
    pp_dao = read(root, "persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/PostProcessingDao.kt", errors)
    view_model = read(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt", errors)

    # M-012 / M-032 / post-processing M-020 / Termux M-046.
    require_all(spec, (
        "sessionPrimaryVariantId", "sessionVariantIds", "enumValueOrThrow",
        "output.getBoolean(\"deleteOriginalAfterPublish\")", "tools.getString(index)",
        "arguments.getString(it)", "spec.formatSelector.orEmpty()",
        "spec.sessionPrimaryVariantId.orEmpty()", "spec.sessionVariantIds.sorted()",
    ), "immutable Termux execution specification", errors)
    require_all(automation, (
        "val isYtDlp = action.kind in setOf", "https://xdm.invalid/media-session/",
        "downloadId = capture.downloadId.takeUnless { isYtDlp }",
        "sessionPrimaryVariantId = capture.selectedVariantId.takeIf { isYtDlp }",
    ), "automatic media post-processing ownership", errors)
    require_all(manager, (
        "expectedProcessToken == null || result.processToken != expectedProcessToken",
        "val durableControl = dao.requestControl", "if (durableControl == 0)",
        "val attached = dao.attachRun", "if (attached == 0)",
        "TermuxProcessControlAction.ForceCancel", "prepareDownloadGraphDeletion",
        "clearTerminalBridgeUris", "val sessionBound =", "if (sessionBound)",
        "https://xdm.invalid/media-session/", "MediaRequestHandoffStore",
        "spec.kind == PostProcessingActionKind.YtDlpDownload && !spec.captureId.isNullOrBlank()",
    ), "Termux durable ownership/recovery", errors)
    require("probeUrl" not in manager, "Termux durable spec must not persist the old media probe URL", errors)
    require_all(pp_dao, ("jobsForDownloadGraph", "clearTerminalBridgeUris"), "post-processing graph DAO", errors)
    require_all(shell, (
        "XDM_PAYLOAD_FIFO", "mkfifo", "chmod 600",
        "XDM_YTDLP_CONFIG", "XDM_YTDLP_URLS", "--config-locations", "--batch-file",
        "managed transient session required", "privacyAuditScript", "FINDINGS=", "STALE=",
        "STALE_NODES", "SHARED_FINDINGS", "-name '.xdm-*'", "%(.{title,ext,duration,is_live,vcodec,acodec",
    ), "managed Termux shell", errors)
    managed_launcher = shell.split("private fun managedLauncher", 1)[-1].split("private fun managedPostProcessScript", 1)[0]
    require("payload.sh" not in managed_launcher, "managed Termux execution must not persist payload.sh", errors)
    raw_post = shell.split("private fun postProcessScript", 1)[-1]
    require("yt-dlp -J --no-warnings" not in raw_post, "raw post-processing must not execute yt-dlp outside the managed transient-session bridge", errors)
    require(" -J --no-warnings > \"${'$'}XDM_METADATA\"" not in shell, "shared metadata bridge must not receive raw yt-dlp JSON", errors)
    require("*XDM*|*Download*|*download*" not in shell, "root/filesystem ownership must not be inferred from path substrings", errors)
    require("PID-only ownership is not accepted" in shell, "PID-only root process ownership must fail closed", errors)
    require("status NOT IN ('Completed', 'Failed', 'Cancelled', 'TimedOut')" in pp_dao, "stale control/result callbacks must not resurrect terminal jobs", errors)
    require_all(root_auth, (
        "File(path).canonicalFile.path", "allowedRoots.any", "target == root || target.startsWith(root + File.separator)",
    ), "canonical root action authorization", errors)
    require("contains(\"/Android/data/\")" not in root_auth, "root authorization must not approve paths by substring", errors)
    require_all(bridge_manager, ("runPrivacyAudit", "XdmTermuxCommand.PrivacyAudit", "TermuxRootActionAuthorizer.authorize"), "Termux privacy/root manager", errors)
    require_all(view_model, ("runTermuxPrivacyAudit", "prepareDownloadGraphDeletion"), "Termux UI/deletion wiring", errors)

    # M-037 / M-039 navigation and ephemeral Add review state.
    route = read(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/AppRoute.kt", errors)
    prefs = read(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/UserPreferencesStore.kt", errors)
    app = read(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApp.kt", errors)
    add = read(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/intake/AddDownloadSurface.kt", errors)
    require_all(route, ('"Add" -> Downloads',), "route restoration", errors)
    require_all(prefs, (
        "lastActivityPanel", "lastSettingsPanel", "selectedDownloadDetailId",
        "selectedRecoveryDownloadId", "selectedRecoveryAction", "if (route == AppRoute.Add) return",
        "setDownloadsNavigation", "setActivityNavigation", "setSettingsNavigation",
    ), "nested navigation persistence", errors)
    require_all(app, (
        "BackHandler(enabled = state.route == AppRoute.Add)", "viewModel.dismissExternalAddDraft()",
        "visible = state.route == AppRoute.Add", "onDetailSelectionChanged = viewModel::selectDownloadDetail",
        "AppRoute.Downloads -> DownloadsScreen(", "requestedDetailDownloadId = state.selectedDownloadDetailId",
    ), "Add dismissal/navigation wiring", errors)
    dismiss = view_model.split("fun dismissExternalAddDraft()", 1)[-1].split("\n    fun ", 1)[0]
    require_all(dismiss, ("externalAddDraft.value = null", "AutomationCommandStatus.Rejected", "User dismissed Add Download review", "UserDeclined"), "external Add dismissal state", errors)
    require_all(add, ("imePadding()", "externalDraftId", "reviewConfirmed = false", "allowFallback = true", "XdmScreenTags.BrowserSessionHealth", "XdmScreenTags.EngineEscalation"), "Add review reset/IME/accessibility", errors)
    require(add.count("XdmScreenTags.AddReview") == 1, "Add/review semantics tags must be unique per surface", errors)

    # M-038 adaptive layout must use actual pane and folding geometry.
    window = read(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/XdmWindowClass.kt", errors)
    fold = read(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/XdmFoldPostureSource.kt", errors)
    downloads = read(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadsScreen.kt", errors)
    primitives = read(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/XdmPrimitives.kt", errors)
    require_all(fold, ("feature.bounds", "feature.isSeparating", "FoldingFeature.Orientation.VERTICAL", "bounds.width().toDp()", "bounds.height().toDp()", "bounds.left.toDp()", "bounds.right.toDp()", "bounds.top.toDp()", "bounds.bottom.toDp()"), "fold geometry", errors)
    require_all(window, ("minimumPaneGap", "hasVerticalSeparatingFold", "hasHorizontalSeparatingFold", "allowsTwoPaneDownloadsFor", "verticalHingeSplitFor", "preferredFoldSafePane"), "adaptive policy", errors)
    require_all(downloads, ("twoPaneLayoutActive", "measuredTwoPaneDownloads", "positionInWindow()", "hingeSplit.leftPaneWidth", "hingeSplit.rightPaneWidth", "visible = !twoPaneLayoutActive && detailDownload != null", "onDetailSelectionChanged(detailDownloadId)"), "measured Downloads layout", errors)
    require_all(primitives, ("preferredFoldSafePane", "XdmFoldSafePaneEdge.Start", "XdmFoldSafePaneEdge.End", "XdmFoldSafePaneEdge.Top", "XdmFoldSafePaneEdge.Bottom"), "hinge-safe adaptive sheet policy", errors)

    # M-040 action truth and stale destructive actions.
    truth = read(root, "core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DownloadUiTruth.kt", errors)
    planner = read(root, "core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DownloadActionPlanner.kt", errors)
    require_all(truth, (
        "exactRequestReplayAvailable", "fun verificationFailed()", "!verificationFailed()",
        "CompletedArtifactHealth.ProviderChanged", "CompletedArtifactHealth.SizeMismatch",
    ), "download UI truth", errors)
    require_all(planner, ("batchActionsFor", "context.exactRequestReplayAvailable", "firstOrNull { it.enabled"), "download action planner", errors)
    require_all(downloads, ("val freshAction = DownloadActionPlanner.actionsFor", "action is no longer available", "executeDownloadAction(confirmedDownload, freshAction)"), "stale confirmation defense", errors)
    require_all(view_model, (
        "fun selectDownloadDetail(downloadId: String?)", "preferences.setDownloadsNavigation(normalized)",
        "val currentForAction = kotlinx.coroutines.withContext(Dispatchers.IO) { repository.findDownload(download.id) }",
        "downloadArtifactActionManager.delete(currentForAction)", "downloadArtifactActionManager.rename(currentForAction, requestedName)",
    ), "fresh completed-artifact actions", errors)

    # M-041 destination and saved-search semantics.
    desktop = read(root, "core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DesktopParityModels.kt", errors)
    organize = read(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/OrganizeDownloadsSheet.kt", errors)
    settings = read(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/settings/AdvancedDownloadSettingsScreen.kt", errors)
    require_all(desktop, (
        "host == domain || host.endsWith(\".$domain\")", "DestinationRuleMatch.Fallback -> false",
        "enabled.firstOrNull { it.match == DestinationRuleMatch.Fallback }",
    ), "destination rule matching", errors)
    require(desktop.index("DestinationRuleMatch.Fallback -> false") < desktop.index("enabled.firstOrNull { it.match == DestinationRuleMatch.Fallback }"), "fallback destination rule must be considered only after specific rules", errors)
    require_all(settings, ("destinationRuleDestination", "viewModel.saveDestinationRule", "destinationRuleDestination.isNotBlank()"), "explicit destination rule target", errors)
    require_all(organize, ("onApplySavedSearch", 'Text("Apply")', "DownloadActionPlanner.batchActionsFor"), "saved search / truthful batch actions", errors)
    require_all(downloads, ("onApplySavedSearch = { search ->", "includeArchived = search.includeArchived", "search.query"), "saved search application", errors)

    # M-052 behavioral/static accessibility contracts.
    phase9 = read(root, "tools/validate-bug-hunt-phase9-accessibility-adaptive-layout.py", errors)
    phase9_test = read(root, "app/src/test/kotlin/com/mikeyphw/xdm/android/Phase9AccessibilityGapClosurePolicyTest.kt", errors)
    require_all(phase9, ("performClick", "Modifier.fillMaxSize().imePadding()", "feature.bounds", "twoPaneLayoutActive"), "Phase-9 behavioral validator", errors)
    require_all(phase9_test, ("verticalSeparatingHingeUsesRealGapAndCanKeepTwoPaneDownloads", "tabletopPostureAvoidsSideBySideDownloadPanes", "measuredPaneWidthControlsTwoPaneEligibility"), "adaptive policy tests", errors)

    for source in root.rglob("*"):
        if source.is_file() and source.suffix in {".kt", ".kts", ".xml", ".json", ".py", ".md", ".sh"} and b"\x00" in source.read_bytes():
            errors.append(f"embedded NUL byte: {source.relative_to(root)}")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("root", nargs="?", default=".")
    args = parser.parse_args()
    root = locate_android_root(Path(args.root))
    errors = validate(root)
    if errors:
        print(f"Phase 11+12 validation FAILED ({len(errors)} issue(s))")
        for item in errors:
            print(f"- {item}")
        return 1
    print("Phase 11+12 validation PASSED")
    print(f"Android root: {root}")
    print("Termux/post-processing ownership, transient secret handling, graph cleanup, filesystem privacy, navigation restoration, adaptive geometry, action truth, destination/search semantics, and accessibility behavior contracts are present.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
