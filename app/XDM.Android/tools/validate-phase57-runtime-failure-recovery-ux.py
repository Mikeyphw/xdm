#!/usr/bin/env python3
from pathlib import Path
import json
import sys


def find_root() -> Path:
    cursor = Path.cwd().resolve()
    for parent in [cursor, *cursor.parents]:
        if (parent / 'app' / 'XDM.Android' / 'settings.gradle.kts').is_file():
            return parent
        if (parent / 'settings.gradle.kts').is_file() and (parent / 'app' / 'src').is_dir():
            return parent.parent.parent
    raise SystemExit('Android root not found')

ROOT = find_root()
errors: list[str] = []


def read(rel: str) -> str:
    path = ROOT / rel
    if not path.is_file():
        errors.append(f'missing {rel}')
        return ''
    return path.read_text()


def require(condition: bool, message: str) -> None:
    if not condition:
        errors.append(message)

planner = read('app/XDM.Android/core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/RuntimeFailureRecovery.kt')
planner_test = read('app/XDM.Android/core-model/src/test/kotlin/com/mikeyphw/xdm/android/model/RuntimeFailureRecoveryPlannerTest.kt')
details = read('app/XDM.Android/app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadDetails.kt')
downloads = read('app/XDM.Android/app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadsScreen.kt')
contract = read('app/XDM.Android/app/src/test/kotlin/com/mikeyphw/xdm/android/Phase57RuntimeFailureRecoveryUxContractTest.kt')
manifest_text = read('app/XDM.Android/PROJECT_MANIFEST.json')
android_manifest = read('app/XDM.Android/app/src/main/AndroidManifest.xml')
changelog = read('CHANGELOG.md')
doc = read('app/XDM.Android/docs/architecture/PHASE-57-RUNTIME-FAILURE-RECOVERY-UX.md')
phase54_validator = read('app/XDM.Android/tools/validate-phase54-engine-escalation-planner.py')
phase55_validator = read('app/XDM.Android/tools/validate-phase55-final-release-warning-explainer.py')
phase56_validator = read('app/XDM.Android/tools/validate-phase56-stale-copy-architecture-noise-sweep.py')
d7_contract = read('app/XDM.Android/app/src/test/kotlin/com/mikeyphw/xdm/android/DebugWorkbenchD7FinalDebugSealContractTest.kt')
d7_validator = read('app/XDM.Android/tools/validate-debug-workbench-d7-final-debug-seal.py')
phase48_gate = read('app/XDM.Android/tools/run-phase-48-final-release-gate.sh')
final_gate = read('app/XDM.Android/tools/run-final-release-gate.sh')

require('object RuntimeFailureRecoveryPlanner' in planner, 'runtime failure planner missing')
for label in [
    'Server requires browser access',
    'Browser session may be stale',
    'Media inspection recommended',
    'Storage visibility needs review',
    'Recovery state needs review',
    'Try another transfer method',
    'Queue policy is holding this item',
    'Retry needs review',
]:
    require(label in planner, f'missing human cause label: {label}')
for action in [
    'Refresh from browser',
    'Retry with captured session',
    'Try yt-dlp',
    'Try aria2',
    'Try XDM Native',
    'Re-check storage visibility',
    'Open Recovery Doctor',
    'Copy redacted report',
]:
    require(action in planner or action in details, f'missing recovery action: {action}')
require('PrivacyDiagnosticsRedactor.redactUrl' in planner, 'redacted report must sanitize URLs')
require('PrivacyDiagnosticsRedactor.redactText' in planner, 'redacted report must sanitize error text')
require('Private values: cookies, authorization values, bearer tokens, signatures, and credential query values are redacted.' in planner, 'redacted report must state privacy boundary')
for forbidden in ['startTransfer', 'enqueueTransfer', 'MANAGE_EXTERNAL_STORAGE', 'delete()', 'Cookie: ${', 'Authorization: ${']:
    require(forbidden not in planner, f'planner must not contain dangerous/runtime raw value pattern: {forbidden}')

require('RuntimeFailureRecoveryPlanner.evaluate(download)' in details, 'Download details must evaluate recovery plan')
require('RuntimeFailureRecoveryCard' in details, 'Download details recovery card missing')
require('runRuntimeRecoveryAction' in details, 'Recovery action dispatcher missing')
require('onOpenActivityAttention = onOpenActivityAttention' in details and 'onOpenActivityAttention = onOpenActivityAttention' in downloads, 'Recovery Doctor bridge must be wired')
require('copyTextToClipboard(context, "XDM recovery report", report)' in details, 'redacted report copy action missing')
require('Open the source page in your browser' in details, 'refresh-from-browser guidance missing')
require('Open Media, inspect this source' in details, 'yt-dlp/media guidance missing')

