#!/usr/bin/env python3
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
errors: list[str] = []


def read(relative: str) -> str:
    path = ROOT / relative
    if not path.is_file():
        errors.append(f"Missing required file: {relative}")
        return ""
    return path.read_text(encoding="utf-8")


def require(text: str, marker: str, owner: str) -> None:
    if marker not in text:
        errors.append(f"{owner} missing marker: {marker}")


manifest = json.loads(read("PROJECT_MANIFEST.json") or "{}")
expected_overlay = "xdm_android_browser_removal_phase8ab_downloader_intake_dashboard_overlay.zip"
current_overlay = str(manifest.get("current_overlay", ""))
if current_overlay != expected_overlay and not current_overlay.startswith("xdm_android_browser_removal_phase8c_"):
    errors.append("current_overlay must identify Phase 8A + 8B or its approved Phase 8C successor")
phase = manifest.get("downloader_experience_phase8ab", {})
for key in (
    "review_first_manual_intake",
    "explicit_clipboard_detection",
    "semantic_link_classification",
    "explicit_media_inspection",
    "smart_dashboard_ordering",
    "actionable_failure_guidance",
    "all_download_engines_preserved",
    "browser_runtime_remains_absent",
):
    if phase.get(key) is not True:
        errors.append(f"downloader_experience_phase8ab.{key} must be true")
if phase.get("auto_probe") is not False or phase.get("auto_queue") is not False:
    errors.append("Phase 8A must remain explicit and review-first")
if phase.get("dashboard_sections") != ["Needs attention", "Active", "Queued", "Completed", "History"]:
    errors.append("Phase 8B dashboard sections or order changed")
if phase.get("stable_routes") != ["Downloads", "Add", "Media", "Library", "Activity", "Settings"]:
    errors.append("Stable downloader routes changed")
if phase.get("room_schema_unchanged") != 14:
    errors.append("Room schema must remain 14")
if phase.get("version_name_unchanged") != "0.20.0-rc08" or phase.get("version_code_unchanged") != 21:
    errors.append("App version must remain 0.20.0-rc08 / 21")

model = read("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DownloaderExperience.kt")
for marker in (
    "enum class DownloadReviewReadiness",
    "object DownloadReviewPlanner",
    "data class DownloadReviewPlan",
    "enum class DownloadDashboardBucket",
    "object DownloadDashboardPlanner",
    "DownloadAttentionKind.Authentication",
    "DownloadAttentionKind.Storage",
    "DownloadAttentionKind.Permission",
    "DownloadAttentionKind.Verification",
    "DownloadAttentionKind.Network",
    "DownloadAttentionKind.Recovery",
):
    require(model, marker, "Downloader experience model")
for forbidden in ("DownloadRepository", "executionStarter", "TransferExecutionRuntime", "android.content", "android.webkit"):
    if forbidden in model:
        errors.append(f"Pure downloader experience model depends on runtime token: {forbidden}")

intake = read("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DownloadIntake.kt")
require(intake, "ManualEntry", "Download intake origin")
require(intake, "fun fromManual(", "Download intake planner")

screens = read("app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt")
for marker in (
    "Review-first intake",
    "Paste detected URL",
    "DownloadReviewPlanner.plan(",
    "Inspect as media (recommended)",
    "Ready for explicit queue submission.",
    "DownloadDashboardPlanner.plan(visible, ordering)",
    "DownloadDashboardSectionHeader(section)",
    "DownloadDashboardOrdering.entries",
    "DownloadDashboardPlanner.attentionSignal(download)",
):
    require(screens, marker, "Compose UI")
require(screens, "firstDownloadUrlFromClipboard(context)", "Explicit clipboard intake")
if "clipboard.primaryClip" not in screens:
    errors.append("Clipboard intake must use the current primary clip only after explicit action")

shell = read("app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApp.kt")
view_model = read("app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt")
require(shell, "viewModel.inspectManualMedia(url, fileName)", "App shell")
require(view_model, "fun inspectManualMedia(url: String, fileName: String)", "MainViewModel")
require(view_model, "downloadIntakePlanner.fromManual", "MainViewModel")

routes = read("app/src/main/kotlin/com/mikeyphw/xdm/android/AppRoute.kt")
for route in ("Downloads", "Add", "Media", "Library", "Activity", "Settings"):
    require(routes, f'{route}("{route}"', "AppRoute")
for forbidden in ("Browser(", "AppRoute.Browser"):
    if forbidden in routes + shell:
        errors.append(f"Browser route returned: {forbidden}")

production_roots = (
    "app/src/main",
    "core-model/src/main",
    "media/src/main",
    "persistence/src/main",
    "scheduler/src/main",
    "transfer-native/src/main",
    "transfer-aria2/src/main",
)
production_text = []
for relative in production_roots:
    path = ROOT / relative
    if path.exists():
        production_text.extend(file.read_text(encoding="utf-8") for file in path.rglob("*") if file.is_file() and file.suffix in {".kt", ".xml", ".kts"})
joined = "\n".join(production_text)
for forbidden in ("android.webkit", "WebView(", "WebViewClient", "WebChromeClient"):
    if forbidden in joined:
        errors.append(f"Browser runtime token returned: {forbidden}")

for preserved in (
    "transfer-native/src/main/kotlin/com/mikeyphw/xdm/android/transfer/nativeengine/NativeHttpDownloadBackend.kt",
    "transfer-aria2/src/main/kotlin/com/mikeyphw/xdm/android/transfer/aria2/EmbeddedAria2Backend.kt",
    "scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferExecutionRuntime.kt",
    "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaTermuxRuntimeAdapter.kt",
    "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaExecutionLibrary.kt",
    "app/src/main/kotlin/com/mikeyphw/xdm/android/Media3PlayerScreen.kt",
):
    if not (ROOT / preserved).is_file():
        errors.append(f"Preserved downloader implementation missing: {preserved}")

build = read("app/build.gradle.kts")
if 'versionName = "0.20.0-rc08"' not in build or not re.search(r"versionCode\s*=\s*21\b", build):
    errors.append("Phase 8A + 8B must not change app version")
database = read("persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/AppDatabase.kt")
if not re.search(r"version\s*=\s*14\b", database):
    errors.append("Phase 8A + 8B must not change Room schema")

for path in (
    "core-model/src/test/kotlin/com/mikeyphw/xdm/android/model/DownloaderExperienceTest.kt",
    "app/src/test/kotlin/com/mikeyphw/xdm/android/DownloaderExperiencePhase8ABContractTest.kt",
    "docs/downloader/PHASE-8AB-DOWNLOADER-INTAKE-DASHBOARD.md",
):
    if not (ROOT / path).is_file():
        errors.append(f"Phase 8A + 8B contract path missing: {path}")

validator = "tools/validate-downloader-experience-phase-8ab.py"
final_gate = read("tools/run-final-release-gate.sh")
workflow = read(".github/workflows/android.yml")
require(final_gate, validator, "Final release gate")
require(workflow, validator, "Android CI")

if errors:
    print("Downloader experience Phase 8A + 8B validation failed:")
    for error in errors:
        print(f"- {error}")
    sys.exit(1)

print("Downloader experience Phase 8A + 8B validation passed: review-first intake and grouped Downloads dashboard are sealed")
