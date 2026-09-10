#!/usr/bin/env python3
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ERRORS: list[str] = []

def read(relative: str) -> str:
    path = ROOT / relative
    if not path.is_file():
        ERRORS.append(f"Missing required file: {relative}")
        return ""
    return path.read_text(encoding="utf-8")

def require(relative: str, *needles: str) -> str:
    text = read(relative)
    for needle in needles:
        if needle not in text:
            ERRORS.append(f"{relative} missing {needle!r}")
    return text

planner = require(
    "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadsWorkspace.kt",
    "enum class DownloadWorkspaceFilter",
    'All("All")', 'Downloading("Downloading")', 'Waiting("Waiting")', 'Finished("Finished")',
    "fun visibleDownloads(", "fun metrics(", "fun queueIssue(", "fun rowStatus(",
)
screen = require(
    "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadsScreen.kt",
    "XdmMetricStrip(", "DownloadWorkspaceFilter.entries", "Search downloads", "Organize downloads",
    "XdmWindowClass.Expanded", "XdmAdaptiveSheet(", "queueIssue",
)
row = require(
    "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadRow.kt",
    "combinedClickable(", "onLongClick", "XdmMediaArtwork(", "XdmProgressLine(",
    "DownloadActionPlanner.primaryActionFor(download, actionContext)", "DownloadAction.iconVector()",
)

# THUMB01: the row's historical XdmFileTypeIcon fallback is now owned by the shared
# XdmMediaArtwork component, which itself provides MIME-backed fallback presentation.
# Phase61: UIX R3 originally required the old row-local primaryRowAction symbol.
# Phase44 intentionally retired that local planner; the current contract is planner-backed.
if "private fun Download.primaryRowAction" in row or "private data class DownloadRowAction" in row:
    ERRORS.append("Download rows must keep using DownloadActionPlanner instead of reviving row-local action planning")

if 'label = { Text("Select") }' in row + screen:
    ERRORS.append("Downloads must use long-press selection instead of permanent Select chips")
if "tonalElevation = 0.dp" not in row or "shadowElevation = 0.dp" not in row:
    ERRORS.append("Download rows must remain flat and zero elevation")
for unsupported in ("DownloadState.Verifying,\n    DownloadState.Repairing", "DownloadState.RecoveryRequired,\n)"):
    if unsupported in row.split("internal fun Download.primaryActionUsesToggle", 1)[-1].split(")", 1)[0]:
        ERRORS.append("Primary row actions must not call togglePause for unsupported recovery/verification states")

details = require(
    "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadDetails.kt",
    "XdmTechnicalDetails", "Saved location", "Source site", "Verification", "Request session", "redacted",
)
for forbidden in ("Text(download.id)", "Text(download.sourceUrl)", "Text(download.requestHeaders"):
    if forbidden in details:
        ERRORS.append(f"Download details expose raw technical content: {forbidden}")

organize = require(
    "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/OrganizeDownloadsSheet.kt",
    "Show archived downloads", "Sort order", "Selection", "Tags", "Saved searches", "History and activity",
)
add = require(
    "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/intake/AddDownloadSurface.kt",
    "DownloadReviewPlanner.plan(", "Advanced options", "destinationUiLabel(destinationUri)",
    'else -> "Download"', 'Text("Paste")', "preferMediaInspection", "Media options", "onCancel",
)
for stale in ("reviewConfirmed", "Review download", "Add to queue", "Step 1 of 2", "Step 2 of 2"):
    if stale in add:
        ERRORS.append(f"Add Download must not retain obsolete two-step confirmation token: {stale}")
if "if (preferMediaInspection)" not in add or "onInspectMedia(url, effectiveFileName)" not in add or "onAdd(" not in add:
    ERRORS.append("Add Download must keep explicit media inspection separate from the one-tap direct Download action")
if add.find("BrowserSessionHealthCard(health)") < add.find("AnimatedVisibility(advancedExpanded)"):
    ERRORS.append("Browser session diagnostics must remain behind Advanced options")
for forbidden in ("Text(requestHeaders", "Text(cookies", "backend probe output", "worker bridge"):
    if forbidden.lower() in add.lower():
        ERRORS.append(f"Normal Add flow exposes engineering detail: {forbidden}")

require("app/src/test/kotlin/com/mikeyphw/xdm/android/UixR3DownloadsWorkspaceTest.kt", "filtersKeepEveryTransferInTheExpectedWorkspace", "metricsExposeDownloadingWaitingQueuedAndMovingSpeedSeparately")
require("app/src/test/kotlin/com/mikeyphw/xdm/android/UixR3DownloadsAddContractTest.kt", "downloadsIsAnAdaptiveTransferFirstWorkspace", "addIsSingleActionAndNeverAutoQueuesMediaInspection")

manifest = json.loads(read("PROJECT_MANIFEST.json") or "{}")
r3 = manifest.get("uix_r3_downloads_add_workspace", {})
expected = {
    "adaptive_list_detail": True, "visible_filters": ["Active", "Queued", "Finished", "All"],
    "long_press_selection": True, "organize_workspace": True,
    "media_inspection_auto_queue": False, "room_schema_unchanged": 14,
    "version_name_unchanged": "0.20.0-rc08", "version_code_unchanged": 21,
}
for key, value in expected.items():
    if key == "visible_filters":
        actual = r3.get(key, [])
        if not all(item in actual for item in value):
            ERRORS.append(f"PROJECT_MANIFEST uix_r3_downloads_add_workspace.{key} must retain {value!r}")
    elif r3.get(key) != value:
        ERRORS.append(f"PROJECT_MANIFEST uix_r3_downloads_add_workspace.{key} must equal {value!r}")

ux = manifest.get("add_media_ux_remodel", {})
if r3.get("two_step_add_review") is not True:
    ERRORS.append("Historical UIX R3 manifest must retain that R3 originally shipped two-step review")
if ux and ux.get("supersedes_two_step_add_review") is not True:
    ERRORS.append("Current Add/Media UX remodel must explicitly supersede the historical two-step Add review")

require("docs/architecture/UIX-R3-DOWNLOADS-ADD-WORKSPACE.md", "transfer-first", "adaptive list-detail", "two-step review", "No Room schema bump")
validator = "tools/validate-uix-r3-downloads-add-workspace.py"
for gate in ("tools/run-final-release-gate.sh", ".github/workflows/android.yml"):
    if validator not in read(gate): ERRORS.append(f"{gate} does not run {validator}")

for relative in (
    "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadsScreen.kt",
    "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadRow.kt",
    "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/intake/AddDownloadSurface.kt",
):
    text = read(relative)
    if "onClick = {}" in text: ERRORS.append(f"{relative} contains an inert action")

if ERRORS:
    print("UIX R3 Downloads and Add workspace validation failed:")
    for error in ERRORS: print(f"- {error}")
    sys.exit(1)
print("UIX R3 Downloads and Add workspace validation passed")
