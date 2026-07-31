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

def text(relative: str) -> str:
    return (ROOT / relative).read_text()

def repo_text(relative: str) -> str:
    return (REPO / relative).read_text()

def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(message)

manifest = json.loads((ROOT / 'PROJECT_MANIFEST.json').read_text())
phase = manifest.get('field_bugfix_phase_58', {})
require(manifest.get('current_overlay') in {'xdm_android_phase58_runtime_recovery_execution_guard_r2_overlay.zip', 'xdm_android_phase59_runtime_recovery_action_transparency_overlay.zip', 'xdm_android_phase60_runtime_recovery_flow_seal_overlay.zip', 'xdm_android_phase61_final_gate_validator_harmony_overlay.zip', 'xdm_android_phase62_real_device_operational_smoke_seal_overlay.zip', 'xdm_android_phase63_release_readiness_support_bundle_seal_overlay.zip'}, 'current overlay must point to Phase58 or a later accepted field-fix overlay')
require(phase.get('room_schema_unchanged') == 14, 'Phase58 must keep Room schema 14')
require(phase.get('top_level_route_added') is False, 'Phase58 must not add top-level route')
require(phase.get('automatic_transfer_start') is False, 'Phase58 must not auto-start transfers')
require(phase.get('automatic_deletion') is False, 'Phase58 must not delete files automatically')
require(phase.get('all_files_permission_added') is False, 'Phase58 must not add all-files permission')
require(phase.get('debug_workbench_reopened') is False, 'Phase58 must not reopen Debug Workbench')

guard = text('core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/RuntimeRecoveryExecutionGuard.kt')
details = text('app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadDetails.kt')
guard_test = text('core-model/src/test/kotlin/com/mikeyphw/xdm/android/model/RuntimeRecoveryExecutionGuardTest.kt')
contract = text('app/src/test/kotlin/com/mikeyphw/xdm/android/Phase58RuntimeRecoveryExecutionGuardContractTest.kt')
doc = text('docs/architecture/PHASE-58-RUNTIME-RECOVERY-EXECUTION-GUARD.md')
android_manifest = text('app/src/main/AndroidManifest.xml')
final_gate = text('tools/run-final-release-gate.sh')
phase48_gate = text('tools/run-phase-48-final-release-gate.sh')
changelog = repo_text('CHANGELOG.md')

for needle in [
    'RuntimeRecoveryExecutionMode',
    'RuntimeRecoveryExecutionDecision',
    'RuntimeRecoveryExecutionGuard',
    'Review before retry',
    'Review captured session',
    'Guidance only',
    'Recovery review required',
    'does not start transfers, migrate methods, delete files',
]:
    require(needle in guard, f'missing guard marker: {needle}')

for needle in [
    'RuntimeRecoveryExecutionGuard.decide(download, action.kind)',
    'Action safety',
    'decision.allowsImmediateCallback',
    'RuntimeRecoveryExecutionMode.OpenRecoveryFirst',
    'showRuntimeRecoveryToast(context, decision.safetyNote)',
]:
    require(needle in details, f'missing UI guard marker: {needle}')

for forbidden in [
    'startTransfer',
    'enqueueTransfer',
    'MANAGE_EXTERNAL_STORAGE',
    'Cookie:',
    'Authorization:',
    'Bearer secret',
]:
    require(forbidden not in guard, f'guard must not contain unsafe marker: {forbidden}')

require('MANAGE_EXTERNAL_STORAGE' not in android_manifest, 'Android manifest must not request all-files permission')
require('partialFailuresOpenRecoveryBeforeRetryOrMethodSwitch' in guard_test, 'guard tests must cover partial retry review')
require('capturedSessionRetryIsAlwaysReviewFirst' in guard_test, 'guard tests must cover captured-session review')
require('guidanceActionsNeverStartBackgroundWork' in guard_test, 'guard tests must cover guidance-only actions')
require('recoveryActionsUseExecutionGuardBeforeCallbacks' in contract, 'contract must cover UI guard wiring')
require('does not auto-start retries' in doc, 'doc must record no auto-start boundary')
require('validate-phase58-runtime-recovery-execution-guard.py' in final_gate, 'final release gate must run Phase58 validator')
require('validate-phase58-runtime-recovery-execution-guard.py' in phase48_gate, 'phase48 gate must run Phase58 validator')
require('XDM Android Phase58' in changelog, 'changelog must document Phase58')

print('Phase 58 runtime recovery execution guard validator passed')
# later accepted overlay: xdm_android_phase60_runtime_recovery_flow_seal_overlay.zip
