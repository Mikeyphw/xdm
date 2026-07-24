#!/usr/bin/env python3
from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
manifest = json.loads((ROOT / "PROJECT_MANIFEST.json").read_text())
browser = (ROOT / "app/src/main/kotlin/com/mikeyphw/xdm/android/BrowserScreen.kt").read_text()
routes = (ROOT / "app/src/main/kotlin/com/mikeyphw/xdm/android/AppRoute.kt").read_text()
contract = (ROOT / "app/src/test/kotlin/com/mikeyphw/xdm/android/ArchitectureContractTest.kt").read_text()
run_gate = (ROOT / "tools/run-final-release-gate.sh").read_text()
workflow = (ROOT / ".github/workflows/android.yml").read_text()

def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(message)

phase = manifest.get("phase46_browser_private_mode_data_isolation", {})
require(manifest.get("current_overlay") in {'xdm_android_phase46_browser_private_mode_data_isolation_overlay.zip', 'xdm_android_phase47_browser_permission_ux_settings_polish_overlay.zip'}, "current_overlay must point at Phase 46 or approved later browser overlay")
require(phase.get("phase45_landed") is True, "Phase 46 must depend on Phase 45")
require(phase.get("no_new_route") is True, "Phase 46 must not add a new route")
require(phase.get("no_room_migration") is True, "Phase 46 must not add a Room migration")
require(phase.get("no_transfer_engine_changes") is True, "Phase 46 must not change transfer engines")
require(phase.get("no_media_execution_changes") is True, "Phase 46 must not change media execution")
require(phase.get("no_adblock_proxy_dns_changes") is True, "Phase 46 must not add adblock/proxy/DNS behavior")

require("val activeTabIsPrivate = activeTab.isPrivate" in browser, "Active private-tab state is missing")
require("privateTabCount = tabs.count { it.isPrivate }" in browser, "Private tab count is missing")
require("effectiveCookieProfile = if (activeTabIsPrivate) BrowserCookieProfile.Private else cookieProfile" in browser, "Private tabs must force the Private cookie profile")
require("fun openNewPrivateTab()" in browser and "BrowserTab.blank(isPrivate = true)" in browser, "New private tab action is missing")
require("fun closePrivateTabs()" in browser and "tabs.filterNot { it.isPrivate }" in browser, "Clear private tabs action is missing")
require("if (!activeTabIsPrivate)" in browser and "recordHistory" in browser, "Private tabs must be excluded from browser history")
require("onMediaDiscovered = { url, mimeType ->" in browser and "if (!activeTabIsPrivate)" in browser and "onMediaRequestState" in browser, "Private tabs must suppress passive media capture persistence")
require("tabs.filterNot { it.isPrivate }.take(MaxStoredTabs)" in browser, "Private tabs must be excluded from session restore persistence")
require("clearPrivateBrowsingData" in browser and "removeSessionCookies" in browser, "Private close must clear session cookies")
require("settings.domStorageEnabled = browserSettings.domStorageEnabled && !profile.privateMode" in browser, "Private WebView settings must disable DOM storage")
require("New private tab" in browser and "Clear private" in browser and "Private tab active" in browser, "Private-mode UX copy/actions are missing")
require("Browser(" in routes, "Existing Browser route must remain present")
require("Private(" not in routes and "Incognito(" not in routes, "Phase 46 must not add private/incognito top-level routes")
require("validate-phase-46-browser-private-mode-data-isolation.py" in run_gate, "Final release gate must include Phase 46 validator")
require("validate-phase-46-browser-private-mode-data-isolation.py" in workflow, "Android CI must include Phase 46 validator")
require("phaseFortySixBrowserPrivateModeDataIsolationContractsArePresent" in contract, "Architecture contract for Phase 46 is missing")


old_contract_keys = [
    "phase35_release_candidate_polish",
    "phase36_external_download_handoff",
    "phase37a_browser_downloader_roadmap",
    "phase37b_dual_launcher_navigation_split",
    "phase38_browser_reliability_foundation",
    "phase39_browser_chrome_navigation",
    "phase40_browser_tabs_session_ux",
    "phase41_browser_download_bridge",
    "phase42_browser_media_capture_cockpit",
    "phase43_browser_library_surfaces",
    "phase44_browser_settings_privacy_controls",
    "phase45_browser_visual_polish_adaptive_layout",
]
phase46_overlay = "xdm_android_phase46_browser_private_mode_data_isolation_overlay.zip"
for key in old_contract_keys:
    start = contract.find(key)
    require(start != -1, f"Architecture contract key {key} is missing")
    section = contract[start:]
    next_marker = section.find("@Test", 1)
    if next_marker != -1:
        section = section[:next_marker]
    require(phase46_overlay in section, f"Architecture contract for {key} must allow the Phase 46 current_overlay")

print("Phase 46 browser private mode + data isolation validation passed")
