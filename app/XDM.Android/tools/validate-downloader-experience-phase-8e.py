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
expected_overlays = {
    "xdm_android_browser_removal_phase8e_activity_diagnostics_overlay.zip",
    "xdm_android_browser_removal_phase8e_compose_storage_compile_hotfix_overlay.zip",
    "xdm_android_browser_removal_phase8e_compile_test_repair_overlay.zip",
    "xdm_android_browser_removal_phase8e_gradle_contract_repair_overlay.zip",
}
if manifest.get("current_overlay") not in expected_overlays:
    errors.append("current_overlay must identify Phase 8E activity diagnostics or its compile hotfix")
phase = manifest.get("downloader_experience_phase8e", {})
for key in (
    "unified_operational_timeline",
    "search_category_severity_time_filters",
    "unresolved_attention_workspace",
    "explainable_queue_decisions",
    "transfer_state_transition_ledger",
    "bounded_retention_outside_room",
    "privacy_safe_diagnostics_export",
    "clear_history_preserves_transfers",
    "downloads_health_deep_links",
    "queue_schedule_recovery_diagnostics_preserved",
    "phase8c_queue_policy_preserved",
    "phase8d_media_resolver_preserved",
    "external_handoff_preserved",
    "all_download_engines_preserved",
    "browser_runtime_remains_absent",
):
    if phase.get(key) is not True:
        errors.append(f"downloader_experience_phase8e.{key} must be true")
if phase.get("stable_routes") != ["Downloads", "Add", "Media", "Library", "Activity", "Settings"]:
    errors.append("Stable downloader routes changed")
if phase.get("room_schema_unchanged") != 14:
    errors.append("Room schema must remain 14")
if phase.get("version_name_unchanged") != "0.20.0-rc08" or phase.get("version_code_unchanged") != 21:
    errors.append("App version must remain 0.20.0-rc08 / 21")

model = read("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/OperationalActivity.kt")
for marker in (
    "enum class OperationalActivityCategory",
    "enum class OperationalActivitySeverity",
    "enum class OperationalActivityTimeRange",
    "data class OperationalActivityEvent",
    "data class OperationalActivityFilter",
    "data class OperationalActivitySummary",
    "data class OperationalDiagnosticsContext",
    "object OperationalActivityPlanner",
    "fun timeline(",
    "fun filter(",
    "fun diagnosticsExport(",
    "PrivacyDiagnosticsRedactor.redactUrl",
    "built-in browser absent",
):
    require(model, marker, "Operational activity model")
for forbidden in ("android.content", "android.webkit", "WebView", "DownloadRepository", "RoomDatabase"):
    if forbidden in model:
        errors.append(f"Pure activity model depends on forbidden runtime token: {forbidden}")

store = read("app/src/main/kotlin/com/mikeyphw/xdm/android/OperationalActivityStore.kt")
for marker in (
    "class OperationalActivityStore",
    "xdm_operational_activity",
    "MAX_EVENTS = 300",
    "RETENTION_MS = 30L",
    "fun observeDownloads(",
    "fun dismiss(",
    "fun clearHistory(",
    "Clearing this ledger never removes a transfer",
):
    require(store, marker, "Operational activity store")
for forbidden in ("DownloadRepository", "RoomDatabase", "sourceUrl", "Cookie", "Authorization"):
    if forbidden in store:
        errors.append(f"Operational activity store must not own/persist sensitive field: {forbidden}")

ledger = read("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/QueueDecisionLedger.kt")
for marker in ("DEFAULT_VISIBLE_EVENTS = 120", "MAX_EVENTS = 240", "RETENTION_MS = 30L", "fun clear()"):
    require(ledger, marker, "Queue decision ledger")

coordinator = read("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/QueueIntelligenceCoordinator.kt")
require(coordinator, "fun clearDecisionHistory()", "Queue coordinator")
require(coordinator, "Queue decision history cleared; transfer records were not removed.", "Queue coordinator")

panels = read("app/src/main/kotlin/com/mikeyphw/xdm/android/ActivityPanel.kt")
for marker in (
    'Overview("Needs attention")',
    'Timeline("Recent")',
    'Attention("Needs attention")',
    'Decisions("Queue decisions")',
    'Queues("Queues")',
    'Schedule("Schedules")',
    'Recovery("Recovery")',
    'Diagnostics("Developer tools")',
    "val primaryPanels = listOf(Attention, Timeline)",
    "val managePanels = listOf(Decisions, Queues, Schedule, Recovery)",
):
    require(panels, marker, "Activity panels")

screens = read("app/src/main/kotlin/com/mikeyphw/xdm/android/OperationalActivityScreens.kt")
for marker in (
    "fun ActivityTimelineScreen",
    "fun ActivityAttentionScreen",
    "fun ActivityDecisionsScreen",
    "fun OperationalActivityOverviewCard",
    "fun OperationalDiagnosticsHeader",
    "Search filename, engine, category, or status",
    "Privacy-safe operational export",
    "Clear resolved history",
):
    require(screens, marker, "Activity UI")

