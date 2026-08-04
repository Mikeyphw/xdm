#!/usr/bin/env python3
from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ERRORS: list[str] = []
OVERLAY = 'xdm_android_phase61_final_gate_validator_harmony_overlay.zip'
LATER_OVERLAYS = {'xdm_android_bug_hunt_phase11_validation_matrix_full_overlay.zip', 'xdm_android_bug_hunt_phase10_release_upgrade_packaging_publication_full_r2_gap_closure_overlay.zip', 'xdm_android_bug_hunt_phase10_release_upgrade_packaging_publication_full_overlay.zip', 'xdm_android_phase62_real_device_operational_smoke_seal_overlay.zip', 'xdm_android_phase63_release_readiness_support_bundle_seal_r2_overlay.zip', 'xdm_android_phase64_final_android_downloader_rc_seal_r2_overlay.zip', 'xdm_android_phase65_diagnostic_export_download_action_fix_overlay.zip'}

def read(relative: str) -> str:
    path = ROOT / relative
    if not path.is_file():
        ERRORS.append(f"Missing required file: {relative}")
        return ""
    return path.read_text(encoding="utf-8")

def require(condition: bool, message: str) -> None:
    if not condition:
        ERRORS.append(message)

manifest_text = read("PROJECT_MANIFEST.json")
manifest = json.loads(manifest_text or "{}")
phase = manifest.get("field_bugfix_phase_61", {})

uix_r3 = read("tools/validate-uix-r3-downloads-add-workspace.py")
phase44 = read("tools/validate-phase-44-download-list-actions.py")
row = read("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadRow.kt")
contract = read("app/src/test/kotlin/com/mikeyphw/xdm/android/Phase61FinalGateValidatorHarmonyContractTest.kt")
doc = read("docs/architecture/PHASE-61-FINAL-GATE-VALIDATOR-HARMONY.md")
final_gate = read("tools/run-final-release-gate.sh")
phase48_gate = read("tools/run-phase-48-final-release-gate.sh")
phase60_validator = read("tools/validate-phase60-runtime-recovery-flow-seal.py")
phase34_validator = read("tools/validate-phase-34-release-handoff.py")
phase35_validator = read("tools/validate-phase-35-release-candidate-polish.py")
browser_removal_validator = read("tools/validate-browser-removal-phase-0-1.py")
phase5_validator = read("tools/validate-browser-removal-phase-5.py")
downloader_8e_validator = read("tools/validate-downloader-experience-phase-8e.py")
android_manifest = read("app/src/main/AndroidManifest.xml")

require(manifest.get("current_overlay") in ({OVERLAY} | LATER_OVERLAYS), "current overlay must point to Phase61 or a later accepted overlay")
require(61 in manifest.get("project", {}).get("implemented_phases", []), "implemented phases must include 61")
require(manifest.get("next_phase") in {"complete", "phase63_release_readiness_support_bundle_seal", 'phase64_final_android_downloader_rc_seal', 'phase11_validation_matrix'}, "next phase should mark the field-fix arc complete or point to Phase63 support-bundle seal")
require(phase.get("room_schema_unchanged") == 14 or manifest.get('database', {}).get('version', 0) >= 17, "Phase61 validator harmony must survive current schema 17 or newer")
require(phase.get("top_level_route_added") is False, "Phase61 must not add a top-level route")
require(phase.get("automatic_transfer_start") is False, "Phase61 must not start transfers automatically")
require(phase.get("automatic_deletion") is False, "Phase61 must not delete files automatically")
require(phase.get("all_files_permission_added") is False, "Phase61 must not add all-files permission")
require(phase.get("debug_workbench_reopened") is False, "Phase61 must not reopen Debug Workbench")
require(phase.get("validator_harmony", {}).get("uix_r3_primary_action_assertion_updated") is True, "Phase61 manifest must record UIX R3 assertion update")

require('"primaryRowAction"' not in uix_r3 and "'primaryRowAction'" not in uix_r3, "UIX R3 validator must not require retired primaryRowAction")
require("DownloadActionPlanner.primaryActionFor(download, actionContext)" in uix_r3, "UIX R3 validator must require planner-backed primary action")
require("DownloadAction.iconVector()" in uix_r3, "UIX R3 validator must keep the row action icon contract")
require("private fun Download.primaryRowAction" in phase44 and "old row-local action planner" in phase44, "Phase44 validator must keep forbidding row-local primaryAction")
require("DownloadActionPlanner.primaryActionFor(download, actionContext)" in row, "DownloadRow must keep using DownloadActionPlanner.primaryActionFor")
require("private fun Download.primaryRowAction" not in row, "DownloadRow must not revive private primaryRowAction")
require("private data class DownloadRowAction" not in row, "DownloadRow must not revive row-local action data")

require("validate-phase61-final-gate-validator-harmony.py" in final_gate, "final release gate must run Phase61 validator")
require("validate-phase61-final-gate-validator-harmony.py" in phase48_gate, "Phase48 gate must run Phase61 validator")
require(OVERLAY in phase60_validator, "Phase60 validator must tolerate Phase61 as a later accepted overlay")
require(OVERLAY in phase34_validator, "Phase34 validator must tolerate Phase61 as a later accepted overlay")
require("hasBrowserRemovalLineage" in phase35_validator, "Phase35 validator must accept the current architecture lineage helper")
require(OVERLAY in browser_removal_validator, "Browser-removal validators must tolerate Phase61 as a later accepted overlay")
require(OVERLAY in phase5_validator, "Browser-removal Phase5 validator must tolerate Phase61 correction markers")
require(OVERLAY in downloader_8e_validator, "Downloader-experience validators must tolerate Phase61 as a later accepted overlay")
require("Phase61FinalGateValidatorHarmonyContractTest" in contract, "Phase61 contract test must exist")
require("UIX R3" in doc and "Phase44" in doc and "primaryRowAction" in doc, "Phase61 doc must explain the stale validator harmony")
require("MANAGE_EXTERNAL_STORAGE" not in android_manifest, "Phase61 must not add all-files storage permission")

if ERRORS:
    print("Phase61 final gate validator harmony validation failed:")
    for error in ERRORS:
        print(f"- {error}")
    sys.exit(1)
print("Phase61 final gate validator harmony validation passed")
