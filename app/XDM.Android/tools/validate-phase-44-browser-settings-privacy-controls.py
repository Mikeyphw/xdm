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
phase = manifest.get("phase44_browser_settings_privacy_controls", {})
browser = read("app/src/main/kotlin/com/mikeyphw/xdm/android/BrowserScreen.kt")
routes = read("app/src/main/kotlin/com/mikeyphw/xdm/android/AppRoute.kt")
contract = read("app/src/test/kotlin/com/mikeyphw/xdm/android/ArchitectureContractTest.kt")
run_gate = read("tools/run-final-release-gate.sh")
workflow = read(".github/workflows/android.yml")
doc = ROOT / "docs/browser/PHASE-44-BROWSER-SETTINGS-PRIVACY-CONTROLS.md"

allowed_overlays = {
    "xdm_android_phase44_browser_settings_privacy_controls_overlay.zip",
    "xdm_android_phase45_browser_visual_polish_adaptive_layout_overlay.zip", "xdm_android_phase46_browser_private_mode_data_isolation_overlay.zip", "xdm_android_phase47_browser_permission_ux_settings_polish_overlay.zip",
    "xdm_android_phase48_browser_resource_inspector_overlay.zip", "xdm_android_phase49_50_download_rules_ux_polish_overlay.zip",
}
require(doc.is_file(), "Phase 44 browser settings/privacy doc is missing")
require(manifest.get("current_overlay") in allowed_overlays or str(manifest.get("current_overlay", "")).startswith("xdm_android_browser_removal_phase"), "current_overlay must point at the Phase 44 browser settings/privacy overlay or approved Phase 45 visual polish overlay")
for key in [
    "phase43_landed",
    "homepage_setting",
    "default_search_engine_setting",
    "javascript_toggle",
    "dom_storage_toggle",
    "desktop_mode_default_toggle",
    "cookie_controls",
    "third_party_cookie_control",
    "clear_browser_data",
    "private_profile_groundwork",
    "shared_preferences_only",
    "bookmarks_preserved_on_clear_data",
    "no_new_route",
    "no_room_migration",
    "no_version_bump",
    "no_transfer_engine_changes",
    "no_media_execution_changes",
]:
    require(phase.get(key) is True, f"phase44_browser_settings_privacy_controls.{key} must be true")
require(phase.get("raw_header_cookie_token_persistence") is False, "raw header/cookie/token persistence must remain false")

require("BrowserPrivacySettings" in browser and "BrowserPrivacySettingsPanel" in browser, "Browser must expose BrowserPrivacySettings and a settings panel")
require("BrowserHomePage" in browser and "Homepage" in browser and "home_page" in browser, "Browser must expose a homepage setting")
require("BrowserSearchEngine" in browser and "Search engine" in browser and "search_engine" in browser, "Browser must expose a default search engine setting")
require("javaScriptEnabled" in browser and "JavaScript" in browser and "settings.javaScriptEnabled = browserSettings.javaScriptEnabled" in browser, "Browser must expose and apply a JavaScript toggle")
require("domStorageEnabled" in browser and "DOM storage" in browser and "settings.domStorageEnabled = browserSettings.domStorageEnabled && !profile.privateMode" in browser, "Browser must expose and apply a DOM storage toggle")
require("desktopModeDefault" in browser and "Desktop default" in browser and "val desktopMode = profile.desktopMode || browserSettings.desktopModeDefault" in browser, "Browser must expose and apply a desktop mode default")
require("cookiesEnabled" in browser and "thirdPartyCookiesEnabled" in browser and "setAcceptThirdPartyCookies" in browser, "Browser must expose cookie and third-party-cookie controls")
require("Clear browser data" in browser and "clearBrowsingData" in browser and "removeAllCookies" in browser and "WebStorage.getInstance().deleteAllData" in browser, "Browser must expose clear browser data and clear cookies/DOM storage")
require("savePrivacySettings" in browser and "loadPrivacySettings" in browser and "xdm_browser_sessions" in browser, "Browser privacy settings must persist in the browser session store")
require(browser.find("fun openBrowserEntry(") != -1 and browser.find("fun openHome()") != -1 and browser.find("fun openBrowserEntry(") < browser.find("fun openHome()"), "openBrowserEntry must be declared before openHome so Kotlin local function resolution can compile")
require(("Private profile overrides" in browser or "Private profile and private tabs override" in browser) and ("Full private-tab isolation" in doc.read_text() or "phase46_browser_private_mode_data_isolation" in manifest), "Phase 44 must document private-profile groundwork or be superseded by Phase 46 private tabs")
require("raw cookie, token, or sensitive header" in doc.read_text(), "Phase 44 doc must preserve raw secret persistence guardrail")

for forbidden in ["BrowserSettings(\"BrowserSettings\"", "Privacy(\"Privacy\"", "Cookies(\"Cookies\""]:
    require(forbidden not in routes, f"Phase 44 must not add top-level route {forbidden}")

# clearBrowsingData deliberately removes transient data but not bookmarks.
clear_body = browser.split("fun clearBrowsingData()", 1)[1].split("fun loadTabs", 1)[0]
require("remove(KeyHistory)" in clear_body and "remove(KeyTabs)" in clear_body and "remove(KeyBookmarks)" not in clear_body, "Clear browsing data must remove tabs/history but preserve bookmarks")

require("validate-phase-44-browser-settings-privacy-controls.py" in run_gate, "Final release gate must run Phase 44 validator")
require("validate-phase-44-browser-settings-privacy-controls.py" in workflow, "Android CI must run Phase 44 validator")
require("phaseFortyFourBrowserSettingsPrivacyControlsContractsArePresent" in contract, "ArchitectureContractTest must cover Phase 44")
require(all(overlay in contract for overlay in allowed_overlays), "ArchitectureContractTest must allow Phase 44 and Phase 45 current_overlay")

if errors:
    for error in errors:
        print(f"ERROR: {error}")
    sys.exit(1)
print("Phase 44 browser settings/privacy controls validation passed")
