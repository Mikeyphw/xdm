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
if manifest.get("current_overlay") not in {"xdm_android_phase39_browser_chrome_navigation_overlay.zip", "xdm_android_phase40_browser_tabs_session_ux_overlay.zip", "xdm_android_phase41_browser_download_bridge_overlay.zip", "xdm_android_phase42_browser_media_capture_cockpit_overlay.zip",
    "xdm_android_phase43_browser_library_surfaces_overlay.zip", "xdm_android_phase44_browser_settings_privacy_controls_overlay.zip", "xdm_android_phase45_browser_visual_polish_adaptive_layout_overlay.zip", "xdm_android_phase46_browser_private_mode_data_isolation_overlay.zip", "xdm_android_phase47_browser_permission_ux_settings_polish_overlay.zip", "xdm_android_phase43_browser_library_surfaces_overlay.zip", "xdm_android_phase48_browser_resource_inspector_overlay.zip", "xdm_android_phase49_50_download_rules_ux_polish_overlay.zip"}:
    errors.append("current_overlay must point at the Phase 39 browser chrome/navigation overlay or approved later Phase 40 tabs/session overlay")

phase = manifest.get("phase39_browser_chrome_navigation", {})
for key in [
    "phase38_landed",
    "address_search_bar_polish",
    "back_forward_state",
    "reload_stop_home_controls",
    "current_page_title_and_location",
    "webview_back_handler",
    "navigation_snapshot_model",
    "add_current_page_url_action",
    "tab_groundwork_preserved",
    "bookmarks_history_redesign_deferred",
    "no_new_route",
    "no_room_migration",
    "no_version_bump",
    "no_transfer_engine_changes",
    "no_media_execution_changes",
]:
    if phase.get(key) is not True:
        errors.append(f"phase39_browser_chrome_navigation.{key} must be true")

browser = read("app/src/main/kotlin/com/mikeyphw/xdm/android/BrowserScreen.kt")
routes = read("app/src/main/kotlin/com/mikeyphw/xdm/android/AppRoute.kt")
contract = read("app/src/test/kotlin/com/mikeyphw/xdm/android/ArchitectureContractTest.kt")
run_gate = read("tools/run-final-release-gate.sh")
workflow = read(".github/workflows/android.yml")
doc = read("docs/browser/PHASE-39-BROWSER-CHROME-NAVIGATION.md")

required_browser_tokens = [
    "BrowserChromeState",
    "browserChromeState",
    "BackHandler(enabled = browserChromeState.canGoBack",
    "canGoBack",
    "canGoForward",
    "onHome",
    "onStop",
    "stopLoading()",
    "Browser home",
    "Stop loading",
    "onNavigationChanged",
    "snapshot(isLoading",
    "Add URL",
]
for token in required_browser_tokens:
    if token not in browser:
        errors.append(f"BrowserScreen.kt must contain {token!r}")

if "data class BrowserChromeState" not in browser or "fun snapshot(isLoading: Boolean" not in browser:
    errors.append("Browser chrome/navigation state must be modeled explicitly")

if "Icons.Rounded.Home" not in browser or "Icons.Rounded.Refresh" not in browser:
    errors.append("Browser chrome must expose Home and Reload controls")

if 'Browser("Browser", Icons.Rounded.Public)' not in routes:
    errors.append("Phase 39 depends on the Phase 37B Browser route")

if any(label in routes for label in ['Chrome("Chrome"', 'Tabs("Tabs"', 'History("History"', 'Bookmarks("Bookmarks"']):
    errors.append("Phase 39 must not add Chrome/Tabs/History/Bookmarks as top-level routes")

if "phaseThirtyNineBrowserChromeNavigationContractsArePresent" not in contract:
    errors.append("ArchitectureContractTest must include Phase 39 browser chrome/navigation coverage")

if "validate-phase-39-browser-chrome-navigation.py" not in run_gate:
    errors.append("Final release gate must include Phase 39 validator")

if "validate-phase-39-browser-chrome-navigation.py" not in workflow:
    errors.append("Android CI must include Phase 39 validator")

for token in ["Back", "Forward", "Home", "Reload", "Stop", "Add URL", "no new top-level route"]:
    if token not in doc:
        errors.append(f"Phase 39 doc must mention {token}")

# Keep the Phase 38 Compose lint posture. Modifier must stay immediately after required captures.
sig = re.search(r"fun BrowserScreen\((.*?)\n\)", browser, re.S)
if sig:
    signature = sig.group(1)
    if signature.find("modifier: Modifier = Modifier") > signature.find("initialUrl: String? = null"):
        errors.append("BrowserScreen modifier must remain the first optional parameter")
else:
    errors.append("BrowserScreen signature not found")

if "__pycache__" in "\n".join(p.as_posix() for p in ROOT.rglob("*")):
    errors.append("repository tree must not contain __pycache__ in overlay inputs")

if errors:
    print("Phase 39 browser chrome/navigation validation failed:")
    for error in errors:
        print(f"- {error}")
    sys.exit(1)

print("Phase 39 browser chrome/navigation validation passed")
