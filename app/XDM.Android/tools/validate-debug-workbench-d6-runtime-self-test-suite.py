#!/usr/bin/env python3
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
checks = []

def read(rel):
    path = ROOT / rel
    if not path.exists():
        raise SystemExit(f"missing required file: {rel}")
    return path.read_text()

def require(label, condition):
    if not condition:
        raise SystemExit(f"D6 validation failed: {label}")
    checks.append(label)

model = read("app/src/main/kotlin/com/mikeyphw/xdm/android/DebugWorkbenchD6RuntimeSelfTestModels.kt")
card = read("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/debug/RuntimeSelfTestSuiteCard.kt")
screen = read("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/debug/DebugWorkbenchSettingsScreen.kt")
unit = read("app/src/test/kotlin/com/mikeyphw/xdm/android/DebugWorkbenchD6RuntimeSelfTestSuiteTest.kt")
contract = read("app/src/test/kotlin/com/mikeyphw/xdm/android/DebugWorkbenchD6RuntimeSelfTestSuiteContractTest.kt")
docs = read("docs/architecture/DEBUG-WORKBENCH-D6-RUNTIME-SELF-TEST-SUITE.md")
manifest = read("PROJECT_MANIFEST.json")

require("model object exists", "object DebugWorkbenchRuntimeSelfTestSuite" in model)
require("runtime report exists", "data class RuntimeSelfTestSuiteReport" in model)
require("checks exist", "RuntimeSelfTestCheck" in model)
for token in ["manifest-routes", "browser-scheme", "file-open-path", "media-sniffer", "redaction", "notification-intent", "recorder-health", "support-report", "state-context"]:
    require(f"check {token}", token in model)
require("uses shared sniffer", "MediaSniffingEngine" in model and "MediaSniffingSource.SharedText" in model)
require("uses redactor url", "DebugRedactor.redactUrl" in model)
require("uses redactor text", "DebugRedactor.redactText" in model)
require("uses key-aware redactor details", "DebugRedactor.redactDetails" in model)
require("redaction smoke bearer token is long enough for pattern", "abc.def.ghi" in model)
require("no page probe", "MediaPageProbe(" not in model)
require("no foreground service", "TransferForegroundService" not in model)
require("no open-file construction", "OpenDownloadedFileActivity(" not in model)
require("read only boundary", "no downloads, viewers, file probes, browser probes, or uploads" in model)
require("card exists", "Runtime self-test suite" in card)
require("copy action exists", "Copy self-test report" in card)
require("card hosted", "RuntimeSelfTestSuiteCard(state)" in screen)
require("no placeholder clicks", "onClick = {}" not in card)
require("no activity launch", "startActivity" not in card and "Intent(" not in card)
require("no raw enum rendering", ".name" not in card)
require("no raw url rendering", ".url" not in card)
require("has status tone", "statusTone" in card)
require("uses existing spacing token", "Arrangement.spacedBy(XdmSpacing.TightGap)" in card)
require("no nonexistent spacing alias", "XdmSpacing.sm" not in card)
require("unit redaction assertion", "secret-token" in unit and "assertFalse" in unit)
require("unit sniffer assertion", "media-sniffer" in unit)
require("contract release seal", "d6UiDoesNotRenderRawEnumNamesUrlsOrMachineValues" in contract)
require("contract read only", "d6SuiteIsReadOnlyAndDoesNotLaunchOrProbe" in contract)
require("manifest records d6", "debug_workbench_phase_d6_runtime_self_test_suite" in manifest)
require("manifest next d7", "debug_workbench_phase_d7_final_debug_seal" in manifest)
require("docs boundaries", "does not start downloads" in docs and "run network probes" in docs)
require("docs next", "D7" in docs)

print(f"Debug Workbench D6 runtime self-test suite validator: passed, {len(checks)} checks")
