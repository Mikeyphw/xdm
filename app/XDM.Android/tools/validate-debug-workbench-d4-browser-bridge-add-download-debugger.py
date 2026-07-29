#!/usr/bin/env python3
from pathlib import Path
import json
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
checks = 0


def read(rel: str) -> str:
    path = ROOT / rel
    if not path.is_file():
        raise AssertionError(f"Missing {rel}")
    return path.read_text(encoding="utf-8")


def require(condition: bool, message: str) -> None:
    global checks
    checks += 1
    if not condition:
        raise AssertionError(message)

manifest = json.loads(read("PROJECT_MANIFEST.json"))
models = read("app/src/main/kotlin/com/mikeyphw/xdm/android/DebugWorkbenchD4DebuggerModels.kt")
card = read("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/debug/BridgeAndAddDownloadDebuggerCard.kt")
screen = read("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/debug/DebugWorkbenchSettingsScreen.kt")
test = read("app/src/test/kotlin/com/mikeyphw/xdm/android/DebugWorkbenchD4BridgeAddDownloadDebuggerTest.kt")
contract = read("app/src/test/kotlin/com/mikeyphw/xdm/android/DebugWorkbenchD4BridgeAddDownloadDebuggerContractTest.kt")
doc = read("docs/architecture/DEBUG-WORKBENCH-D4-BROWSER-BRIDGE-ADD-DOWNLOAD-DEBUGGER.md")

phase = manifest.get("debug_workbench_phase_d4_browser_bridge_add_download_debugger", {})
require(phase.get("status") == "implemented", "D4 manifest status missing")
require(manifest.get("next_phase") == "debug_workbench_phase_d5_transfer_notification_debugger", "D4 next_phase missing")
require(manifest.get("current_overlay") == "xdm_android_debug_workbench_phase_d4_browser_bridge_add_download_debugger_overlay.zip", "current overlay not D4")

for token in [
    "data class BrowserBridgeDebugReport",
    "data class AddDownloadDebugReport",
    "object BrowserBridgeDebugReporter",
    "object AddDownloadDebugReporter",
    "BrowserBridgeDiagnosticsRedactor.sanitize",
    "DebugRedactor.redactUrl",
    "DownloadReviewPlanner.plan",
    "review-only; no transfer starts",
    "copy-only diagnostics",
    "debugLabel()",
]:
    require(token in models, f"D4 models missing {token}")

for token in [
    "BrowserBridgeDebuggerCard(state)",
    "AddDownloadDebuggerCard(state)",
    "XdmSectionHeader(\"Browser bridge debugger\")",
    "XdmSectionHeader(\"Add Download debugger\")",
]:
    require(token in screen, f"Debug Workbench screen missing {token}")

for token in [
    "Browser bridge debugger",
    "Add Download debugger",
    "Copy bridge debugger",
    "Copy Add debugger",
    "does not open a custom scheme",
    "cannot create a transfer",
]:
    require(token in card, f"D4 card missing {token}")

require("onClick = {}" not in card, "D4 card has placeholder click handler")
require("navigate(AppRoute" not in card, "D4 card navigates unexpectedly")
require("queueIntelligenceCoordinator" not in card, "D4 card can enqueue unexpectedly")

for idx, line in enumerate(card.splitlines(), 1):
    renders = re.search(r"\b(?:Text|XdmSupportingText|XdmMetadataText|XdmMetricText)\s*\(", line)
    if renders:
        require(".name" not in line, f"D4 card renders raw enum name at line {idx}")
        require(".url" not in line, f"D4 card renders raw URL at line {idx}")
        require("rawHeaders" not in line and ".cookies" not in line and ".authorization" not in line and ".command" not in line, f"D4 card renders secret-bearing data at line {idx}")

for token in [
    "browserBridgeDebuggerCopiesSafeReportWithoutOpeningSchemes",
    "addDownloadDebuggerExplainsActiveDraftWithoutQueueing",
    "assertFalse(report.copyText.contains(\"token=secret\"))",
    "assertTrue(report.boundaryLabel.contains(\"Nothing is queued\"))",
]:
    require(token in test, f"D4 unit test missing {token}")

for token in [
    "debugWorkbenchContainsBridgeAndAddDownloadDebuggerCards",
    "d4DebuggerIsCopyOnlyAndReviewOnly",
    "d4UiDoesNotRenderRawEnumNamesOrRawLinks",
    "manifestRecordsD4AndNextPhase",
]:
    require(token in contract, f"D4 contract test missing {token}")

for token in [
    "Browser bridge debugger",
    "Add Download debugger",
    "review-only",
    "never opens a custom scheme",
    "DebugRedactor.redactUrl",
]:
    require(token in doc, f"D4 doc missing {token}")

print(f"Debug Workbench D4 validator passed ({checks} checks).")
