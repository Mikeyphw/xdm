#!/usr/bin/env python3
from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
errors: list[str] = []

def require(condition: bool, message: str) -> None:
    if not condition:
        errors.append(message)

def read(path: str) -> str:
    return (ROOT / path).read_text()

manifest = json.loads(read("PROJECT_MANIFEST.json"))
phase = manifest.get("phase42_browser_media_capture_cockpit", {})
browser = read("app/src/main/kotlin/com/mikeyphw/xdm/android/BrowserScreen.kt")
app_shell = read("app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApp.kt")
routes = read("app/src/main/kotlin/com/mikeyphw/xdm/android/AppRoute.kt")
contract = read("app/src/test/kotlin/com/mikeyphw/xdm/android/ArchitectureContractTest.kt")
run_gate = read("tools/run-final-release-gate.sh")
workflow = read(".github/workflows/android.yml")
doc = ROOT / "docs/browser/PHASE-42-BROWSER-MEDIA-CAPTURE-COCKPIT.md"

require(doc.is_file(), "Phase 42 browser media cockpit doc is missing")
require(manifest.get("current_overlay") in {"xdm_android_phase42_browser_media_capture_cockpit_overlay.zip", "xdm_android_phase43_browser_library_surfaces_overlay.zip", "xdm_android_phase44_browser_settings_privacy_controls_overlay.zip"}, "current_overlay must point at the Phase 42 browser media capture cockpit overlay")
for key in [
    "phase41_landed",
    "media_found_cockpit",
    "grouped_hls_dash_progressive_audio_video_direct_unknown",
    "variant_summary_cards",
    "source_title_host_display",
    "download_selected_explicit_action",
    "resolve_variants_action",
    "review_media_action",
    "direct_file_download_bridge_preserved",
    "protected_live_unknown_diagnostics",
    "no_silent_auto_queue",
    "no_new_route",
    "no_room_migration",
    "no_version_bump",
    "no_transfer_engine_changes",
    "no_media_execution_changes",
]:
    require(phase.get(key) is True, f"phase42_browser_media_capture_cockpit.{key} must be true")
require(phase.get("raw_header_cookie_token_persistence") is False, "Phase 42 must not persist raw browser credentials")

require("BrowserMediaCockpit" in browser and "Media found" in browser and "Media cockpit" in browser, "Browser must expose a visible media cockpit")
require("toBrowserMediaCockpitGroups" in browser and "BrowserMediaCockpitGroup" in browser, "Browser media cockpit must group captures")
for token in [
    "MediaSourceKind.HlsPlaylist",
    "MediaSourceKind.DashManifest",
    "MediaSourceKind.ProgressiveMedia",
    "MediaSourceKind.AudioStream",
    "MediaSourceKind.VideoStream",
    "MediaSourceKind.DirectFile",
    "MediaSourceKind.Unknown",
]:
    require(token in browser, f"Browser media cockpit must classify {token}")
require("BrowserMediaVariantCard" in browser and "variantSummary" in browser and "variantCount" in browser, "Browser must render variant summary cards")
require("Download selected" in browser and "onDownloadSelected" in browser and "onDownloadMediaCapture" in browser, "Browser media cockpit must offer explicit Download selected action")
require("Resolve variants" in browser and "onResolveSelected" in browser and "onResolveMediaCapture" in browser, "Browser media cockpit must offer explicit Resolve variants action")
require("Review media" in browser and "onOpenMediaInbox" in browser, "Browser media cockpit must keep Review media action")
require("Protected-media hint" in browser and "no bypass" in browser and "Possible live/expiring stream" in browser, "Browser media cockpit must show protected/live diagnostic copy")
require("BrowserDownloadBridgeCard" in browser and "Download detected" in browser and "Add download" in browser, "Phase 42 must preserve the Phase 41 direct download bridge")
require("setDownloadListener" in browser and "URLUtil.guessFileName" in browser, "Phase 42 must keep WebView download listener filename handling")

require("onDownloadMediaCapture = viewModel::downloadMediaCapture" in app_shell, "App shell must wire Browser cockpit download action to ViewModel")
require("onResolveMediaCapture = viewModel::resolveMediaCapture" in app_shell, "App shell must wire Browser cockpit resolve action to ViewModel")
require("Browser(\"Browser\", Icons.Rounded.Public)" in routes, "Browser route must remain first-class")
for forbidden in ["MediaCockpit(\"MediaCockpit\"", "PageResources(\"PageResources\"", "BrowserMedia(\"BrowserMedia\""]:
    require(forbidden not in routes, f"Phase 42 must not add top-level route {forbidden}")

require("validate-phase-42-browser-media-capture-cockpit.py" in run_gate, "Final release gate must run Phase 42 validator")
require("validate-phase-42-browser-media-capture-cockpit.py" in workflow, "Android CI must run Phase 42 validator")
require("phaseFortyTwoBrowserMediaCaptureCockpitContractsArePresent" in contract, "ArchitectureContractTest must cover Phase 42")
require("xdm_android_phase42_browser_media_capture_cockpit_overlay.zip" in contract, "ArchitectureContractTest must allow Phase 42 current_overlay")

if errors:
    for error in errors:
        print(f"ERROR: {error}")
    sys.exit(1)
print("Phase 42 browser media capture cockpit validation passed")
