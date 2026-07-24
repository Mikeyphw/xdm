#!/usr/bin/env python3
from pathlib import Path
import json
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
errors = []

def read(path: str) -> str:
    p = ROOT / path
    if not p.exists():
        errors.append(f"missing {path}")
        return ""
    return p.read_text()

manifest = json.loads(read("PROJECT_MANIFEST.json") or "{}")
if manifest.get("current_overlay") not in {"xdm_android_phase40_browser_tabs_session_ux_overlay.zip", "xdm_android_phase41_browser_download_bridge_overlay.zip", "xdm_android_phase42_browser_media_capture_cockpit_overlay.zip",
    "xdm_android_phase43_browser_library_surfaces_overlay.zip", "xdm_android_phase43_browser_library_surfaces_overlay.zip"}:
    errors.append("current_overlay must point at the Phase 40 browser tabs/session UX overlay or approved later Phase 41 browser download bridge overlay")

phase = manifest.get("phase40_browser_tabs_session_ux", {})
for key in [
    "phase39_landed",
    "explicit_tab_session_state",
    "tab_switcher_toggle",
    "restored_session_banner",
    "current_tab_summary",
    "new_and_close_tab_actions",
    "bounded_compact_tab_list",
    "private_tab_placeholder_deferred",
    "webview_state_bundle_deferred",
    "bookmarks_history_redesign_deferred",
    "no_new_route",
    "no_room_migration",
    "no_version_bump",
    "no_transfer_engine_changes",
    "no_media_execution_changes",
]:
    if phase.get(key) is not True:
        errors.append(f"phase40_browser_tabs_session_ux.{key} must be true")

browser = read("app/src/main/kotlin/com/mikeyphw/xdm/android/BrowserScreen.kt")
routes = read("app/src/main/kotlin/com/mikeyphw/xdm/android/AppRoute.kt")
contract = read("app/src/test/kotlin/com/mikeyphw/xdm/android/ArchitectureContractTest.kt")
run_gate = read("tools/run-final-release-gate.sh")
workflow = read(".github/workflows/android.yml")
doc = read("docs/browser/PHASE-40-BROWSER-TABS-SESSION-UX.md")

required_browser_tokens = [
    "BrowserTabSessionState",
    "browserTabSessionState",
    "restoredTabs",
    "restoredTabCount",
    "showTabSwitcher",
    "BrowserTabSwitcher",
    "Browser session",
    "Show tabs",
    "Hide tabs",
    "Open tabs",
    "Restored ",
    "New tab",
    "Close tab",
    "Clear tab",
    "Private tab isolation remains reserved for the privacy phase",
    "MaxVisibleTabs = 8",
    "MaxStoredTabs = 12",
]
for token in required_browser_tokens:
    if token not in browser:
        errors.append(f"BrowserScreen.kt must contain {token!r}")

if "data class BrowserTabSessionState" not in browser or "val summary: String" not in browser:
    errors.append("Browser tab/session state must be modeled explicitly with a summary")

if "private fun BrowserTabSwitcher(" not in browser or "modifier: Modifier = Modifier" not in browser:
    errors.append("Browser tab switcher must be a composable with Modifier as first optional parameter")

sig = re.search(r"fun BrowserScreen\((.*?)\n\)", browser, re.S)
if sig:
    signature = sig.group(1)
    if signature.find("modifier: Modifier = Modifier") > signature.find("initialUrl: String? = null"):
        errors.append("BrowserScreen modifier must remain the first optional parameter")
else:
    errors.append("BrowserScreen signature not found")

if any(label in routes for label in ['Tabs("Tabs"', 'History("History"', 'Bookmarks("Bookmarks"']):
    errors.append("Phase 40 must not add Tabs/History/Bookmarks top-level routes")

if "phaseFortyBrowserTabsSessionUxContractsArePresent" not in contract:
    errors.append("ArchitectureContractTest must include Phase 40 browser tabs/session UX coverage")

if "validate-phase-40-browser-tabs-session-ux.py" not in run_gate:
    errors.append("Final release gate must include Phase 40 validator")

if "validate-phase-40-browser-tabs-session-ux.py" not in workflow:
    errors.append("Android CI must include Phase 40 validator")

for token in ["BrowserTabSessionState", "BrowserTabSwitcher", "restored-session", "private-tab isolation", "No new top-level routes"]:
    if token not in doc:
        errors.append(f"Phase 40 doc must mention {token}")

if "__pycache__" in "\n".join(p.as_posix() for p in ROOT.rglob("*")):
    errors.append("repository tree must not contain __pycache__ in overlay inputs")

if errors:
    print("Phase 40 browser tabs/session UX validation failed:")
    for error in errors:
        print(f"- {error}")
    sys.exit(1)

print("Phase 40 browser tabs/session UX validation passed")
