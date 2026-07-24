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

phase = manifest.get("phase45_browser_visual_polish_adaptive_layout", {})
require(manifest.get("current_overlay") in {"xdm_android_phase45_browser_visual_polish_adaptive_layout_overlay.zip", "xdm_android_phase46_browser_private_mode_data_isolation_overlay.zip", "xdm_android_phase47_browser_permission_ux_settings_polish_overlay.zip", "xdm_android_phase48_browser_resource_inspector_overlay.zip"}, "current_overlay must point at Phase 45 or later Phase 46 overlay")
require(phase.get("phase44_landed") is True, "Phase 45 must depend on Phase 44")
require(phase.get("no_new_route") is True, "Phase 45 must not add a new route")
require(phase.get("no_room_migration") is True, "Phase 45 must not add a Room migration")
require(phase.get("no_transfer_engine_changes") is True, "Phase 45 must not change transfer engines")
require(phase.get("no_media_execution_changes") is True, "Phase 45 must not change media execution")
require(phase.get("no_adblock_proxy_dns_changes") is True, "Phase 45 must not add adblock/proxy/DNS behavior")

require("BrowserVisualStatusBar" in browser, "Browser visual status bar is missing")
require("Adaptive browser cockpit" in browser, "Adaptive cockpit copy is missing")
require("BrowserMaxContentWidthDp" in browser and "sizeIn(maxWidth = BrowserMaxContentWidthDp.dp)" in browser, "Centered wide-screen browser width guard is missing")
require("Review-first bridge keeps browser downloads separate" in browser, "Download bridge visual hierarchy copy is missing")
require("compact cockpit" in browser, "Media cockpit visual hierarchy copy is missing")
require("Adaptive layout centers the browser cockpit" in browser, "Start page adaptive layout copy is missing")
require("Adblock" not in browser and "proxy chain" not in browser and "encrypted DNS" not in browser, "Phase 45 must not add adblock/proxy/DNS UI")
require("Browser(" in routes, "Existing Browser route must remain present")
require("VisualPolish(" not in routes and "Layout(" not in routes, "Phase 45 must not add visual-polish/layout routes")
require("validate-phase-45-browser-visual-polish-adaptive-layout.py" in run_gate, "Final release gate must include Phase 45 validator")
require("validate-phase-45-browser-visual-polish-adaptive-layout.py" in workflow, "Android CI must include Phase 45 validator")
require("phaseFortyFiveBrowserVisualPolishAdaptiveLayoutContractsArePresent" in contract, "Architecture contract for Phase 45 is missing")

print("Phase 45 browser visual polish + adaptive layout validation passed")
