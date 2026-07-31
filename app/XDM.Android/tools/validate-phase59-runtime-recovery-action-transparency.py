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
phase = manifest.get('field_bugfix_phase_59', {})
require(manifest.get('current_overlay') in {'xdm_android_phase59_runtime_recovery_action_transparency_overlay.zip', 'xdm_android_phase60_runtime_recovery_flow_seal_overlay.zip', 'xdm_android_phase61_final_gate_validator_harmony_overlay.zip', 'xdm_android_phase62_real_device_operational_smoke_seal_overlay.zip', 'xdm_android_phase63_release_readiness_support_bundle_seal_overlay.zip'}, 'current overlay must point to Phase59 or a later accepted field-fix overlay')
require(59 in manifest.get('project', {}).get('implemented_phases', []), 'Phase59 must be recorded as implemented')
require(phase.get('room_schema_unchanged') == 14, 'Phase59 must keep Room schema 14')
require(phase.get('top_level_route_added') is False, 'Phase59 must not add a top-level route')
require(phase.get('automatic_transfer_start') is False, 'Phase59 must not auto-start transfers')
require(phase.get('automatic_deletion') is False, 'Phase59 must not delete files automatically')
require(phase.get('all_files_permission_added') is False, 'Phase59 must not add all-files permission')
require(phase.get('debug_workbench_reopened') is False, 'Phase59 must not reopen Debug Workbench')
require(phase.get('privacy', {}).get('persists_session_values') is False, 'Phase59 must not persist session values')

preview = text('core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/RuntimeRecoveryActionPreview.kt')
preview_test = text('core-model/src/test/kotlin/com/mikeyphw/xdm/android/model/RuntimeRecoveryActionPreviewPlannerTest.kt')
details = text('app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadDetails.kt')
contract = text('app/src/test/kotlin/com/mikeyphw/xdm/android/Phase59RuntimeRecoveryActionTransparencyContractTest.kt')
doc = text('docs/architecture/PHASE-59-RUNTIME-RECOVERY-ACTION-TRANSPARENCY.md')
android_manifest = text('app/src/main/AndroidManifest.xml')
final_gate = text('tools/run-final-release-gate.sh')
phase48_gate = text('tools/run-phase-48-final-release-gate.sh')
phase58_validator = text('tools/validate-phase58-runtime-recovery-execution-guard.py')
changelog = repo_text('CHANGELOG.md')

for needle in [
    'RuntimeRecoveryActionPreviewPlanner',
    'redactedReportSection',
    'Explicit tap required',
    'Recovery Doctor required',
    'No background work',
    'Private values remain redacted',
]:
    require(needle in preview, f'missing preview marker: {needle}')

for needle in [
    'RuntimeRecoveryActionPreviewPlanner.build(download, plan',
    'Action preview',
    'What happens',
    'RuntimeRecoveryActionPreviewPlanner.redactedReportSection',
]:
    require(needle in details, f'missing UI transparency marker: {needle}')

for forbidden in [
    'startTransfer',
    'enqueueTransfer',
    'MANAGE_EXTERNAL_STORAGE',
    'delete()',
    'Cookie:',
    'Authorization:',
    'Bearer secret',
]:
    require(forbidden not in preview, f'preview model must not contain unsafe marker: {forbidden}')

require('MANAGE_EXTERNAL_STORAGE' not in android_manifest, 'Android manifest must not request all-files permission')
require('partialRetryPreviewExplainsRecoveryDoctorBeforeRetry' in preview_test, 'preview test must cover Recovery Doctor explanation')
require('guidancePreviewDoesNotClaimBackgroundExecution' in preview_test, 'preview test must cover guidance-only copy')
require('previewReportRedactsHeaderLikeSafetyNotes' in preview_test, 'preview test must cover header redaction')
require('recoveryCardShowsActionPreviewBeforeCallbacks' in contract, 'contract must cover UI action preview')
require('does not auto-start retries' in doc, 'doc must record no auto-start boundary')
require('validate-phase59-runtime-recovery-action-transparency.py' in final_gate, 'final release gate must run Phase59 validator')
require('validate-phase59-runtime-recovery-action-transparency.py' in phase48_gate, 'Phase48 gate must run Phase59 validator')
require('xdm_android_phase59_runtime_recovery_action_transparency_overlay.zip' in phase58_validator and 'xdm_android_phase60_runtime_recovery_flow_seal_overlay.zip' in phase58_validator and 'xdm_android_phase61_final_gate_validator_harmony_overlay.zip' in phase58_validator, 'Phase58 validator must tolerate Phase59 and later accepted overlays')
require('XDM Android Phase59' in changelog, 'changelog must document Phase59')

print('Phase 59 runtime recovery action transparency validator passed')
