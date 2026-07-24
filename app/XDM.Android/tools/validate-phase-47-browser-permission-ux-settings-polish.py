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

def require(cond, msg):
    if not cond:
        raise SystemExit(msg)
phase = manifest.get("phase47_browser_permission_ux_settings_polish", {})
allowed = {"xdm_android_phase47_browser_permission_ux_settings_polish_overlay.zip", "xdm_android_phase48_browser_resource_inspector_overlay.zip"}
require(manifest.get("current_overlay") in allowed, "current_overlay must point at Phase 47 or approved later Phase 48 overlay")
for key in ["phase46_landed", "site_permission_prompt", "permission_decision_status", "reset_browser_settings", "no_new_route", "no_room_migration", "no_transfer_engine_changes", "no_media_execution_changes", "no_adblock_proxy_dns_changes"]:
    require(phase.get(key) is True, f"Phase 47 manifest key {key} missing/false")
require("BrowserPermissionStatusPanel" in browser and "Site permissions" in browser, "permission status panel missing")
require("onPermissionRequest" in browser and "PermissionRequest" in browser, "WebView permission request hook missing")
require("onGeolocationPermissionsShowPrompt" in browser and "GeolocationPermissions" in browser, "geolocation permission UX missing")
require("Grant once" in browser and "Deny" in browser and "Recent permission decisions" in browser, "permission decision UX missing")
require("Reset browser settings" in browser and "resetBrowserPrivacySettings" in browser, "settings reset polish missing")
require("No durable permission allow-list" not in browser, "implementation must not claim durable permission storage")
require("Permission(" not in routes and "SitePermissions(" not in routes, "Phase 47 must not add permission top-level routes")
require("validate-phase-47-browser-permission-ux-settings-polish.py" in run_gate, "final gate missing Phase 47 validator")
require("validate-phase-47-browser-permission-ux-settings-polish.py" in workflow, "CI missing Phase 47 validator")
require("phaseFortySevenBrowserPermissionUxSettingsPolishContractsArePresent" in contract, "Architecture contract missing Phase 47")
print("Phase 47 browser permission UX + settings polish validation passed")
