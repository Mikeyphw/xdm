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
phase = manifest.get("phase48_browser_resource_inspector", {})
require(manifest.get("current_overlay") == "xdm_android_phase48_browser_resource_inspector_overlay.zip", "current_overlay must point at Phase 48 overlay")
for key in ["phase47_landed", "resource_inspector_panel", "resource_filters", "open_add_inspect_actions", "source_page_context", "no_new_route", "no_room_migration", "no_transfer_engine_changes", "no_media_execution_changes"]:
    require(phase.get(key) is True, f"Phase 48 manifest key {key} missing/false")
require("BrowserResourceInspectorPanel" in browser and "Resource inspector" in browser, "resource inspector panel missing")
require("BrowserResourceFilter" in browser and "Inspect resources" in browser and "Hide resources" in browser, "resource filters/toggle missing")
require("Inspect media" in browser and "onInspectResource" in browser, "resource inspect media action missing")
require("MaxVisibleInspectorResources = 12" in browser, "resource inspector bound missing")
require("ResourceInspector(" not in routes and "PageResources(" not in routes, "Phase 48 must not add resource top-level routes")
require("validate-phase-48-browser-resource-inspector.py" in run_gate, "final gate missing Phase 48 validator")
require("validate-phase-48-browser-resource-inspector.py" in workflow, "CI missing Phase 48 validator")
require("phaseFortyEightBrowserResourceInspectorContractsArePresent" in contract, "Architecture contract missing Phase 48")
print("Phase 48 browser resource inspector validation passed")
