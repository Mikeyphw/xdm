#!/usr/bin/env python3
from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
errors: list[str] = []

def require(condition: bool, message: str) -> None:
    if not condition:
        errors.append(message)

manifest = json.loads((ROOT / "PROJECT_MANIFEST.json").read_text())
phase = manifest.get("phase41_browser_download_bridge", {})
allowed_current = {"xdm_android_phase41_browser_download_bridge_overlay.zip", "xdm_android_phase42_browser_media_capture_cockpit_overlay.zip",
    "xdm_android_phase43_browser_library_surfaces_overlay.zip", "xdm_android_phase44_browser_settings_privacy_controls_overlay.zip", "xdm_android_phase45_browser_visual_polish_adaptive_layout_overlay.zip", "xdm_android_phase46_browser_private_mode_data_isolation_overlay.zip", "xdm_android_phase47_browser_permission_ux_settings_polish_overlay.zip", "xdm_android_phase43_browser_library_surfaces_overlay.zip", "xdm_android_phase48_browser_resource_inspector_overlay.zip", "xdm_android_phase49_50_download_rules_ux_polish_overlay.zip", "xdm_android_browser_removal_phase2_neutral_intake_extraction_overlay.zip"}
require(manifest.get("current_overlay") in allowed_current or str(manifest.get("current_overlay", "")).startswith("xdm_android_browser_removal_phase"), "current_overlay must point at the Phase 41 browser download bridge overlay or a browser-removal successor")
for key in [
    "phase40_landed",
    "webview_download_listener_bridge",
    "download_detected_card",
    "content_disposition_filename_guess",
    "mime_type_and_size_display",
    "source_page_display",
    "add_download_primary_action",
    "inspect_media_secondary_action",
    "no_silent_auto_queue",
    "no_room_migration",
    "no_version_bump",
    "no_transfer_engine_changes",
    "no_media_execution_changes",
]:
    require(phase.get(key) is True, f"phase41_browser_download_bridge.{key} must be true")

browser = (ROOT / "app/src/main/kotlin/com/mikeyphw/xdm/android/BrowserScreen.kt").read_text()
view_model = (ROOT / "app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt").read_text()
app_shell = (ROOT / "app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApp.kt").read_text()
run_gate = (ROOT / "tools/run-final-release-gate.sh").read_text()
workflow = (ROOT / ".github/workflows/android.yml").read_text()
architecture = (ROOT / "app/src/test/kotlin/com/mikeyphw/xdm/android/ArchitectureContractTest.kt").read_text()

doc = ROOT / "docs/browser/PHASE-41-BROWSER-DOWNLOAD-BRIDGE.md"
validator = ROOT / "tools/validate-phase-41-browser-download-bridge.py"
require(doc.is_file(), "Phase 41 browser download bridge doc must exist")
require(validator.is_file(), "Phase 41 validator must exist")

require("setDownloadListener" in browser and "URLUtil.guessFileName" in browser, "Browser must use WebView download listener with URLUtil filename guessing")
require("BrowserDownloadBridgeDraft" in browser and "BrowserDownloadBridgeCard" in browser, "Browser must model and show a review-first download bridge card")
require("Download detected" in browser and "Add download" in browser and "Inspect media" in browser and "Dismiss" in browser, "Browser download card must expose detected/download/inspect/dismiss UI")
require("contentLength" in browser and "formatBytes()" in browser and "mimeType" in browser and "sourcePageUrl" in browser, "Browser download card must expose size, MIME, and source page details")
require("onDownloadRequested" in browser and "onOpenDownloadReview" in browser, "Browser must route WebView download events through the neutral Add Download intake seam")
require("openDownloadReview" in view_model and "DownloadIntakeDraft" in view_model, "ViewModel must expose a browser-neutral review draft entrypoint")
require("DownloadIntakePlanner" in browser and "fromBuiltInBrowserDownload" in browser, "Browser download bridge must preserve filename suggestions through DownloadIntakePlanner")
require("onOpenDownloadReview = viewModel::openDownloadReview" in app_shell, "App shell must wire neutral review drafts to the ViewModel")

download_block = browser.split("setDownloadListener", 1)[1].split("}", 1)[0]
require("onMediaDiscovered" not in download_block, "WebView download listener must not treat every direct file as a media capture")
require("addDownload(" not in browser, "Browser UI must not directly enqueue downloads")
require("executionStarter.start" not in view_model.split("fun openDownloadReview", 1)[1].split("fun ", 1)[0], "openDownloadReview must not start transfers")
require("authorization headers are not shown" in browser and "not persisted as raw browser handoff data" in browser, "Browser download bridge must keep raw header/cookie/token posture visible")

require("validate-phase-41-browser-download-bridge.py" in run_gate, "final release gate must include Phase 41 validator")
require("validate-phase-41-browser-download-bridge.py" in workflow, "Android CI must include Phase 41 validator")
require("phaseFortyOneBrowserDownloadBridgeContractsArePresent" in architecture, "ArchitectureContractTest must include Phase 41 contracts")

if errors:
    print("Phase 41 browser download bridge validation failed:")
    for error in errors:
        print(f"- {error}")
    raise SystemExit(1)
print("Phase 41 browser download bridge validation passed")
