#!/usr/bin/env python3
from __future__ import annotations

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
errors: list[str] = []


def read(path: str) -> str:
    file = ROOT / path
    if not file.is_file():
        errors.append(f"Missing required file: {path}")
        return ""
    return file.read_text(encoding="utf-8")


def require(text: str, marker: str, context: str) -> None:
    if marker not in text:
        errors.append(f"{context} missing marker: {marker}")


manifest = json.loads(read("PROJECT_MANIFEST.json") or "{}")
phase = manifest.get("browser_removal_phase6", {})
for key in (
    "downloader_focused_route_set",
    "global_add_action",
    "library_promoted",
    "activity_workspace_consolidated",
    "legacy_route_migration",
    "external_handoff_preserved",
    "all_download_engines_preserved",
    "media_resolver_library_player_preserved",
    "browser_runtime_remains_absent",
):
    if phase.get(key) is not True:
        errors.append(f"browser_removal_phase6.{key} must be true")
expected_overlay = "xdm_android_browser_removal_phase6_downloader_ui_consolidation_overlay.zip"
current_overlay = str(manifest.get("current_overlay", ""))
if current_overlay not in {"xdm_android_phase61_final_gate_validator_harmony_overlay.zip", "xdm_android_phase62_real_device_operational_smoke_seal_overlay.zip", "xdm_android_phase63_release_readiness_support_bundle_seal_r2_overlay.zip", "xdm_android_phase64_final_android_downloader_rc_seal_r2_overlay.zip"} and current_overlay != expected_overlay and not current_overlay.startswith(("xdm_android_browser_removal_phase7_", "xdm_android_browser_removal_phase8")):
    errors.append("current_overlay must identify Phase 6 or its approved later successor")

routes = read("app/src/main/kotlin/com/mikeyphw/xdm/android/AppRoute.kt")
expected_routes = ("Downloads", "Add", "Media", "Library", "Activity", "Settings")
positions = []
for route in expected_routes:
    marker = f'{route}("{route}"'
    require(routes, marker, "AppRoute")
    positions.append(routes.find(marker))
if positions != sorted(positions):
    errors.append("AppRoute order must be Downloads, Add, Media, Library, Activity, Settings")
for retired in ("Queues(\"Queues\"", "Scheduler(\"Scheduler\"", "Recovery(\"Recovery\"", "Diagnostics(\"Diagnostics\""):
    if retired in routes:
        errors.append(f"Retired top-level route remains: {retired}")
for legacy in ('"Queues", "Scheduler", "Recovery", "Diagnostics" -> Activity', 'entries.firstOrNull { it.name == storedName } ?: Downloads'):
    require(routes, legacy, "Persisted route migration")

preferences = read("app/src/main/kotlin/com/mikeyphw/xdm/android/UserPreferencesStore.kt")
require(preferences, "AppRoute.restore(preferences[Keys.LastRoute])", "User preferences route restore")

shell = read("app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApp.kt")
require(shell, "private val primaryRoutes = routeTopology.filterNot { it == AppRoute.Add }", "Adaptive primary navigation")
require(shell, "onAddDownload = { viewModel.navigate(AppRoute.Add) }", "Global Add action")
require(shell, "AppRoute.Library -> MediaLibraryScreen", "Library route")
require(shell, "AppRoute.Activity -> ActivityHub", "Activity route")
for marker in ("ActivityPanel.Queues", "ActivityPanel.Schedule", "ActivityPanel.Recovery", "ActivityPanel.Diagnostics"):
    require(shell, marker, "Activity workspace")
for marker in ("AppRoute.Queues ->", "AppRoute.Scheduler ->", "AppRoute.Recovery ->", "AppRoute.Diagnostics ->"):
    if marker in shell:
        errors.append(f"Retired top-level destination remains in shell: {marker}")

screens = read("app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt")
for marker in (
    "fun MediaLibraryScreen(",
    "fun ActivityOverviewScreen(",
    "Completed media, playback readiness, sidecar health",
    "A single operational workspace for queue control",
):
    require(screens, marker, "Consolidated screens")
if screens.count("OfflineLibraryV2Card(libraryV2)") != 0:
    errors.append("Media inbox still renders the promoted Library 2.0 card")
if screens.count("OfflineLibraryV2Card(library)") != 1:
    errors.append("Library destination must render exactly one Library 2.0 card")

android_manifest = read("app/src/main/AndroidManifest.xml")
for marker in (".ExternalAddDownloadActivity", "android.intent.action.SEND", "android.intent.action.VIEW", "com.android.browser.action.DOWNLOAD"):
    require(android_manifest, marker, "External handoff manifest")
if ".BrowserActivity" in android_manifest:
    errors.append("BrowserActivity returned to AndroidManifest")

production = "\n".join(
    path.read_text(encoding="utf-8")
    for base in (ROOT / "app/src/main", ROOT / "media/src/main")
    for path in base.rglob("*")
    if path.is_file() and path.suffix in {".kt", ".xml"}
)
for forbidden in ("android.webkit", "WebView(", "WebViewClient", "WebChromeClient"):
    if forbidden in production:
        errors.append(f"Browser runtime marker returned: {forbidden}")

for path, marker in {
    "transfer-native/src/main/kotlin/com/mikeyphw/xdm/android/transfer/nativeengine/NativeHttpDownloadBackend.kt": "class NativeHttpDownloadBackend",
    "transfer-aria2/src/main/kotlin/com/mikeyphw/xdm/android/transfer/aria2/EmbeddedAria2Backend.kt": "class EmbeddedAria2Backend",
    "scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferExecutionRuntime.kt": "class TransferExecutionRuntime",
    "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaDownloadPlanner.kt": "class MediaDownloadPlanner",
    "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaExecutionLibrary.kt": "class MediaExecutionLibraryPlanner",
    "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaTermuxRuntimeAdapter.kt": "class MediaTermuxRuntimeAdapter",
    "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaWorkerBridge.kt": "class MediaWorkerBridgePlanner",
    "app/src/main/kotlin/com/mikeyphw/xdm/android/Media3PlayerScreen.kt": "fun Media3DirectPlayerCard",
}.items():
    require(read(path), marker, path)

for path in (
    "docs/browser-removal/PHASE-6-DOWNLOADER-UI-CONSOLIDATION.md",
    "app/src/test/kotlin/com/mikeyphw/xdm/android/BrowserRemovalPhase6ContractTest.kt",
):
    read(path)
workflow = read(".github/workflows/android.yml")
final_gate = read("tools/run-final-release-gate.sh")
validator = "tools/validate-browser-removal-phase-6.py"
require(workflow, validator, "Android CI")
require(final_gate, validator, "Final release gate")

build = read("app/build.gradle.kts")
if 'versionName = "0.20.0-rc08"' not in build or not re.search(r"versionCode\s*=\s*21\b", build):
    errors.append("Phase 6 must not bump app version")
database = read("persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/AppDatabase.kt")
if not re.search(r"version\s*=\s*14\b", database):
    errors.append("Phase 6 must keep Room schema 14")

if errors:
    print("Browser removal Phase 6 validation failed:")
    for error in errors:
        print(f"- {error}")
    raise SystemExit(1)

print("Browser removal Phase 6 validation passed: downloader-focused routes are consolidated and execution remains preserved")
