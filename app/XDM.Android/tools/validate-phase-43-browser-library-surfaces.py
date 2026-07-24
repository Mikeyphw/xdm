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
phase = manifest.get("phase43_browser_library_surfaces", {})
browser = read("app/src/main/kotlin/com/mikeyphw/xdm/android/BrowserScreen.kt")
routes = read("app/src/main/kotlin/com/mikeyphw/xdm/android/AppRoute.kt")
contract = read("app/src/test/kotlin/com/mikeyphw/xdm/android/ArchitectureContractTest.kt")
run_gate = read("tools/run-final-release-gate.sh")
workflow = read(".github/workflows/android.yml")
doc = ROOT / "docs/browser/PHASE-43-BROWSER-LIBRARY-SURFACES.md"

require(doc.is_file(), "Phase 43 browser library doc is missing")
require(manifest.get("current_overlay") in {"xdm_android_phase43_browser_library_surfaces_overlay.zip", "xdm_android_phase44_browser_settings_privacy_controls_overlay.zip", "xdm_android_phase45_browser_visual_polish_adaptive_layout_overlay.zip", "xdm_android_phase46_browser_private_mode_data_isolation_overlay.zip", "xdm_android_phase47_browser_permission_ux_settings_polish_overlay.zip", "xdm_android_phase48_browser_resource_inspector_overlay.zip", "xdm_android_phase49_50_download_rules_ux_polish_overlay.zip"}, "current_overlay must point at the Phase 43 browser library overlay or approved Phase 44 settings/privacy overlay")
for key in [
    "phase42_landed",
    "bookmarks_surface",
    "browser_history_surface",
    "page_resources_surface",
    "import_links_from_text",
    "clipboard_paste_import",
    "browser_history_separate_from_downloader_history",
    "shared_preferences_only",
    "private_tabs_full_isolation_deferred",
    "no_new_route",
    "no_room_migration",
    "no_version_bump",
    "no_transfer_engine_changes",
    "no_media_execution_changes",
]:
    require(phase.get(key) is True, f"phase43_browser_library_surfaces.{key} must be true")

require("BrowserLibraryPanel" in browser and "Browser library" in browser, "Browser must expose a BrowserLibraryPanel")
require("BrowserBookmarkEntry" in browser and "loadBookmarks" in browser and "saveBookmarks" in browser and "toggleBookmark" in browser, "Browser must persist bookmarks in the session store")
require("KeyBookmarks" in browser and "xdm_browser_sessions" in browser, "Bookmarks must stay inside the browser session SharedPreferences store")
require("BrowserPageResourceEntry" in browser and "toBrowserPageResources" in browser, "Browser must expose page resource entries")
require("BrowserImportedLink" in browser and "extractBrowserImportLinks" in browser and "BrowserImportUrlRegex" in browser, "Browser must support import-link extraction")
require("ClipboardManager" in browser and "Paste links" in browser and "pasteClipboardIntoImport" in browser, "Browser must support clipboard paste import")
require("Bookmarks" in browser and "Page resources" in browser and "Import links" in browser and "Recent history" in browser, "Browser library sections must be visible")
require("review-first" in doc.read_text().lower(), "Phase 43 doc must preserve review-first posture")
require("Downloader history and browser history stay separate" in doc.read_text(), "Phase 43 doc must keep browser/downloader history separation")

require("Browser(\"Browser\", Icons.Rounded.Public)" in routes, "Browser route must remain first-class")
for forbidden in ["Bookmarks(\"Bookmarks\"", "History(\"History\"", "PageResources(\"PageResources\""]:
    require(forbidden not in routes, f"Phase 43 must not add top-level route {forbidden}")

require("validate-phase-43-browser-library-surfaces.py" in run_gate, "Final release gate must run Phase 43 validator")
require("validate-phase-43-browser-library-surfaces.py" in workflow, "Android CI must run Phase 43 validator")
require("phaseFortyThreeBrowserLibrarySurfacesContractsArePresent" in contract, "ArchitectureContractTest must cover Phase 43")
require(
    "xdm_android_phase43_browser_library_surfaces_overlay.zip" in contract
    and "xdm_android_phase44_browser_settings_privacy_controls_overlay.zip" in contract
    and "xdm_android_phase45_browser_visual_polish_adaptive_layout_overlay.zip" in contract
    and "xdm_android_phase46_browser_private_mode_data_isolation_overlay.zip" in contract,
    "ArchitectureContractTest must allow Phase 43, Phase 44, Phase 45, and Phase 46 current_overlay",
)

if errors:
    for error in errors:
        print(f"ERROR: {error}")
    sys.exit(1)
print("Phase 43 browser library surfaces validation passed")
