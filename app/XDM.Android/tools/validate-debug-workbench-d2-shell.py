#!/usr/bin/env python3
from pathlib import Path
import json
import sys

ROOT = Path(__file__).resolve().parents[1]
checks = []

def require(path, needle, label):
    text = (ROOT / path).read_text()
    if needle not in text:
        raise SystemExit(f"missing {label}: {needle} in {path}")
    checks.append(label)

def forbid(path, needle, label):
    text = (ROOT / path).read_text()
    if needle in text:
        raise SystemExit(f"forbidden {label}: {needle} in {path}")
    checks.append(label)

manifest = json.loads((ROOT / "PROJECT_MANIFEST.json").read_text())
if manifest.get("next_phase") != "debug_workbench_phase_d3_media_sniffing_lab":
    raise SystemExit("next_phase must hand off to debug_workbench_phase_d3_media_sniffing_lab")
if "debug_workbench_phase_d2_shell" not in manifest:
    raise SystemExit("manifest missing debug_workbench_phase_d2_shell")
checks.append("manifest d2 record")

require("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DebugWorkbenchShellModels.kt", "object DebugWorkbenchShellPolicy", "shell policy")
require("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DebugWorkbenchShellModels.kt", "fun toClipboardReport()", "clipboard report")
require("app/src/main/kotlin/com/mikeyphw/xdm/android/SettingsPanel.kt", "DebugWorkbench(\"Debug Workbench\")", "settings panel")
require("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/settings/SettingsScreen.kt", "SettingsPanel.DebugWorkbench -> DebugWorkbenchSettingsScreen(state, viewModel)", "settings route")
require("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/settings/SettingsScreen.kt", "viewModel.selectSettingsPanel(SettingsPanel.DebugWorkbench)", "settings entry")
require("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/debug/DebugWorkbenchSettingsScreen.kt", "SettingsPageHeader(\"Debug Workbench\"", "debug page header")
require("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/debug/DebugWorkbenchSettingsScreen.kt", "Copy debug status", "copy debug status")
require("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/debug/DebugWorkbenchSettingsScreen.kt", "Copy support report", "copy support report")
require("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/debug/DebugWorkbenchSettingsScreen.kt", "Runtime self-checks", "self checks")
forbid("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/debug/DebugWorkbenchSettingsScreen.kt", "onClick = {}", "placeholder clicks")
forbid("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/debug/DebugWorkbenchSettingsScreen.kt", "ui.common.copyTextToClipboard", "wrong clipboard import")
forbid("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/debug/DebugWorkbenchSettingsScreen.kt", "XdmStatusTone.Danger", "invalid status tone")
forbid("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/debug/DebugWorkbenchSettingsScreen.kt", "modifier = Modifier.fillMaxWidth(),\n                    modifier = Modifier.fillMaxWidth(),", "duplicate row modifier")
require("app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApplication.kt", "DebugRecorderProvider", "provider")
require("app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApplication.kt", "RollingJsonlDebugEventRecorder", "rolling recorder")
require("app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt", "debugEventRecorder: DebugEventRecorder", "viewmodel recorder arg")
require("app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt", "debugWorkbenchReport = DebugWorkbenchShellPolicy.evaluate", "ui report")
require("app/src/test/kotlin/com/mikeyphw/xdm/android/DebugWorkbenchD2ShellContractTest.kt", "settingsRoutesToDebugWorkbenchShellWithoutTopLevelRoute", "app contract")
require("core-model/src/test/kotlin/com/mikeyphw/xdm/android/model/DebugWorkbenchShellModelsTest.kt", "reportSummarizesRecorderRedactionAndSupportBundleReadiness", "model test")

require("app/src/test/kotlin/com/mikeyphw/xdm/android/DebugWorkbenchD2ShellContractTest.kt", '"""DebugWorkbench("Debug Workbench")"""', "raw DebugWorkbench panel contract")
require("app/src/test/kotlin/com/mikeyphw/xdm/android/DebugWorkbenchD2ShellContractTest.kt", '"""title = "Debug Workbench""""', "raw title contract")
require("app/src/test/kotlin/com/mikeyphw/xdm/android/DebugWorkbenchD2ShellContractTest.kt", '"""File(filesDir, "debug-sessions")"""', "raw recorder directory contract")
require("app/src/test/kotlin/com/mikeyphw/xdm/android/BrowserBridgePhase48FinalReleaseGateContractTest.kt", 'Regex(""""next_phase"', "raw next_phase regex contract")
require("app/src/test/kotlin/com/mikeyphw/xdm/android/MediaSniffingPhase47ContractTest.kt", "usesRecorderBackedSharedSniffer", "phase47 recorder-backed sniffer assertion")
require("app/src/test/kotlin/com/mikeyphw/xdm/android/MediaSniffingPhase47ContractTest.kt", "debugRecorder = debugEventRecorder", "phase47 debug recorder assertion")
require("app/src/test/kotlin/com/mikeyphw/xdm/android/MediaSniffingPhase47ContractTest.kt", "usesOriginalSharedSniffer || usesRecorderBackedSharedSniffer", "phase47 compatibility assertion")
require("app/src/test/kotlin/com/mikeyphw/xdm/android/DebugWorkbenchD2ShellContractTest.kt", "phase47SharedSnifferContractAcceptsRecorderBackedConstruction", "d2 phase47 assertion contract")
require("PROJECT_MANIFEST.json", "contract_assertion_repair", "manifest assertion repair")
require("docs/architecture/DEBUG-WORKBENCH-D2-SHELL.md", "Settings → Debug Workbench", "doc")
require("docs/architecture/DEBUG-WORKBENCH-D2-SHELL.md", "r4 assertion compatibility repair", "doc assertion repair")
print(f"Debug Workbench D2 shell validator passed ({len(checks)} checks)")
