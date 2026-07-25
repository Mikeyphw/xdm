#!/usr/bin/env python3
from pathlib import Path
import json, sys
root = Path(__file__).resolve().parents[1]
errors = []
def require(cond, msg):
    if not cond: errors.append(msg)
manifest = json.loads((root / "PROJECT_MANIFEST.json").read_text())
browser = (root / "app/src/main/kotlin/com/mikeyphw/xdm/android/BrowserScreen.kt").read_text()
routes = (root / "app/src/main/kotlin/com/mikeyphw/xdm/android/AppRoute.kt").read_text()
run_gate = (root / "tools/run-final-release-gate.sh").read_text()
workflow = (root / ".github/workflows/android.yml").read_text()
require(manifest.get("current_overlay") == "xdm_android_phase49_50_download_rules_ux_polish_overlay.zip" or str(manifest.get("current_overlay", "")).startswith("xdm_android_browser_removal_phase"), "current_overlay must point at combined Phase 49/50 overlay")
require("phase50_browser_downloader_ux_polish_seal" in manifest, "manifest must record Phase 50")
require("BrowserDownloaderFlowPanel" in browser and "Browser → Downloader flow" in browser, "flow panel must be present")
require("Direct files follow download rules" in browser and "media stays in the capture cockpit" in browser, "dual path polish copy must be present")
require("Resources (" in browser and "Media (" in browser and "Add page" in browser, "flow panel entry points must be visible")
require("private enum class BrowserDownloadCategory" in browser and "private enum class BrowserDownloadRuleDecision" in browser, "download rule/category models must remain local")
require("ResourceInspector(" not in routes and "BrowserPolish(" not in routes and "DownloadRules(" not in routes, "Phase 50 must not add routes")
require("validate-phase-49-browser-download-rules-file-type-interception.py" in run_gate and "validate-phase-50-browser-downloader-ux-polish-seal.py" in run_gate, "final gate must include Phase 49/50 validators")
require("validate-phase-49-browser-download-rules-file-type-interception.py" in workflow and "validate-phase-50-browser-downloader-ux-polish-seal.py" in workflow, "CI must include Phase 49/50 validators")
if errors:
    for e in errors: print(e, file=sys.stderr)
    sys.exit(1)
print("Phase 50 browser/downloader UX polish seal validation passed")
