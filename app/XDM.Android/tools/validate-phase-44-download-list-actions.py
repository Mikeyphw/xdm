#!/usr/bin/env python3
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
errors: list[str] = []

def read(path: str) -> str:
    file = ROOT / path
    if not file.exists():
        errors.append(f"missing {path}")
        return ""
    return file.read_text(encoding="utf-8")

def require(path: str, needle: str, label: str | None = None) -> None:
    text = read(path)
    if needle not in text:
        errors.append(f"{path} missing {label or needle}")

def reject(path: str, needle: str, label: str | None = None) -> None:
    text = read(path)
    if needle in text:
        errors.append(f"{path} still contains forbidden {label or needle}")

planner = "core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DownloadActionPlanner.kt"
row = "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadRow.kt"
screen = "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadsScreen.kt"
core_test = "core-model/src/test/kotlin/com/mikeyphw/xdm/android/model/DownloadActionPlannerTest.kt"
contract = "app/src/test/kotlin/com/mikeyphw/xdm/android/DownloadListPhase44ActionMenusContractTest.kt"
doc = "docs/architecture/PHASE-44-DOWNLOAD-LIST-ACTION-MODEL-MENUS.md"
manifest = "PROJECT_MANIFEST.json"

for marker in [
    "enum class DownloadActionKind",
    "OpenFile",
    "OpenDetails",
    "ReviewRecovery",
    "MoveToTop",
    "DeleteFileAndRecord",
    "data class DownloadAction",
    "destructive: Boolean = false",
    "requiresConfirmation: Boolean = false",
    "object DownloadActionPlanner",
    "fun actionsFor(download: Download): List<DownloadAction>",
    "fun primaryActionFor(download: Download): DownloadAction",
    "fun batchActionsFor(downloads: List<Download>): List<DownloadAction>",
    "DownloadState.Completed -> listOf(",
    "openFile(download, primary = true)",
    "reviewRecovery(primary = true)",
    "startNow(primary = true)",
]:
    require(planner, marker)

require(row, "DownloadActionPlanner.primaryActionFor(download)")
require(row, "onMoreActions: () -> Unit")
require(row, "More actions for")
require(row, "DownloadAction.iconVector()")
reject(row, "private data class DownloadRowAction", "old row-local action model")
reject(row, "private fun Download.primaryRowAction", "old row-local action planner")

for marker in [
    "var actionDownloadId by rememberSaveable",
    "DownloadActionsContent",
    "DownloadActionPlanner.actionsFor(download)",
    "performDownloadAction",
    "XdmListRow(",
    "Requires confirmation",
    "Destructive",
    "shareText(context",
]:
    require(screen, marker)

for marker in [
    "completedDownloadsPreferOpenFileAndKeepDestructiveDeleteSeparated",
    "queuedDownloadsExposeStartAndReorderActions",
    "recoveryDownloadsRoutePrimaryActionToReviewRecovery",
    "batchActionsAreDerivedFromSelectedStateMix",
]:
    require(core_test, marker)

require(contract, "downloadRowsRenderPlannerPrimaryAndMoreActions")
require(contract, "phase44PlannerIsPureModelWithStateMatrixAndBatchActions")
require(doc, "Phase 44")
require(doc, "State matrix")
require(manifest, '"browser_bridge_phase44_download_list_action_model_and_menus"')
require(manifest, '"next_overlay": "browser_bridge_phase45_completed_notification_open_file_intent"')
require(manifest, '"room_schema_unchanged": 14')

if errors:
    print("Phase 44 download-list action validation failed:")
    for error in errors:
        print(f"- {error}")
    sys.exit(1)

print("Phase 44 download-list action validation passed.")
