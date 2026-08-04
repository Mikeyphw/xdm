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
OVERLAY = 'xdm_android_phase64_final_android_downloader_rc_seal_r2_overlay.zip'
ACCEPTED_CURRENT_OVERLAYS = {OVERLAY, 'xdm_android_phase65_diagnostic_export_download_action_fix_overlay.zip', 'xdm_android_bug_hunt_phase10_release_upgrade_packaging_publication_full_overlay.zip'}
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
phase = manifest.get('field_bugfix_phase_64', {})
seal = text('core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/FinalAndroidDownloaderRcSeal.kt')
seal_test = text('core-model/src/test/kotlin/com/mikeyphw/xdm/android/model/FinalAndroidDownloaderRcSealPlannerTest.kt')
contract = text('app/src/test/kotlin/com/mikeyphw/xdm/android/Phase64FinalAndroidDownloaderRcSealContractTest.kt')
doc = text('docs/architecture/PHASE-64-FINAL-ANDROID-DOWNLOADER-RC-SEAL.md')
final_gate = text('tools/run-final-release-gate.sh')
phase48_gate = text('tools/run-phase-48-final-release-gate.sh')
android_manifest = text('app/src/main/AndroidManifest.xml')
changelog = repo_text('CHANGELOG.md')

require(manifest.get('current_overlay') in ACCEPTED_CURRENT_OVERLAYS, 'current overlay must point to Phase64 or a later accepted field fix')
require(64 in manifest.get('project', {}).get('implemented_phases', []), 'implemented phases must include 64')
require(manifest.get('next_phase') in {'complete', 'phase11_validation_matrix'}, 'Phase64 must mark next_phase complete or hand off to Phase11 validation matrix')
require(phase.get('status') == 'implemented', 'Phase64 must be implemented')
require(phase.get('final_android_downloader_rc_sealed') is True, 'Phase64 must seal Android downloader RC')
require(phase.get('room_schema_unchanged') == 14, 'Phase64 must keep Room schema 14')
for key in [
    'top_level_route_added',
    'automatic_transfer_start',
    'automatic_deletion',
    'automatic_upload',
    'all_files_permission_added',
    'debug_workbench_reopened',
    'release_criteria_changed',
    'built_in_browser_resurrected',
    'persisted_session_values',
]:
    require(phase.get(key) is False, f'Phase64 must keep {key} false')
require(phase.get('requires_deferred_full_validation') is True, 'Phase64 must require deferred full validation')

expected_tracks = {
    'debug_workbench_d1_d7',
    'field_bugfix_phase49_56_operational_hardening',
    'runtime_recovery_phase57_60',
    'validator_harmony_phase61',
    'real_device_smoke_phase62',
    'support_bundle_readiness_phase63',
}
require(set(phase.get('sealed_tracks', [])) == expected_tracks, 'Phase64 sealed track set must match expected RC tracks')

for needle in [
    'FinalAndroidDownloaderRcSealPlanner',
    'Debug Workbench D-series sealed',
    'Operational hardening sealed',
    'Runtime recovery flow sealed',
    'Final validators harmonized',
    'Real-device smoke represented',
    'Support bundle readiness sealed',
    'Browser-free downloader boundary',
    'Privacy and artifact handoff',
    'Private values: full links, raw headers, cookies, authorization values, bearer tokens, signatures, credential query values, and browser session values are redacted.',
]:
    require(needle in seal, f'missing final RC seal marker: {needle}')

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
    require(forbidden not in seal, f'final RC seal model must not contain unsafe marker: {forbidden}')

for needle in [
    'finalRcSealIsReadyWhenAllDownloaderReadinessSignalsArePresent',
    'finalRcSealHoldsWhenValidationOrPrivacyBoundariesAreMissing',
    'assertTrue(seal.readyForRcHandoff)',
    'assertFalse(seal.readyForRcHandoff)',
]:
    require(needle in seal_test, f'missing final RC seal test marker: {needle}')

for needle in [
    'Phase64FinalAndroidDownloaderRcSealContractTest',
    'validate-phase64-final-android-downloader-rc-seal.py',
    OVERLAY,
]:
    require(needle in contract, f'missing Phase64 contract marker: {needle}')

for prior in range(54, 64):
    names = {
        54: 'engine-escalation-planner',
        55: 'final-release-warning-explainer',
        56: 'stale-copy-architecture-noise-sweep',
        57: 'runtime-failure-recovery-ux',
        58: 'runtime-recovery-execution-guard',
        59: 'runtime-recovery-action-transparency',
        60: 'runtime-recovery-flow-seal',
        61: 'final-gate-validator-harmony',
        62: 'real-device-operational-smoke-seal',
        63: 'release-readiness-support-bundle-seal',
    }
    validator = text(f'tools/validate-phase{prior}-{names[prior]}.py')
    require(OVERLAY in validator, f'Phase{prior} validator must tolerate Phase64 as the final overlay')

for relative in [
    'tools/validate-browser-removal-phase-0-1.py',
    'tools/validate-browser-removal-phase-2.py',
    'tools/validate-browser-removal-phase-4.py',
    'tools/validate-browser-removal-phase-5.py',
    'tools/validate-browser-removal-phase-6.py',
    'tools/validate-browser-removal-phase-7.py',
    'tools/validate-downloader-experience-phase-8ab.py',
    'tools/validate-downloader-experience-phase-8c.py',
    'tools/validate-downloader-experience-phase-8d.py',
    'tools/validate-downloader-experience-phase-8e.py',
    'tools/validate-phase-8e-compose-storage-hotfix.py',
    'tools/validate-phase-34-release-handoff.py',
    'tools/validate-phase-35-release-candidate-polish.py',
    'tools/validate-phase-36-external-download-handoff.py',
    'tools/validate-phase-47-real-shared-media-sniffing-engine.py',
]:
    validator = text(relative)
    require(OVERLAY in validator, f'{relative} must tolerate Phase64 final overlay')

require('validate-phase64-final-android-downloader-rc-seal.py' in final_gate, 'final release gate must run Phase64 validator')
require('validate-phase65-diagnostic-export-download-action-fix.py' in final_gate, 'final release gate must run Phase65 validator')
require('validate-phase64-final-android-downloader-rc-seal.py' in phase48_gate, 'Phase48 gate must run Phase64 validator')
require('validate-phase65-diagnostic-export-download-action-fix.py' in phase48_gate, 'Phase48 gate must run Phase65 validator')
require('MANAGE_EXTERNAL_STORAGE' not in android_manifest, 'Android manifest must not request all-files permission')
require('Final Android Downloader RC Seal' in changelog, 'changelog must describe Phase64')
require('No built-in browser resurrection' in doc, 'Phase64 doc must record browser-free boundary')
require('Deferred validation: apply with --no-validate, then run the full gate once this final overlay is applied' in doc, 'Phase64 doc must record deferred validation instruction')

if ERRORS:
    print('Phase64 final Android downloader RC seal validation failed:')
    for error in ERRORS:
        print(f'- {error}')
    sys.exit(1)
print('Phase64 final Android downloader RC seal validator passed')
