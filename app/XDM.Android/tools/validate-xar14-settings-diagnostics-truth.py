#!/usr/bin/env python3
"""XAR14 settings/destination/diagnostics truth contract."""
from __future__ import annotations
import json, pathlib, re, sys
ROOT = pathlib.Path(__file__).resolve().parents[1]
CANONICAL_IDS = [
    "S14-01", "S14-02", "S14-03", "S14-04", "S14-05", "S14-06", "S14-07", "S14-08",
    "S14-09", "S14-10", "S14-11", "S14-12", "S14-13", "S14-14",
    "DS7-S14-01", "DS7-S14-02", "DS7-S14-03", "DS7-S14-04", "DS7-S14-05",
    "RERUN67-S14-01", "RERUN7R-S14-01",
]
files = {
    "models": ROOT / "core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DesktopParityModels.kt",
    "debug_events": ROOT / "core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DebugEventModels.kt",
    "integrity": ROOT / "core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DiagnosticExportIntegrity.kt",
    "store": ROOT / "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/debug/DebugTestStore.kt",
    "runner": ROOT / "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/debug/DebugTestRunner.kt",
    "catalog": ROOT / "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/debug/DebugTestCatalog.kt",
    "sharing": ROOT / "app/src/main/kotlin/com/mikeyphw/xdm/android/DebugCenterExportSharing.kt",
    "helpers": ROOT / "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/common/UiTextHelpers.kt",
    "settings_ui": ROOT / "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/settings/AdvancedDownloadSettingsScreen.kt",
    "developer_ui": ROOT / "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/developer/DeveloperToolsWorkspace.kt",
    "vm": ROOT / "app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt",
    "repo": ROOT / "persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/DownloadRepository.kt",
    "tests": ROOT / "core-model/src/test/kotlin/com/mikeyphw/xdm/android/model/DesktopParityModelsTest.kt",
    "xar_tests": ROOT / "app/src/test/kotlin/com/mikeyphw/xdm/android/Xar14SettingsDiagnosticsTruthContractTest.kt",
    "gradle": ROOT / "app/build.gradle.kts",
    "gate": ROOT / "tools/run-final-release-gate.sh",
    "manifest": ROOT / "PROJECT_MANIFEST.json",
    "doc": ROOT / "docs/remediation/XAR14-SETTINGS-DIAGNOSTICS-TRUTH.md",
    "report": ROOT / "XDM_ANDROID_XAR14_SETTINGS_DIAGNOSTICS_TRUTH_REPORT.md",
}
missing = [str(p.relative_to(ROOT)) for p in files.values() if not p.exists()]
if missing:
    raise SystemExit("Missing XAR14 files: " + ", ".join(missing))
text = {k: p.read_text() for k,p in files.items()}
checks=[]
has=lambda k,n: n in text[k]
rx=lambda k,p: re.search(p,text[k],re.S) is not None
checks += [
    ("settings portable copy drops grants", has("models", "portableCopy()") and has("models", "isDeviceBoundDestinationUri") and has("models", "proxy = proxy.copy(credentialAlias = \"\")")),
    ("settings field delimiter escaped", has("models", "'|' -> append(\"\\\\p\")") and has("models", "splitEscapedSettingFields")),
    ("settings decode result visible", has("models", "SettingsExchangeImportResult") and has("vm", "settingsImportResult") and has("settings_ui", "state.settingsImportResult.summary")),
    ("settings invalid import non destructive", has("vm", "val result = SettingsExchangeCodec.decodeResult(text)") and has("settings_ui", "viewModel.importSettingsSnapshot(importText)\n                        }") and has("models", "malformed or invalid fields")),
    ("import atomic and ignores external ids", has("repo", "importOrganizationSettingsAtomically") and has("models", "id = \"ignored-import-id\"") and has("models", "stableSettingsExchangeId")),
    ("exact host rules are exact", has("models", "hostMatchesDestinationRule") and has("models", "normalizedHost == normalizedPattern") and has("models", "normalizedHost.endsWith(\".$domain\")") and has("tests", "https://sub.example.com")),
    ("debug runner has per test timeout", has("runner", "withTimeout(PerTestDeadlineMs)") and has("runner", "debug-test-timeout")),
    ("debug store atomic/quarantine", has("store", ".json.tmp") and has("store", "quarantineCorruptRun")),
    ("debug exports bounded", has("store", "pruneOldExports") and has("store", "retainedRuns")),
    ("historical run live context separated", has("store", "Historical diagnostics export") and has("store", "currentRunId == run.id")),
    ("room schema coherent", has("store", "roomSchemaVersion = 25") and has("store", "Room schema: 25") and has("developer_ui", "Room schema: 25")),
    ("redaction catches path secrets", has("debug_events", "redactPathSegments") and has("integrity", "pathCredentialPattern") and has("integrity", "longPathSecretPattern")),
    ("final zip scanner gates sharing", has("sharing", "DiagnosticExportIntegrity.scanZip(zip)") and has("sharing", "if (!scan.safe)")),
    ("copy/share support reports redacted", has("helpers", "label.contains(\"support report\"") and has("helpers", "DebugRedactor.redactExportLine")),
    ("safe checks nonschedulable", has("catalog", "temporary non-schedulable cancelled Download probe row") and has("catalog", "state = DownloadState.Cancelled")),
    ("private storage probe cleaned", has("catalog", "finally") and has("catalog", "probe.delete()")),
    ("capability health not hardcoded", has("vm", "debugEventRecorder !is NoOpDebugEventRecorder") and has("vm", "diagnosticsRuntimePrivacyReady") and "recorderInstalled = true" not in text["vm"][text["vm"].find("supportReportText = supportReportText"):text["vm"].find("destinationUri = prefs.destinationUri")]),
    ("gradle task registered", has("gradle", "verifyXar14SettingsDiagnosticsTruth")),
    ("final gate runs validator", has("gate", "tools/validate-xar14-settings-diagnostics-truth.py")),
    ("doc covers ids", all(cid in text["doc"] for cid in CANONICAL_IDS)),
    ("report covers ids", all(cid in text["report"] for cid in CANONICAL_IDS)),
]
manifest=json.loads(text["manifest"])
entry=manifest.get("xar14_settings_diagnostics_truth")
checks += [
    ("manifest entry", bool(entry)),
    ("manifest overlay position", entry and entry.get("roadmap_position")=="14 of 17"),
    ("manifest closes 21", entry and entry.get("canonical_findings_closed")==21 and entry.get("canonical_ids")==CANONICAL_IDS),
    ("roadmap current", manifest.get("xar_roadmap",{}).get("current_overlay")=="XAR14"),
    ("archive inventory updated", "xdm_android_xar14_settings_diagnostics_truth_v1.tar.gz" in manifest.get("xar_applied_overlay_archives", [])),
]
failed=[label for label,ok in checks if not ok]
if failed:
    print("XAR14 validation failed:", file=sys.stderr)
    for f in failed: print(" - "+f, file=sys.stderr)
    raise SystemExit(1)
print(f"XAR14 settings/diagnostics truth contract passed: {len(checks)} checks; {len(CANONICAL_IDS)}/21 S14 findings covered.")
