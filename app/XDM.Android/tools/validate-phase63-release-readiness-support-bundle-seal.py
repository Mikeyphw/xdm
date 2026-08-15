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
OVERLAY = 'xdm_android_phase63_release_readiness_support_bundle_seal_r2_overlay.zip'
LATER_OVERLAYS = {'xdm_android_privacy_quality_final_gate_overlay_v2.zip', 'xdm_android_foundation_gate_repair_overlay.zip', 'xdm_android_bug_hunt_phase10_release_upgrade_packaging_publication_full_overlay.zip', 'xdm_android_phase64_final_android_downloader_rc_seal_r2_overlay.zip', 'xdm_android_phase65_diagnostic_export_download_action_fix_overlay.zip'}
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
phase = manifest.get('field_bugfix_phase_63', {})
seal = text('core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/SupportBundleReleaseReadinessSeal.kt')
seal_test = text('core-model/src/test/kotlin/com/mikeyphw/xdm/android/model/SupportBundleReleaseReadinessPlannerTest.kt')
contract = text('app/src/test/kotlin/com/mikeyphw/xdm/android/Phase63ReleaseReadinessSupportBundleSealContractTest.kt')
main_vm = text('app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt')
doc = text('docs/architecture/PHASE-63-RELEASE-READINESS-SUPPORT-BUNDLE-SEAL.md')
final_gate = text('tools/run-final-release-gate.sh')
phase48_gate = text('tools/run-phase-48-final-release-gate.sh')
android_manifest = text('app/src/main/AndroidManifest.xml')
changelog = repo_text('CHANGELOG.md')

require(manifest.get('current_overlay') in ({OVERLAY} | LATER_OVERLAYS), 'current overlay must point to Phase63 or the final RC overlay')
require(63 in manifest.get('project', {}).get('implemented_phases', []), 'implemented phases must include 63')
require(manifest.get('next_phase') in {'phase64_final_android_downloader_rc_seal', 'complete', 'phase11_validation_matrix'}, 'next phase should point to Phase64 or complete after final RC seal')
require(phase.get('status') == 'implemented', 'Phase63 must be implemented')
require(phase.get('room_schema_unchanged') == 14, 'Phase63 must keep Room schema 14')
require(phase.get('top_level_route_added') is False, 'Phase63 must not add a top-level route')
require(phase.get('automatic_transfer_start') is False, 'Phase63 must not start transfers automatically')
require(phase.get('automatic_deletion') is False, 'Phase63 must not delete files automatically')
require(phase.get('automatic_upload') is False, 'Phase63 must not upload automatically')
require(phase.get('all_files_permission_added') is False, 'Phase63 must not add all-files permission')
require(phase.get('debug_workbench_reopened') is False, 'Phase63 must not reopen Debug Workbench')
require(phase.get('support_bundle_sealed') is True, 'Phase63 must seal support bundle readiness')

expected_sections = {
    'operational_diagnostics_summary',
    'release_security_summary',
    'install_update_readiness',
    'final_release_warning_explainer',
    'real_device_smoke_status',
    'privacy_redaction_guarantees',
}
require(set(phase.get('support_bundle_sections', [])) == expected_sections, 'Phase63 support bundle sections must match the sealed release-readiness set')

for needle in [
    'SupportBundleReleaseReadinessPlanner',
    'Operational diagnostics summary',
    'Release-security status',
    'Install/update readiness',
    'Final-release warning explanations',
    'Real-device smoke status',
    'Privacy redaction boundary',
    'Copy-only support handoff',
    'Private values: full links, raw headers, cookies, authorization values, bearer tokens, signatures, and credential query values are redacted.',
]:
    require(needle in seal, f'missing support-bundle seal marker: {needle}')

for forbidden in [
    'https://example.test',
    'Cookie:',
    'Authorization:',
    'Bearer secret',
    'MANAGE_EXTERNAL_STORAGE',
    'startTransfer',
    'enqueueTransfer',
    'delete()',
    'upload(',
]:
    require(forbidden not in seal, f'support-bundle seal model must not contain unsafe marker: {forbidden}')

for needle in [
    'supportBundleSealIsReadyWhenAllReleaseSectionsAreRedactedAndPresent',
    'supportBundleSealBlocksWhenWarningsAreBareOrSessionValuesWouldPersist',
    'assertFalse(seal.readyForSupportHandoff)',
    'assertTrue(seal.readyForSupportHandoff)',
]:
    require(needle in seal_test, f'missing support-bundle test marker: {needle}')

for needle in [
    'SupportBundleReleaseReadinessPlanner.evaluate',
    'supportBundleSeal.redactedSummary()',
    'finalReleaseGateReport.redactedExplanationSummary()',
    'PrivacyDiagnosticsRedactor.redactedHealthSummary',
    'installUpdateReadinessReport.redactedSummary()',
]:
    require(needle in main_vm, f'MainViewModel must include support report marker: {needle}')

for needle in [
    'Phase63ReleaseReadinessSupportBundleSealContractTest',
    'validate-phase63-release-readiness-support-bundle-seal.py',
    OVERLAY,
]:
    require(needle in contract, f'missing Phase63 contract marker: {needle}')

for prior in range(54, 63):
    name = {
        54: 'engine-escalation-planner',
        55: 'final-release-warning-explainer',
        56: 'stale-copy-architecture-noise-sweep',
        57: 'runtime-failure-recovery-ux',
        58: 'runtime-recovery-execution-guard',
        59: 'runtime-recovery-action-transparency',
        60: 'runtime-recovery-flow-seal',
        61: 'final-gate-validator-harmony',
        62: 'real-device-operational-smoke-seal',
    }[prior]
    validator = text(f'tools/validate-phase{prior}-{name}.py')
    require(OVERLAY in validator, f'Phase{prior} validator must tolerate Phase63 as a later overlay')

require('validate-phase63-release-readiness-support-bundle-seal.py' in final_gate, 'final release gate must run Phase63 validator')
require('validate-phase63-release-readiness-support-bundle-seal.py' in phase48_gate, 'Phase48 gate must run Phase63 validator')
require('MANAGE_EXTERNAL_STORAGE' in android_manifest, 'Runtime Foundation personal build must declare all-files permission')
require('Release Readiness / Support Bundle Seal' in changelog, 'changelog must describe Phase63')
require('does not upload, start transfers, delete files, add storage permissions, persist session values, or reopen Debug Workbench' in doc, 'Phase63 doc must record safety boundary')

if ERRORS:
    print('Phase63 release readiness support bundle seal validation failed:')
    for error in ERRORS:
        print(f'- {error}')
    sys.exit(1)
print('Phase63 release readiness support bundle seal validator passed')
