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


manifest_data = json.loads(read("PROJECT_MANIFEST.json") or "{}")
phase = manifest_data.get("browser_removal_phase5", {})
required_flags = (
    "browser_preferences_and_session_store_absent",
    "browser_only_resources_absent",
    "android_webkit_dependency_absent",
    "browser_runtime_phase_docs_retired",
    "browser_runtime_validators_deleted",
    "media_capture_quality_preserved_and_neutralized",
    "external_page_context_privacy_audit_preserved",
    "external_browser_integration_module_preserved",
    "external_download_actions_preserved",
    "native_aria2_termux_worker_queue_runtime_preserved",
    "media_resolver_tracks_offline_library_player_preserved",
)
for key in required_flags:
    if phase.get(key) is not True:
        errors.append(f"browser_removal_phase5.{key} must be true")
expected_overlay = "xdm_android_browser_removal_phase5_persistence_contract_cleanup_overlay.zip"
current_overlay = str(manifest_data.get("current_overlay", ""))
if current_overlay != expected_overlay and not current_overlay.startswith(("xdm_android_browser_removal_phase6_", "xdm_android_browser_removal_phase7_", "xdm_android_browser_removal_phase8")):
    errors.append("current_overlay must identify Phase 5 or an approved later successor")
if manifest_data.get("next_phase") != "complete":
    errors.append("top-level next_phase must remain complete for legacy release gates")
if phase.get("next_phase") not in {"downloader_ui_consolidation", "phase6_downloader_ui_consolidation_landed", "phase7_final_release_seal_landed", "phase8ab_downloader_experience_landed"}:
    errors.append("browser_removal_phase5.next_phase must identify downloader UI consolidation or its landed successor")
for key in ("built_in_browser_media_downloader",):
    if key in manifest_data:
        errors.append(f"Retired active manifest section remains: {key}")
for key in manifest_data:
    if re.match(r"phase(?:37a|37b|3[89]|4[0-9]|50)_", key):
        errors.append(f"Retired browser phase remains active in PROJECT_MANIFEST: {key}")
for phase_number in (40, 41, 42, 49, 50):
    if phase_number in manifest_data.get("implemented_phases", []):
        errors.append(f"Browser-only phase {phase_number} remains in implemented_phases")

retired_browser_dir = ROOT / "docs/browser"
if retired_browser_dir.exists() and any(path.is_file() for path in retired_browser_dir.rglob("*")):
    errors.append("Retired browser documentation files still exist under docs/browser")

for path in (
    "docs/architecture/PHASE-18-BUILT-IN-BROWSER-MEDIA-DOWNLOADER.md",
    "docs/architecture/PHASE-18-BROWSER-MEDIA-CONTINUITY.md",
    "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaBrowserCaptureQuality.kt",
):
    if (ROOT / path).exists():
        errors.append(f"Retired browser path still exists: {path}")

retired_validators = (
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
for name in retired_validators:
    if (ROOT / "tools" / name).exists():
        errors.append(f"Retired validator still exists: {name}")

archive = read("docs/archive/BUILT-IN-BROWSER-HISTORY.md")
require(archive, "historical context only", "Retired browser archive")
require(archive, "must not be used to reintroduce", "Retired browser archive")

quality = read("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaCaptureQuality.kt")
for marker in (
    "class MediaCaptureQualityPlanner",
    "data class MediaCaptureQualityDashboard",
    "CaptureQualityDisposition",
    "AnalyticsBeacon",
    "GroupWithExisting",
    "secret-safe capture quality",
):
    require(quality, marker, "Media capture quality")
for forbidden in ("MediaBrowserCaptureQualityPlanner", "BrowserCaptureQualityDashboard", "WebView"):
    if forbidden in quality:
        errors.append(f"Media capture quality retains browser-only marker: {forbidden}")

mobile = read("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaMobilePolish.kt")
for forbidden in ("BrowserFocused", "browserVisible"):
    if forbidden in mobile:
        errors.append(f"Mobile polish retains dormant browser state: {forbidden}")

privacy = read("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaSessionPrivacyAudit.kt")
require(privacy, 'ExternalPageContext("external page context")', "Privacy audit")
if "BrowserProfile" in privacy:
    errors.append("Privacy audit still exposes BrowserProfile")

production_roots = [ROOT / "app/src/main", ROOT / "media/src/main"]
production_text = "\n".join(
    path.read_text(encoding="utf-8")
    for base in production_roots
    for path in base.rglob("*")
    if path.is_file() and path.suffix in {".kt", ".xml"}
)
for marker in (
    "android.webkit",
    "WebView(",
    "WebViewClient",
    "WebChromeClient",
    "xdm_browser_sessions",
    "KeyBookmarks",
    "KeyBrowserHistory",
    "KeyBrowserTabs",
    "--cookies-from-browser=android-webview",
):
    if marker in production_text:
        errors.append(f"Browser runtime/persistence marker remains in production: {marker}")

resources = ROOT / "app/src/main/res"
for path in resources.rglob("*"):
    if path.is_file() and re.search(r"browser|webview|bookmark", path.name, re.IGNORECASE):
        errors.append(f"Browser-only resource remains: {path.relative_to(ROOT)}")

settings = read("settings.gradle.kts")
app_build = read("app/build.gradle.kts")
require(settings, '":browser-integration"', "Settings external integration module")
require(app_build, 'implementation(project(":browser-integration"))', "App external integration dependency")
if "androidx.webkit" in app_build.lower() or "webkit" in app_build.lower():
    errors.append("App build retains Android WebKit dependency")
shared_parser = read("browser-integration/src/main/kotlin/com/mikeyphw/xdm/android/browser/SharedLinkParser.kt")
require(shared_parser, "object BrowserHandoffContract", "External browser handoff contract")
require(shared_parser, "com.android.browser.action.DOWNLOAD", "External browser download action")
if "android.webkit" in shared_parser:
    errors.append("browser-integration must remain WebKit-free")

android_manifest = read("app/src/main/AndroidManifest.xml")
for marker in (
    ".ExternalAddDownloadActivity",
    "android.intent.action.SEND",
    "android.intent.action.SEND_MULTIPLE",
    "android.intent.action.VIEW",
    "com.android.browser.action.DOWNLOAD",
    'android:scheme="ftp"',
):
    require(android_manifest, marker, "External download manifest")
if ".BrowserActivity" in android_manifest:
    errors.append("BrowserActivity returned to AndroidManifest")

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

workflow = read(".github/workflows/android.yml")
final_gate = read("tools/run-final-release-gate.sh")
validator_path = "tools/validate-browser-removal-phase-5.py"
require(workflow, validator_path, "Android CI")
require(final_gate, validator_path, "Final release gate")
for retired in retired_validators:
    for context, text in (("Android CI", workflow), ("Final release gate", final_gate), ("PROJECT_MANIFEST", json.dumps(manifest_data))):
        if retired in text:
            errors.append(f"{context} still references retired validator: {retired}")

build = read("app/build.gradle.kts")
if 'versionName = "0.20.0-rc08"' not in build or not re.search(r"versionCode\s*=\s*21\b", build):
    errors.append("Phase 5 must not bump app version")
database = read("persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/AppDatabase.kt")
if not re.search(r"version\s*=\s*14\b", database):
    errors.append("Phase 5 must keep Room schema 14")

if errors:
    print("Browser removal Phase 5 validation failed:")
    for error in errors:
        print(f"- {error}")
    raise SystemExit(1)

print("Browser removal Phase 5 validation passed: browser persistence/contracts are gone and downloader integrations remain protected")
