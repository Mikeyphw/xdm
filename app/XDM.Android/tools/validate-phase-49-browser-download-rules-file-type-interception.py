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
contract = (root / "app/src/test/kotlin/com/mikeyphw/xdm/android/ArchitectureContractTest.kt").read_text()
require(manifest.get("current_overlay") == "xdm_android_phase49_50_download_rules_ux_polish_overlay.zip", "current_overlay must point at combined Phase 49/50 overlay")
require("phase49_browser_download_rules_file_type_interception" in manifest, "manifest must record Phase 49")
require("BrowserDownloadRules" in browser and "BrowserDownloadRulesPanel" in browser and "BrowserDownloadRuleDecision" in browser, "download rule model/panel must exist")
require("BrowserDownloadCategory" in browser and "classifyBrowserDownload" in browser, "file-type classification must exist")
for token in ["Archive", "Apk", "Document", "Media", "Torrent", "Other"]:
    require(token in browser, f"download category {token} must be represented")
require("No silent auto-queue" not in browser or "never queues silently" in browser or "never queue" in browser.lower(), "review-first copy must be present")
require("executionStarter.start" not in browser and "addDownload(" not in browser, "BrowserScreen must not start transfers directly")
require("ResourceInspector(" not in routes and "DownloadRules(" not in routes, "Phase 49 must not add routes")
require("phaseFortyNineAndFiftyBrowserDownloadRulesUxPolishContractsArePresent" in contract, "ArchitectureContractTest must include combined Phase 49/50 contract")
if errors:
    for e in errors: print(e, file=sys.stderr)
    sys.exit(1)
print("Phase 49 browser download rules/file-type interception validation passed")
