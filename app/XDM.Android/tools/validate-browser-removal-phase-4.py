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


manifest_json = json.loads(read("PROJECT_MANIFEST.json") or "{}")
phase = manifest_json.get("browser_removal_phase4", {})
for key in (
    "browser_screen_removed",
    "browser_activity_removed",
    "browser_route_removed",
    "browser_launcher_removed",
    "generic_http_https_browser_claim_removed",
    "android_webkit_runtime_removed",
    "browser_startup_state_removed",
    "external_download_receiver_preserved",
    "sharesheet_text_subject_clipdata_preserved",
    "review_first_external_handoff_preserved",
    "neutral_intake_and_media_review_preserved",
    "native_aria2_termux_worker_queue_runtime_preserved",
    "media_resolver_tracks_offline_library_player_preserved",
    "browser_era_runtime_validators_retired",
):
    if phase.get(key) is not True:
        errors.append(f"browser_removal_phase4.{key} must be true")
current_overlay = str(manifest_json.get("current_overlay", ""))
if current_overlay not in {"xdm_android_phase61_final_gate_validator_harmony_overlay.zip", "xdm_android_phase62_real_device_operational_smoke_seal_overlay.zip", "xdm_android_phase63_release_readiness_support_bundle_seal_r2_overlay.zip", "xdm_android_phase64_final_android_downloader_rc_seal_r2_overlay.zip"} and current_overlay != "xdm_android_browser_removal_phase4_runtime_excision_overlay.zip" and not current_overlay.startswith(("xdm_android_browser_removal_phase5_", "xdm_android_browser_removal_phase6_", "xdm_android_browser_removal_phase7_", "xdm_android_browser_removal_phase8")):
    errors.append("current_overlay must identify Phase 4 or an approved later browser-removal overlay")

for path in (
    "app/src/main/kotlin/com/mikeyphw/xdm/android/BrowserScreen.kt",
    "app/src/main/kotlin/com/mikeyphw/xdm/android/BrowserActivity.kt",
):
    if (ROOT / path).exists():
        errors.append(f"Removed browser runtime file still exists: {path}")

routes = read("app/src/main/kotlin/com/mikeyphw/xdm/android/AppRoute.kt")
shell = read("app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApp.kt")
view_model = read("app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt")
activity = read("app/src/main/kotlin/com/mikeyphw/xdm/android/MainActivity.kt")
strings = read("app/src/main/res/values/strings.xml")
for marker, text, context in (
    ('Browser("Browser"', routes, "AppRoute"),
    ("Icons.Rounded.Public", routes, "AppRoute"),
    ("AppRoute.Browser", shell, "App shell"),
    ("BrowserScreen(", shell, "App shell"),
    ("browserStartUrl", view_model, "MainViewModel"),
    ("openBrowserUrl(url: String)", view_model, "MainViewModel"),
    ("consumeBrowserStartUrl", view_model, "MainViewModel"),
    ("shouldOpenBrowserUrl", activity, "MainActivity"),
    ("openBrowserUrlFromIntent", activity, "MainActivity"),
    ("browser_activity_label", strings, "String resources"),
):
    if marker in text:
        errors.append(f"{context} still contains removed browser marker: {marker}")
require(shell, "private val primaryRoutes = routeTopology.filterNot { it == AppRoute.Add }", "Primary navigation")
require(activity, "handleExternalIntent(intent)", "MainActivity external intake")

manifest = read("app/src/main/AndroidManifest.xml")
if ".BrowserActivity" in manifest:
    errors.append("AndroidManifest still registers BrowserActivity")
if manifest.count('android.intent.category.LAUNCHER') != 1:
    errors.append("AndroidManifest must expose exactly one launcher after browser removal")
match = re.search(r'<activity\s+[^>]*android:name="\.ExternalAddDownloadActivity"[\s\S]*?</activity>', manifest)
if not match:
    errors.append("ExternalAddDownloadActivity manifest block is missing")
    external_block = ""
else:
    external_block = match.group(0)
    for marker in (
        "android.intent.action.SEND",
        "android.intent.action.SEND_MULTIPLE",
        "android.intent.action.VIEW",
        'android:scheme="http"',
        'android:scheme="https"',
        'android:scheme="ftp"',
    ):
        require(external_block, marker, "ExternalAddDownloadActivity")

app_source_root = ROOT / "app/src/main/kotlin"
app_source = "\n".join(path.read_text(encoding="utf-8") for path in app_source_root.rglob("*.kt"))
for forbidden in ("android.webkit", "WebView(", "WebViewClient", "WebChromeClient"):
    if forbidden in app_source:
        errors.append(f"Application runtime still contains Android WebKit marker: {forbidden}")

preferences = read("app/src/main/kotlin/com/mikeyphw/xdm/android/UserPreferencesStore.kt")
require(preferences, "AppRoute.restore(preferences[Keys.LastRoute])", "Persisted-route migration")

for path, marker in {
    "core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DownloadIntake.kt": "data class DownloadIntakeDraft",
    "media/src/main/kotlin/com/mikeyphw/xdm/android/media/ExternalMediaReviewIntake.kt": "class ExternalMediaReviewPlanner",
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
    "docs/browser-removal/PHASE-4-BROWSER-RUNTIME-EXCISION.md",
    "app/src/test/kotlin/com/mikeyphw/xdm/android/BrowserRemovalPhase4ContractTest.kt",
):
    read(path)

workflow = read(".github/workflows/android.yml")
final_gate = read("tools/run-final-release-gate.sh")
validator = "tools/validate-browser-removal-phase-4.py"
require(workflow, validator, "Android CI")
require(final_gate, validator, "Final release gate")
retired = (
    "validate-browser-media-downloader.py",
    "validate-browser-media-continuity.py",
    "validate-phase-37a-browser-downloader-roadmap.py",
    "validate-phase-37b-dual-launcher-navigation-split.py",
    "validate-phase-38-browser-reliability-foundation.py",
    "validate-phase-39-browser-chrome-navigation.py",
    "validate-phase-40-browser-tabs-session-ux.py",
    "validate-phase-41-browser-download-bridge.py",
    "validate-phase-42-browser-media-capture-cockpit.py",
    "validate-phase-43-browser-library-surfaces.py",
    "validate-phase-44-browser-settings-privacy-controls.py",
    "validate-phase-45-browser-visual-polish-adaptive-layout.py",
    "validate-phase-46-browser-private-mode-data-isolation.py",
    "validate-phase-47-browser-permission-ux-settings-polish.py",
    "validate-phase-48-browser-resource-inspector.py",
    "validate-phase-49-browser-download-rules-file-type-interception.py",
    "validate-phase-50-browser-downloader-ux-polish-seal.py",
)
for name in retired:
    if name in final_gate or name in workflow:
        errors.append(f"Retired browser-runtime validator remains active: {name}")

build = read("app/build.gradle.kts")
if 'versionName = "0.20.0-rc08"' not in build or not re.search(r"versionCode\s*=\s*21\b", build):
    errors.append("Phase 4 must not bump app version")
database = read("persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/AppDatabase.kt")
if not re.search(r"version\s*=\s*14\b", database):
    errors.append("Phase 4 must keep Room schema 14")

if errors:
    print("Browser removal Phase 4 validation failed:")
    for error in errors:
        print(f"- {error}")
    raise SystemExit(1)

print("Browser removal Phase 4 validation passed: built-in browser runtime is gone and downloader handoff remains protected")
