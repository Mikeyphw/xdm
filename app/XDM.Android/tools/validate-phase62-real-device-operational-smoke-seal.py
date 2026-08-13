#!/usr/bin/env python3
from __future__ import annotations

import json
import sys
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
OVERLAY = 'xdm_android_phase62_real_device_operational_smoke_seal_overlay.zip'
LATER_OVERLAYS = {'xdm_android_foundation_gate_repair_overlay.zip', 'xdm_android_phase63_release_readiness_support_bundle_seal_r2_overlay.zip', 'xdm_android_phase64_final_android_downloader_rc_seal_r2_overlay.zip', 'xdm_android_phase65_diagnostic_export_download_action_fix_overlay.zip'}
ERRORS: list[str] = []


def text(relative: str) -> str:
    path = ROOT / relative
    if not path.is_file():
        ERRORS.append(f'Missing required file: {relative}')
        return ''
    return path.read_text(encoding='utf-8')


def repo_text(relative: str) -> str:
    path = REPO / relative
    if not path.is_file():
        ERRORS.append(f'Missing required file: {relative}')
        return ''
    return path.read_text(encoding='utf-8')


def require(condition: bool, message: str) -> None:
    if not condition:
        ERRORS.append(message)

manifest = json.loads(text('PROJECT_MANIFEST.json') or '{}')
phase = manifest.get('field_bugfix_phase_62', {})
seal = text('core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/RealDeviceOperationalSmokeSeal.kt')
seal_test = text('core-model/src/test/kotlin/com/mikeyphw/xdm/android/model/RealDeviceOperationalSmokeSealPlannerTest.kt')
contract = text('app/src/test/kotlin/com/mikeyphw/xdm/android/Phase62RealDeviceOperationalSmokeSealContractTest.kt')
doc = text('docs/architecture/PHASE-62-REAL-DEVICE-OPERATIONAL-SMOKE-SEAL.md')
final_gate = text('tools/run-final-release-gate.sh')
phase48_gate = text('tools/run-phase-48-final-release-gate.sh')
android_manifest = text('app/src/main/AndroidManifest.xml')
changelog = repo_text('CHANGELOG.md')

require(manifest.get('current_overlay') in ({OVERLAY} | LATER_OVERLAYS), 'current overlay must point to Phase62')
require(62 in manifest.get('project', {}).get('implemented_phases', []), 'implemented phases must include 62')
require(manifest.get('next_phase') in {'complete', 'phase63_release_readiness_support_bundle_seal', 'phase64_final_android_downloader_rc_seal', 'phase11_validation_matrix'}, 'next phase should mark completion or point to Phase63 release readiness/support bundle seal')
require(phase.get('status') == 'implemented', 'Phase62 must be implemented')
require(phase.get('room_schema_unchanged') == 14, 'Phase62 must keep Room schema 14')
require(phase.get('top_level_route_added') is False, 'Phase62 must not add a top-level route')
require(phase.get('automatic_transfer_start') is False, 'Phase62 must not start transfers automatically')
require(phase.get('automatic_deletion') is False, 'Phase62 must not delete files automatically')
require(phase.get('automatic_upload') is False, 'Phase62 must not upload automatically')
require(phase.get('all_files_permission_added') is False, 'Phase62 must not add all-files permission')
require(phase.get('debug_workbench_reopened') is False, 'Phase62 must not reopen Debug Workbench')
require(phase.get('requires_real_device_manual_run') is True, 'Phase62 must record that manual real-device smoke is required')

for needle in [
    'RealDeviceOperationalSmokeSealPlanner',
    'ExternalBrowserHandoff',
    'ExtensionMediaCapture',
    'AuthenticatedFailureRecovery',
    'CompletedStorageVisibility',
    'RecoveryDoctorReview',
    'manual device run required',
    'Private values: full links, cookies, authorization values, bearer tokens, signatures, and credential query values are redacted.',
]:
    require(needle in seal, f'missing smoke seal marker: {needle}')

for forbidden in [
    'startTransfer',
    'enqueueTransfer',
    'delete()',
    'MANAGE_EXTERNAL_STORAGE',
    'Cookie:',
    'Authorization:',
    'Bearer secret',
    'https://example.test',
]:
    require(forbidden not in seal, f'smoke seal model must not contain unsafe marker: {forbidden}')

for needle in [
    'smokeSealRequiresManualDeviceRunBeforeRcCandidate',
    'capturedSmokeRunCanSealRcCandidate',
    'assertFalse(seal.readyForRcCandidate)',
    'assertTrue(seal.readyForRcCandidate)',
]:
    require(needle in seal_test, f'missing smoke seal test marker: {needle}')

for needle in [
    'Phase62RealDeviceOperationalSmokeSealContractTest',
    'validate-phase62-real-device-operational-smoke-seal.py',
    'xdm_android_phase62_real_device_operational_smoke_seal_overlay.zip',
]:
    require(needle in contract, f'missing contract marker: {needle}')

for flow in phase.get('smoke_flows', []):
    require(flow in {
        'external_browser_handoff_review',
        'extension_media_capture_reviewed_add',
        'authenticated_failure_recovery',
        'completed_storage_visibility',
        'recovery_doctor_partial_orphan_review',
    }, f'unexpected smoke flow: {flow}')
require(len(phase.get('smoke_flows', [])) == 5, 'Phase62 must record five smoke flows')
require(phase.get('privacy', {}).get('redacted_reports_only') is True, 'Phase62 reports must be redacted')
require(phase.get('privacy', {}).get('persists_session_values') is False, 'Phase62 must not persist session values')

for prior in range(54, 62):
    name = {
        54: 'engine-escalation-planner',
        55: 'final-release-warning-explainer',
        56: 'stale-copy-architecture-noise-sweep',
        57: 'runtime-failure-recovery-ux',
        58: 'runtime-recovery-execution-guard',
        59: 'runtime-recovery-action-transparency',
        60: 'runtime-recovery-flow-seal',
        61: 'final-gate-validator-harmony',
    }[prior]
    validator = text(f'tools/validate-phase{prior}-{name}.py')
    require(OVERLAY in validator, f'Phase{prior} validator must tolerate Phase62 as a later overlay')

require('validate-phase62-real-device-operational-smoke-seal.py' in final_gate, 'final release gate must run Phase62 validator')
require('validate-phase62-real-device-operational-smoke-seal.py' in phase48_gate, 'Phase48 gate must run Phase62 validator')
require('MANAGE_EXTERNAL_STORAGE' in android_manifest, 'Runtime Foundation personal build must declare all-files permission')
require('Real-device Operational Smoke Seal' in changelog, 'changelog must describe Phase62')
require('does not start transfers, delete files, request all-files storage, persist browser session values, or reopen Debug Workbench' in doc, 'Phase62 doc must record safety boundary')

if ERRORS:
    print('Phase62 real-device operational smoke seal validation failed:')
    for error in ERRORS:
        print(f'- {error}')
    sys.exit(1)
print('Phase62 real-device operational smoke seal validator passed')