shell = read("app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApp.kt")
for owner, source in (("Activity UI", screens), ("App shell", shell)):
    if "import androidx.compose.foundation.layout.weight" in source:
        errors.append(f"{owner} imports Compose's internal weight symbol")
monitor = read("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/QueueConditionMonitor.kt")
for deprecated_field in ("Intent.ACTION_DEVICE_STORAGE_LOW", "Intent.ACTION_DEVICE_STORAGE_OK"):
    if deprecated_field in monitor:
        errors.append(f"Queue condition monitor uses deprecated field: {deprecated_field}")
for marker in (
    "ActivityWorkspaceScreen(",
    "ActivityPanel.Attention",
    "ActivityPanel.Decisions",
    "ActivityPanel.Queues",
    "ActivityPanel.Schedule",
    "ActivityPanel.Recovery",
    "ActivityPanel.Diagnostics",
    "viewModel.openDeveloperTools()",
    "XdmAdaptiveSheet(",
):
    require(shell, marker, "App shell")
developer_workspace = read("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/developer/DeveloperToolsWorkspace.kt")
require(developer_workspace, "OperationalDiagnosticsHeader(", "Developer workspace")

view_model = read("app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt")
for marker in (
    "val activityEvents: List<OperationalActivityEvent>",
    "val activitySummary: OperationalActivitySummary",
    "val activityDiagnosticsExport: String",
    "OperationalActivityPlanner.timeline",
    "OperationalActivityPlanner.diagnosticsExport",
    "fun navigateActivity(",
    "fun dismissActivityEvent(",
    "fun clearActivityHistory(",
):
    require(view_model, marker, "MainViewModel activity wiring")

application = read("app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApplication.kt")
require(application, "val operationalActivityStore = OperationalActivityStore(this)", "Application activity wiring")
require(application, "operationalActivityStore = operationalActivityStore", "Application activity wiring")

downloads_screen = read("app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt")
for marker in ("Operational health", "Open attention", "Queue decisions", "OperationalActivityOverviewCard"):
    require(downloads_screen, marker, "Downloads and Activity overview")

routes = read("app/src/main/kotlin/com/mikeyphw/xdm/android/AppRoute.kt")
for route in ("Downloads", "Add", "Media", "Library", "Activity", "Settings"):
    require(routes, f'{route}("{route}"', "AppRoute")
for forbidden in ("Browser(", "AppRoute.Browser", "android.webkit", "WebView(", "WebViewClient", "WebChromeClient"):
    if forbidden in routes + shell + screens + downloads_screen + model:
        errors.append(f"Browser runtime token returned: {forbidden}")

for preserved in (
    "core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/QueueIntelligence.kt",
    "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaResolverWorkspace.kt",
    "transfer-native/src/main/kotlin/com/mikeyphw/xdm/android/transfer/nativeengine/NativeHttpDownloadBackend.kt",
    "transfer-aria2/src/main/kotlin/com/mikeyphw/xdm/android/transfer/aria2/EmbeddedAria2Backend.kt",
    "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaTermuxRuntimeAdapter.kt",
    "app/src/main/kotlin/com/mikeyphw/xdm/android/Media3PlayerScreen.kt",
    "browser-integration/src/main/kotlin/com/mikeyphw/xdm/android/browser/SharedLinkParser.kt",
):
    if not (ROOT / preserved).is_file():
        errors.append(f"Preserved downloader implementation missing: {preserved}")

build = read("app/build.gradle.kts")
if 'versionName = "0.20.0-rc08"' not in build or not re.search(r"versionCode\s*=\s*21\b", build):
    errors.append("Phase 8E must not change app version")
database = read("persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/AppDatabase.kt")
if not re.search(r"version\s*=\s*14\b", database):
    errors.append("Phase 8E must not change Room schema")

for path in (
    "core-model/src/test/kotlin/com/mikeyphw/xdm/android/model/OperationalActivityTest.kt",
    "app/src/test/kotlin/com/mikeyphw/xdm/android/DownloaderExperiencePhase8EContractTest.kt",
    "docs/downloader/PHASE-8E-ACTIVITY-DIAGNOSTICS.md",
):
    if not (ROOT / path).is_file():
        errors.append(f"Phase 8E contract path missing: {path}")

validator = "tools/validate-downloader-experience-phase-8e.py"
final_gate = read("tools/run-final-release-gate.sh")
workflow = read(".github/workflows/android.yml")
require(final_gate, validator, "Final release gate")
require(workflow, validator, "Android CI")

if errors:
    print("Downloader experience Phase 8E validation failed:")
    for error in errors:
        print(f"- {error}")
    sys.exit(1)

print("Downloader experience Phase 8E validation passed: Activity timeline, attention, decisions, retention, export redaction, and downloader preservation are sealed")