for test_name in [
    'forbiddenFailuresPrioritizeBrowserRefreshWithoutLeakingSecrets',
    'recoveryRequiredOpensRecoveryDoctorBeforeRetrying',
    'mediaFailuresPreferYtDlpInspection',
    'storageFailuresOfferVisibilityCheckAndRecoveryDoctor',
    'healthyCompletedDownloadsDoNotShowFailureRecovery',
]:
    require(test_name in planner_test, f'missing planner regression test: {test_name}')
require('Phase57RuntimeFailureRecoveryUxContractTest' in contract, 'Phase57 contract test missing')

require('MANAGE_EXTERNAL_STORAGE' not in android_manifest, 'Phase57 must not add all-files permission')
require('XDM Android Phase 57 Runtime Failure Recovery UX' in changelog, 'changelog missing Phase57 entry')
require('does not start transfers automatically' in doc, 'Phase57 doc must state no automatic transfer start')
require('validate-phase57-runtime-failure-recovery-ux.py' in phase48_gate, 'Phase48 final gate must include Phase57 validator')
require('tools/validate-phase57-runtime-failure-recovery-ux.py' in final_gate, 'Final release gate must include Phase57 validator')
require('xdm_android_phase57_runtime_failure_recovery_ux_overlay.zip' in phase54_validator and 'xdm_android_phase57_runtime_failure_recovery_ux_r2_overlay.zip' in phase54_validator, 'Phase54 validator must tolerate Phase57/r2')
require('xdm_android_phase57_runtime_failure_recovery_ux_overlay.zip' in phase55_validator and 'xdm_android_phase57_runtime_failure_recovery_ux_r2_overlay.zip' in phase55_validator, 'Phase55 validator must tolerate Phase57/r2')
require('xdm_android_phase57_runtime_failure_recovery_ux_overlay.zip' in phase56_validator and 'xdm_android_phase57_runtime_failure_recovery_ux_r2_overlay.zip' in phase56_validator, 'Phase56 validator must tolerate Phase57/r2')
require('\\"overlay\\": \\"xdm_android_debug_workbench_phase_d7_final_debug_seal_overlay.zip\\"' in d7_contract, 'D7 contract must check sealed D7 manifest block overlay')
require('\\"current_overlay\\": \\"xdm_android_debug_workbench_phase_d7_final_debug_seal_overlay.zip\\"' not in d7_contract, 'D7 contract must not pin global current overlay to D7')
require('d7.get("overlay") == "xdm_android_debug_workbench_phase_d7_final_debug_seal_overlay.zip"' in d7_validator, 'D7 validator must check D7 block overlay')
require('manifest.get("current_overlay") == "xdm_android_debug_workbench_phase_d7_final_debug_seal_overlay.zip"' not in d7_validator, 'D7 validator must not pin global current overlay to D7')

try:
    manifest = json.loads(manifest_text)
    implemented = manifest.get('project', {}).get('implemented_phases', [])
    require(57 in implemented, 'implemented phases must include 57')
    require(manifest.get('current_overlay') in {'xdm_android_phase57_runtime_failure_recovery_ux_overlay.zip', 'xdm_android_phase57_runtime_failure_recovery_ux_r2_overlay.zip'}, 'current overlay must point to Phase57/r2')
    require(manifest.get('next_phase') == 'field_bugfix_phase_58_targeted_follow_up_or_complete', 'next phase must point to targeted follow-up or complete')
    p57 = manifest.get('field_bugfix_phase_57', {})
    require(p57.get('room_schema_unchanged') == 14, 'Phase57 must keep Room schema 14')
    for key in ['top_level_route_added', 'debug_workbench_reopened', 'automatic_transfer_start', 'automatic_deletion', 'all_files_permission_added', 'automatic_upload', 'release_criteria_changed']:
        require(p57.get(key) is False, f'Phase57 boundary must be false: {key}')
    ux = p57.get('ux', {})
    for key in ['download_details_recovery_card', 'server_access_refresh_guidance', 'captured_session_retry_guidance', 'yt_dlp_media_fallback_guidance', 'aria2_native_method_switch_guidance', 'storage_visibility_recheck_guidance', 'recovery_doctor_bridge', 'redacted_failure_report']:
        require(ux.get(key) is True, f'Phase57 UX flag missing: {key}')
    privacy = p57.get('privacy', {})
    for key in ['shows_raw_urls', 'shows_cookie_values', 'shows_authorization_values', 'shows_bearer_tokens', 'shows_credential_query_values', 'persists_session_values']:
        require(privacy.get(key) is False, f'Phase57 privacy flag must be false: {key}')
except Exception as exc:
    errors.append(f'manifest parse failed: {exc}')

if errors:
    print('Phase 57 runtime failure recovery UX validator failed:')
    for error in errors:
        print(f'- {error}')
    sys.exit(1)
print('Phase 57 runtime failure recovery UX validator passed')
