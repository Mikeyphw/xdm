#!/usr/bin/env python3
import json
from pathlib import Path


def find_root() -> Path:
    cursor = Path.cwd().resolve()
    for _ in range(8):
        if (cursor / 'settings.gradle.kts').is_file() and (cursor / 'app/src/main').is_dir():
            return cursor
        candidate = cursor / 'app' / 'XDM.Android'
        if (candidate / 'settings.gradle.kts').is_file() and (candidate / 'app/src/main').is_dir():
            return candidate
        if cursor.parent == cursor:
            break
        cursor = cursor.parent
    raise SystemExit('Android root not found')

ROOT = find_root()
REPO = ROOT.parent.parent
OVERLAY = 'xdm_android_phase60_runtime_recovery_flow_seal_overlay.zip'
LATER_OVERLAYS = {'xdm_android_phase61_final_gate_validator_harmony_overlay.zip', 'xdm_android_phase62_real_device_operational_smoke_seal_overlay.zip', 'xdm_android_phase63_release_readiness_support_bundle_seal_r2_overlay.zip', 'xdm_android_phase64_final_android_downloader_rc_seal_r2_overlay.zip', 'xdm_android_phase65_diagnostic_export_download_action_fix_overlay.zip'}


def text(relative: str) -> str:
    return (ROOT / relative).read_text()


def repo_text(relative: str) -> str:
    return (REPO / relative).read_text()


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(message)

manifest = json.loads((ROOT / 'PROJECT_MANIFEST.json').read_text())
phase = manifest.get('field_bugfix_phase_60', {})
require(manifest.get('current_overlay') in ({OVERLAY} | LATER_OVERLAYS), 'current overlay must point to Phase60 or a later accepted field-fix overlay')
require(60 in manifest.get('project', {}).get('implemented_phases', []), 'Phase60 must be recorded as implemented')
require(phase.get('status') == 'implemented', 'Phase60 must be implemented')
require(phase.get('room_schema_unchanged') == 14, 'Phase60 must keep Room schema 14')
require(phase.get('top_level_route_added') is False, 'Phase60 must not add a top-level route')
require(phase.get('automatic_transfer_start') is False, 'Phase60 must not auto-start transfers')
require(phase.get('automatic_deletion') is False, 'Phase60 must not delete files automatically')
require(phase.get('automatic_upload') is False, 'Phase60 must not upload automatically')
require(phase.get('all_files_permission_added') is False, 'Phase60 must not add all-files permission')
require(phase.get('debug_workbench_reopened') is False, 'Phase60 must not reopen Debug Workbench')
require(phase.get('privacy', {}).get('persists_session_values') is False, 'Phase60 must not persist session values')

seal = text('core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/RuntimeRecoveryFlowSeal.kt')
seal_test = text('core-model/src/test/kotlin/com/mikeyphw/xdm/android/model/RuntimeRecoveryFlowSealPlannerTest.kt')
contract = text('app/src/test/kotlin/com/mikeyphw/xdm/android/Phase60RuntimeRecoveryFlowSealContractTest.kt')
details = text('app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadDetails.kt')
recovery_model = text('core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/RuntimeFailureRecovery.kt')
android_manifest = text('app/src/main/AndroidManifest.xml')
doc = text('docs/architecture/PHASE-60-RUNTIME-RECOVERY-FLOW-SEAL.md')
final_gate = text('tools/run-final-release-gate.sh')
phase48_gate = text('tools/run-phase-48-final-release-gate.sh')
phase59_validator = text('tools/validate-phase59-runtime-recovery-action-transparency.py')
changelog = repo_text('CHANGELOG.md')

for needle in [
    'RuntimeRecoveryFlowSealPlanner',
    'RuntimeFailureRecoveryPlanner.evaluate',
    'RuntimeRecoveryExecutionGuard.decide',
    'RuntimeRecoveryActionPreviewPlanner.build',
    'redactedReportSection',
    'Manual only',
]:
    require(needle in seal, f'missing seal marker: {needle}')

for needle in [
    'failedDownloadSealCombinesPlannerGuardPreviewAndRedactedReport',
    'healthyDownloadSealDoesNotOfferRecoveryWork',
]:
    require(needle in seal_test, f'missing seal test: {needle}')

require('Recovery options' in recovery_model, 'Phase57 recovery plan title missing')
for needle in ['Action safety', 'Action preview', 'What happens']:
    require(needle in details, f'Phase58-59 recovery card marker missing: {needle}')

for forbidden in [
    'startTransfer',
    'enqueueTransfer',
    'MANAGE_EXTERNAL_STORAGE',
    'delete()',
    'Cookie:',
    'Authorization:',
    'Bearer secret',
]:
    require(forbidden not in seal, f'seal model must not contain unsafe marker: {forbidden}')

for prior in [54, 55, 56, 57, 58, 59]:
    validator = text(f'tools/validate-phase{prior}-' + {
        54: 'engine-escalation-planner.py',
        55: 'final-release-warning-explainer.py',
        56: 'stale-copy-architecture-noise-sweep.py',
        57: 'runtime-failure-recovery-ux.py',
        58: 'runtime-recovery-execution-guard.py',
        59: 'runtime-recovery-action-transparency.py',
    }[prior])
    require(OVERLAY in validator, f'Phase{prior} validator must tolerate Phase60 as a later overlay')

require('MANAGE_EXTERNAL_STORAGE' in android_manifest, 'Runtime Foundation personal build must declare all-files permission')
require('validate-phase60-runtime-recovery-flow-seal.py' in final_gate, 'final release gate must run Phase60 validator')
require('validate-phase60-runtime-recovery-flow-seal.py' in phase48_gate, 'Phase48 gate must run Phase60 validator')
require(OVERLAY in phase59_validator, 'Phase59 validator must accept Phase60 as later overlay')
require('does not start transfers, delete files, or persist browser session values' in doc, 'doc must record safety boundary')
require('XDM Android Phase60' in changelog, 'changelog must document Phase60')
require('runtimeRecoveryFlowSealConnectsPlannerGuardPreviewAndReport' in contract, 'contract must cover seal wiring')

print('Phase 60 runtime recovery flow seal validator passed')
