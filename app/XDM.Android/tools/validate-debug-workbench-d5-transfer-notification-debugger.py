#!/usr/bin/env python3
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
checks = 0

def require(condition, message):
    global checks
    checks += 1
    if not condition:
        raise SystemExit(f"D5 validator failed: {message}")

def text(relative):
    path = ROOT / relative
    require(path.is_file(), f"missing {relative}")
    return path.read_text()

manifest = json.loads(text("PROJECT_MANIFEST.json"))
screen = text("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/debug/DebugWorkbenchSettingsScreen.kt")
card = text("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/debug/TransferNotificationDebuggerCard.kt")
model = text("app/src/main/kotlin/com/mikeyphw/xdm/android/DebugWorkbenchD5TransferNotificationModels.kt")
unit_test = text("app/src/test/kotlin/com/mikeyphw/xdm/android/DebugWorkbenchD5TransferNotificationDebuggerTest.kt")
contract = text("app/src/test/kotlin/com/mikeyphw/xdm/android/DebugWorkbenchD5TransferNotificationDebuggerContractTest.kt")
doc = text("docs/architecture/DEBUG-WORKBENCH-D5-TRANSFER-NOTIFICATION-DEBUGGER.md")

require(manifest.get("current_overlay") == "xdm_android_debug_workbench_phase_d5_transfer_notification_debugger_overlay.zip", "manifest current_overlay not D5")
require(manifest.get("next_phase") == "debug_workbench_phase_d6_runtime_self_test_suite", "manifest next_phase not D6")
require("debug_workbench_phase_d5_transfer_notification_debugger" in manifest, "manifest missing D5 block")
d5 = manifest["debug_workbench_phase_d5_transfer_notification_debugger"]
require(d5.get("status") == "implemented", "D5 status not implemented")
require(d5.get("top_level_route_added") is False, "D5 must not add top-level route")
require(d5.get("automatic_upload") is False, "D5 must not upload")
require(d5.get("room_schema_unchanged") == 14, "D5 must not change Room schema")
require(d5["transfer_debugger"]["read_only"] is True, "transfer debugger must be read-only")
require(d5["notification_debugger"]["starts_activity"] is False, "D5 must not start activity")
require(d5["privacy"]["renders_raw_urls"] is False, "D5 must not render raw URLs")
require(d5["privacy"]["renders_raw_enum_names"] is False, "D5 must not render raw enum names")

require("TransferNotificationDebuggerCard(state)" in screen, "screen does not host D5 card")
require('XdmSectionHeader("Transfer + notification debugger")' in screen, "screen missing D5 section header")
require("Transfer + notification debugger" in card, "card missing title")
require("Copy transfer debugger" in card, "card missing copy action")
require("TransferNotificationDebugReporter.summarize" in card, "card not using reporter")
require("state.downloads" in card and "state.activeTransfers" in card, "card not using transfer state")
require("copyTextToClipboard" in card, "card missing copy action implementation")
require("onClick = {}" not in card, "card has placeholder click")
require("startActivity" not in card and "Intent(" not in card, "card must not launch activities")
require("runtime.pause" not in card and "runtime.resume" not in card and "runtime.cancel" not in card, "card must not control runtime")

render_re = re.compile(r"\b(?:Text|XdmSupportingText|XdmMetadataText|XdmMetricText)\s*\(")
for idx, line in enumerate(card.splitlines(), 1):
    if render_re.search(line):
        require(".name" not in line, f"card renders raw enum name at line {idx}")
        require(".url" not in line, f"card renders raw URL at line {idx}")
        require("rawJson" not in line and "JSONObject" not in line and "JSONArray" not in line, f"card renders raw machine value at line {idx}")

require("object TransferNotificationDebugReporter" in model, "model missing reporter")
require("DebugRedactor.redactUrl" in model, "model must redact URLs")
require("DebugRedactor.fingerprint" in model, "model must fingerprint identifiers")
require("transferStateLabel" in model, "model must humanize state")
require("transferBackendLabel" in model, "model must humanize backend")
require("non-exported open-file trampoline" in model, "model missing completed notification open-file path")
require("completed-file tap" in model, "model missing completed notification explanation")
require("read-only diagnostics" in model, "model missing read-only boundary")
require("no transfer control, viewer launch, file probe, or upload" in model, "model missing full safety boundary")
require("TransferForegroundService" not in model, "model must not start foreground service")
require("OpenDownloadedFileActivity(" not in model, "model must not construct open-file activity")
require("appendLine(\"Destination: ${download.destinationUri}" not in model, "model copies raw destination URI")
require("state.name" not in model and "backend.name" not in model, "model uses raw enum display")

require("transferDebuggerExplainsActiveTransferWithoutLeakingUrls" in unit_test, "unit test missing active transfer case")
require("transferDebuggerExplainsCompletedOpenFileFallbackPath" in unit_test, "unit test missing completed open case")
require("transferDebuggerLabelsFailuresForCopyOnlyDiagnosis" in unit_test, "unit test missing failure label case")
require("secret-token" in unit_test and "assertFalse" in unit_test, "unit test must assert secret removal")
require("debugWorkbenchHostsTransferNotificationDebugger" in contract, "contract missing host check")
require("d5UiDoesNotRenderRawEnumNamesUrlsOrMachineValues" in contract, "contract missing UI seal check")
require("manifestRecordsD5AndNextPhase" in contract, "contract missing manifest check")

require("Settings → Debug Workbench" in doc, "doc missing location")
require("does not pause, resume, cancel, retry" in doc, "doc missing no-control boundary")
require("non-exported trampoline" in doc, "doc missing open-file trampoline")
require("No automatic upload" not in doc or "automatic upload" in doc, "doc should discuss upload boundary")

print(f"D5 validator: passed, {checks} checks")
