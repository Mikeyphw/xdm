#!/usr/bin/env python3
"""XAR13 Downloads truthful actions/state contract."""
from __future__ import annotations

import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
CANONICAL_IDS = [
    "S13-01", "S13-02", "S13-04", "S13-05", "S13-07", "S13-08", "S13-09", "S13-10",
    "S13-11", "S13-12", "S13-13", "S13-14", "S13-15",
    "DS7-S13-01", "DS7-S13-02", "DS7-S13-03", "DS7-S13-04", "DS7-S13-05", "DS7-S13-06",
    "RERUN67-S13-01", "RERUN7R-S13-01",
]
files = {
    "truth": ROOT / "core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DownloadUiTruth.kt",
    "planner": ROOT / "core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DownloadActionPlanner.kt",
    "exec": ROOT / "core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DownloadActionExecutionModels.kt",
    "repo": ROOT / "persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/DownloadRepository.kt",
    "vm": ROOT / "app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt",
    "screen": ROOT / "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadsScreen.kt",
    "row": ROOT / "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadRow.kt",
    "organize": ROOT / "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/OrganizeDownloadsSheet.kt",
    "handoff": ROOT / "scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/MediaRequestHandoffStore.kt",
    "gradle": ROOT / "app/build.gradle.kts",
    "gate": ROOT / "tools/run-final-release-gate.sh",
    "manifest": ROOT / "PROJECT_MANIFEST.json",
    "doc": ROOT / "docs/remediation/XAR13-DOWNLOADS-TRUTHFUL-ACTIONS.md",
    "report": ROOT / "XDM_ANDROID_XAR13_DOWNLOADS_TRUTHFUL_ACTIONS_REPORT.md",
}
missing = [str(path.relative_to(ROOT)) for path in files.values() if not path.exists()]
if missing:
    raise SystemExit("Missing XAR13 files: " + ", ".join(missing))
text = {name: path.read_text() for name, path in files.items()}
checks: list[tuple[str, bool]] = []
def has(name: str, needle: str) -> bool:
    return needle in text[name]
def rx(name: str, pattern: str) -> bool:
    return re.search(pattern, text[name], re.S) is not None

checks += [
    ("attempt-bound verification", has("truth", "it.attemptGeneration == currentAttempt") and has("truth", "latestChecksum")),
    ("durable completion gate", has("truth", "completionDurablyCommitted") and has("truth", "Completion pending durable artifact metadata")),
    ("indeterminate active progress", has("truth", "indeterminateProgressVisible") and has("row", "XdmProgressLine(progress = phaseProgress")),
    ("backend owner label retained", has("truth", "backendLabel") and has("truth", "ownerLabel")),
    ("batch planner only implemented actions", has("planner", "DownloadActionKind.Archive") and has("planner", "DownloadActionKind.Unarchive") and "Copy redacted source links" not in text["planner"]),
    ("duplicate queueStates removed", text["planner"].count("private val queueStates") == 1),
    ("repository truthful archive CAS", has("repo", "setArchivedTruthfully") and has("repo", "sameObservedRevision") and has("repo", "RejectedActiveOwner")),
    ("repository exposes current row reload", has("repo", "findDownloadsByIds")),
    ("start now reloads current durable state", has("vm", "repository.findDownload(download.id) ?: return@launch") and has("vm", "policyOverrideFromCurrent(current)")),
    ("bulk actions reload current rows", has("vm", "repository.findDownloadsByIds(ids)") and has("vm", "bulkPause") and has("vm", "bulkResume")),
    ("native HLS delete/restart ownership", has("vm", "nativeHlsMediaManager.cancel(current.id) else transferRuntime.cancel(current.id)") and has("vm", "databaseNativeHlsOwnership(current.id)")),
    ("source replacement commits before handoff rebind", rx("vm", r"val saved = repository\.save\(current\.copy\(sourceUrl = persisted.*?if \(!saved\).*?MediaRequestHandoffStore\.replaceDownloadUrl")),
    ("fresh redownload handoff precedes queue", has("vm", "handoffPrepared") and has("vm", "MediaRequestHandoffStore.forget(newId)") and has("vm", "requestStart(newId")),
    ("same exact request preserves approvals", has("handoff", "val sameExactRequest") and has("handoff", "sameExactRequest && source.privateNetworkApproved")),
    ("organize tag unassign", has("organize", "onSetTagAssignment(tag, !allSelectedHaveTag)") and has("organize", "Remove ${tag.name}")),
    ("DownloadsScreen uses set tag assignment", has("screen", "onSetTagAssignment") and "onAssignTag(tag)" not in text["screen"]),
    ("XAR13 Gradle task registered", has("gradle", "verifyXar13DownloadsTruthfulActions")),
    ("final gate runs XAR13 validator", has("gate", "tools/validate-xar13-downloads-truthful-actions.py")),
    ("doc includes all S13 canonical ids", all(cid in text["doc"] for cid in CANONICAL_IDS)),
    ("report includes all S13 canonical ids", all(cid in text["report"] for cid in CANONICAL_IDS)),
]
manifest = json.loads(text["manifest"])
entry = manifest.get("xar13_downloads_truthful_actions")
if not entry:
    raise SystemExit("PROJECT_MANIFEST missing xar13_downloads_truthful_actions")
checks += [
    ("manifest marks XAR13 as overlay 13 of 17", entry.get("roadmap_position") == "13 of 17"),
    ("manifest closes exactly 21 S13 findings", entry.get("canonical_findings_closed") == 21 and entry.get("canonical_ids") == CANONICAL_IDS),
    ("manifest wires validator/task", entry.get("validator") == "tools/validate-xar13-downloads-truthful-actions.py" and entry.get("validation_task") == "verifyXar13DownloadsTruthfulActions"),
    ("roadmap current overlay updated", manifest.get("xar_roadmap", {}).get("current_overlay") == "XAR13"),
]
failed = [label for label, ok in checks if not ok]
if failed:
    print("XAR13 validation failed:", file=sys.stderr)
    for label in failed:
        print(f" - {label}", file=sys.stderr)
    raise SystemExit(1)
print(f"XAR13 Downloads truthful actions contract passed: {len(checks)} checks; {len(CANONICAL_IDS)}/21 S13 findings covered.")
