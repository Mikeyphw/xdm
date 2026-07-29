#!/usr/bin/env python3
from __future__ import annotations

import json
import re
import sys
from pathlib import Path


def find_root() -> Path:
    cursor = Path.cwd().resolve()
    for candidate in [cursor, *cursor.parents]:
        if (candidate / "PROJECT_MANIFEST.json").is_file() and (candidate / "app/src").is_dir():
            return candidate
        if (candidate / "settings.gradle.kts").is_file() and (candidate / "PROJECT_MANIFEST.json").is_file():
            return candidate
        nested = candidate / "app" / "XDM.Android"
        if (nested / "PROJECT_MANIFEST.json").is_file() and (nested / "app/src").is_dir():
            return nested
        if (nested / "settings.gradle.kts").is_file() and (nested / "PROJECT_MANIFEST.json").is_file():
            return nested
    raise SystemExit("Unable to locate XDM Android root")


ROOT = find_root()
checks: list[str] = []


def read(relative: str) -> str:
    path = ROOT / relative
    if not path.is_file():
        raise SystemExit(f"missing required file: {relative}")
    return path.read_text(encoding="utf-8")


def optional_read(relative: str) -> str | None:
    path = ROOT / relative
    if not path.is_file():
        return None
    return path.read_text(encoding="utf-8")


def require(label: str, condition: bool) -> None:
    if not condition:
        raise SystemExit(f"D7 validation failed: {label}")
    checks.append(label)


manifest_text = read("PROJECT_MANIFEST.json")
manifest = json.loads(manifest_text)
d7 = manifest.get("debug_workbench_phase_d7_final_debug_seal") or {}

require("current overlay", manifest.get("current_overlay") == "xdm_android_debug_workbench_phase_d7_final_debug_seal_overlay.zip")
require("next phase complete", manifest.get("next_phase") == "complete")
require("d7 manifest block", bool(d7))
require("final phase complete", d7.get("final_phase_complete") is True)
require("d6 green baseline", d7.get("d6_tests_passed") == 414 and d7.get("d6_tests_failed") == 0)
require("diagnostics green", d7.get("diagnostic_warnings") == 0 and d7.get("diagnostic_errors") == 0)
require("no top level route", d7.get("top_level_route_added") is False)
require("no automatic upload", d7.get("automatic_upload") is False)
require("room schema unchanged", d7.get("room_schema_unchanged") == 14)
require("support bundle user shared", d7.get("support_bundle_user_shared_only") is True)
for key in [
    "debug_workbench_phase_d1_event_recorder",
    "debug_workbench_phase_d2_shell",
    "debug_workbench_phase_d3_media_sniffing_lab",
    "debug_workbench_phase_d4_browser_bridge_add_download_debugger",
    "debug_workbench_phase_d5_transfer_notification_debugger",
    "debug_workbench_phase_d6_runtime_self_test_suite",
]:
    require(f"manifest keeps {key}", key in manifest)

shell = read("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DebugWorkbenchShellModels.kt")
shell_test = read("core-model/src/test/kotlin/com/mikeyphw/xdm/android/model/DebugWorkbenchShellModelsTest.kt")
events = optional_read("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DebugEventModels.kt")
screen = read("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/debug/DebugWorkbenchSettingsScreen.kt")
contract = read("app/src/test/kotlin/com/mikeyphw/xdm/android/DebugWorkbenchD7FinalDebugSealContractTest.kt")
doc = read("docs/architecture/DEBUG-WORKBENCH-D7-FINAL-DEBUG-SEAL.md")
script = read("tools/run-phase-48-final-release-gate.sh")

require("area label helper", "fun DebugArea.supportLabel()" in shell)
require("state label helper", "fun DebugWorkbenchCheckState.displayLabel()" in shell)
require("clipboard uses area labels", "debugAreas.joinToString { it.supportLabel() }" in shell)
require("clipboard uses state labels", "check.state.displayLabel()" in shell)
require("clipboard avoids area enum names", "debugAreas.joinToString { it.name }" not in shell)
require("clipboard avoids check state enum names", "check.state.name" not in shell)
require("core test checks human labels", "clipboardReportUsesHumanLabelsForAreasAndCheckStates" in shell_test)
if events is None:
    require("contract checks support export redaction", "DebugRedactor.redactDetails(metadata)" in contract)
    require("contract checks support export local boundary", "No automatic upload" in contract)
else:
    require("support export redacts metadata", "DebugRedactor.redactDetails(metadata)" in events)
    require("support export is local only", "No automatic upload" in events)
require("debug screen uses display labels", "check.state.displayLabel()" in screen and "check.state.name" not in screen)
require("normal debug UI removed phase acronym copy", "D2 does not" not in screen)
require("contract test exists", "DebugWorkbenchD7FinalDebugSealContractTest" in contract)
require("doc records final seal", "D7 seals the Debug Workbench roadmap" in doc)
require("doc records support privacy", "Support bundles remain local and user-shared only" in doc)
require("doc records D6 baseline", "414 passed, 0 failed, 0 skipped" in doc)
require("release gate runs D7 validator", "validate-debug-workbench-d7-final-debug-seal.py" in script)
require("release gate no stale D1 validator", "validate-debug-workbench-d1-event-recorder.py" not in script)

render_re = re.compile(r"\b(?:Text|XdmSupportingText|XdmMetadataText|XdmMetricText)\s*\(")
for file in (ROOT / "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/debug").rglob("*.kt"):
    text = file.read_text(encoding="utf-8")
    require(f"{file.name} has no placeholder click", "onClick = {}" not in text)
    require(f"{file.name} does not start foreground service", "TransferForegroundService" not in text)
    require(f"{file.name} does not launch activities", "startActivity" not in text and "Intent(" not in text)
    for index, line in enumerate(text.splitlines(), 1):
        if render_re.search(line):
            require(f"{file.name}:{index} no raw enum rendering", ".name" not in line)
            require(f"{file.name}:{index} no raw URL rendering", ".url" not in line)
            require(f"{file.name}:{index} no machine JSON rendering", not any(token in line for token in ["rawJson", "JSONObject", "JSONArray"]))

print(f"Debug Workbench D7 final debug seal validator: passed, {len(checks)} checks")
