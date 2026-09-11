#!/usr/bin/env python3
from pathlib import Path
import json
import sys

ROOT = Path(__file__).resolve().parents[1]
errors = []


def read(rel: str) -> str:
    try:
        return (ROOT / rel).read_text(encoding="utf-8")
    except Exception as exc:
        errors.append(f"cannot read {rel}: {exc}")
        return ""


def need(condition: bool, message: str) -> None:
    if not condition:
        errors.append(message)

planner = read("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DownloadActionPlanner.kt")
planner_test = read("core-model/src/test/kotlin/com/mikeyphw/xdm/android/model/DownloadActionPlannerTest.kt")
row = read("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadRow.kt")
downloads = read("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadsScreen.kt")
capture = read("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/media/MediaCaptureCard.kt")
inbox = read("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/media/MediaInboxScreen.kt")
library = read("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/library/MediaLibraryScreen.kt")
app = read("app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApp.kt")
contract = read("app/src/test/kotlin/com/mikeyphw/xdm/android/ListQuickActionsFinalSealContractTest.kt")
gate = read("tools/run-final-release-gate.sh")
common_gate = read("tools/run-final-common-validation.sh")
report = read("XDM_LIST_QUICK_ACTIONS_FINAL_SEAL_ACT01_ACT02_REPORT.md")
manifest = json.loads(read("PROJECT_MANIFEST.json") or "{}")
entry = manifest.get("list_quick_actions_final_seal_act01_act02", {})

# Download-card action ergonomics.
need("fun quickActionsFor(download: Download" in planner, "download planner missing quickActionsFor")
for token in (
    "DownloadActionKind.Cancel",
    "DownloadActionKind.RefreshLink",
    "DownloadActionKind.ShareFile",
    "DownloadActionKind.Rename",
    "DownloadActionKind.DeleteRecord",
):
    need(token in planner, f"download quick-action matrix missing {token}")
need("distinctBy(DownloadAction::kind).take(2)" in planner, "quick-action set must stay bounded to two unique actions")
need("quickActionsExposePrimaryPlusContextualShortcut" in planner_test, "quick-action planner regression test missing")
need("DownloadActionPlanner.quickActionsFor(download, actionContext)" in row, "download row must consume planner quick actions")
need("quickActions.forEach" in row and "onQuickAction: (DownloadAction) -> Unit" in row,
     "download row must render planner-owned direct actions")
need("onQuickAction = { download, action -> runDownloadAction(download, action) }" in downloads,
     "downloads screen must route quick actions through canonical action execution")
need("action.requiresConfirmation" in downloads and "confirmationAction = action" in downloads,
     "direct destructive actions must retain confirmation")

# Captured/recent media ergonomics.
need('Text("Edit")' in capture and 'Text("Remove")' in capture, "captured-media cards must expose Edit and Remove")
need("Remove captured media?" in capture and "Existing downloaded files are not deleted" in capture,
     "captured-media direct removal must be explicit and non-file-destructive")
need("onCancelDownload: (Download) -> Unit" in inbox and "onOpenDownload: (Download) -> Unit" in inbox,
     "recently queued media must expose cancel/manage callbacks")
need('Text("Cancel")' in inbox and 'Text("Manage")' in inbox,
     "recently queued media must render direct Cancel and Manage actions")
need("onCancelDownload = viewModel::cancelDownload" in app and "viewModel.navigate(AppRoute.Downloads)" in app,
     "Media screen must wire cancel and manage to actual download runtime/details")

# Library ergonomics.
need('Text("Share")' in library and 'Text("Manage")' in library and 'Text("Remove")' in library,
     "Library cards must expose Share, Manage, and Remove")
need("Remove from Library?" in library and "downloaded file is kept on your device" in library,
     "Library direct removal must confirm record-only behavior")

# Roadmap carry-forward and final validation ownership.
for validator in (
    "tools/validate-observability-problem-reporting.py",
    "tools/validate-media-thumbnail-mime-presentation.py",
    "tools/validate-live-locator-title-naming.py",
    "tools/validate-list-quick-actions-final-seal.py",
):
    need(validator in gate, f"final release gate missing {validator}")
for task in (
    ":app:finalRemediationStaticGate",
    ":app:compileDebugKotlin",
    ":core-model:test",
    ":media:test",
    ":app:testDebugUnitTest",
    ":app:lintDebug",
    ":browser-extension:test",
    ":browser-extension:jsTest",
    ":browser-extension:validateFirefoxExtension",
    ":app:checkBrowserIntegration",
    "assembleDebug",
    ":app:assembleDebugAndroidTest",
):
    need(task in common_gate, f"common validation runner missing {task}")
need("finalSealCarriesEveryRequestedExperiencePromiseAndOwnsValidationClosure" in contract,
     "ACT final contract test missing roadmap closure assertion")
need("Roadmap promise audit" in report and "No requested promise remains unimplemented" in report,
     "ACT report must contain explicit promise closure audit")

need(entry.get("status") == "implemented_final_seal", "manifest must mark ACT01/ACT02 as final seal")
need(entry.get("depends_on") == "xdm_android_live_locator_title_naming_v1.zip", "manifest ACT dependency is wrong")
need(entry.get("download_quick_actions_planner_owned") is True and entry.get("download_quick_action_limit") == 2,
     "manifest must record bounded planner-owned download shortcuts")
need(entry.get("captured_media_direct_edit") is True and entry.get("recent_media_direct_cancel") is True,
     "manifest must record Media action ergonomics")
need(entry.get("library_direct_share") is True and entry.get("library_direct_remove") is True,
     "manifest must record Library action ergonomics")
need(entry.get("roadmap_promises_complete") is True, "manifest must seal all requested roadmap promises")
promises = entry.get("roadmap_promises", {})
for key in (
    "proper_logging_and_debug_logging",
    "problem_notifications",
    "download_media_thumbnails",
    "captured_media_thumbnails",
    "webview_capture_artwork",
    "extension_capture_artwork",
    "media_captured_list_artwork",
    "webview_improvements",
    "page_title_default_naming_webview",
    "page_title_default_naming_extension",
    "mime_backed_fallback_icons",
    "easier_card_actions",
):
    need(promises.get(key) is True, f"manifest promise audit missing {key}")
need(entry.get("full_validation_required") is True and entry.get("validation_deferred") is False,
     "final seal must require non-deferred full validation")
need(len(entry.get("required_final_validation_tasks", [])) >= 20,
     "final seal must retain the complete explicit Gradle validation task matrix")
need(entry.get("room_schema_unchanged") == 21, "ACT final seal must not change Room schema")
need(entry.get("next_overlay") is None, "ACT final seal must close this roadmap")
need(manifest.get("current_experience_polish_authority") in {"list_quick_actions_final_seal_act01_act02", "media_parity04_browser_ux_userscripts_notifications_release_seal"},
     "manifest must identify ACT01/ACT02 as current experience-polish authority")

if errors:
    print("ACT01/ACT02 list quick-actions final seal validator failed:")
    for error in errors:
        print(f"- {error}")
    sys.exit(1)
print("ACT01/ACT02 list quick-actions final seal validator: OK")
