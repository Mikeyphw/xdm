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
errors = []


def read(rel: str) -> str:
    path = ROOT / rel
    if not path.is_file():
        errors.append(f'missing {rel}')
        return ''
    return path.read_text()


def require(condition: bool, message: str) -> None:
    if not condition:
        errors.append(message)

planner = read('app/XDM.Android/core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/EngineEscalationPlanner.kt')
planner_test = read('app/XDM.Android/core-model/src/test/kotlin/com/mikeyphw/xdm/android/model/EngineEscalationPlannerTest.kt')
app = read('app/XDM.Android/app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApp.kt')
add_surface = read('app/XDM.Android/app/src/main/kotlin/com/mikeyphw/xdm/android/ui/intake/AddDownloadSurface.kt')
contract = read('app/XDM.Android/app/src/test/kotlin/com/mikeyphw/xdm/android/Phase54EngineEscalationPlannerContractTest.kt')
manifest_text = read('app/XDM.Android/PROJECT_MANIFEST.json')
changelog = read('CHANGELOG.md')
doc = read('app/XDM.Android/docs/architecture/PHASE-54-ENGINE-ESCALATION-PLANNER.md')
phase53_validator = read('app/XDM.Android/tools/validate-phase53-extension-detection-quality-gate.py')
phase52_contract = read('app/XDM.Android/app/src/test/kotlin/com/mikeyphw/xdm/android/Phase52BrowserSessionHealthContractTest.kt')

require('data class EngineEscalationPlan' in planner, 'EngineEscalationPlan model missing')
require('object EngineEscalationPlanner' in planner, 'EngineEscalationPlanner missing')
require('review-only' in planner, 'planner must document review-only boundary')
require('XDM Native with captured session' in planner, 'Native captured-session recommendation missing')
require('Media resolver or yt-dlp' in planner, 'media resolver/yt-dlp recommendation missing')
require('aria2 segmented transfer' in planner, 'aria2 recommendation missing')
require('Refresh browser capture or inspect with yt-dlp' in planner, '403 recapture recommendation missing')
require('lastHttpStatus == 401 || lastHttpStatus == 403' in planner, '401/403 path missing')
require('LargeFileThresholdBytes' in planner, 'large direct-file threshold missing')
require('hasCredentialBearingQuery' in planner, 'credential-bearing query risk missing')
require('hasShortLivedPathHint' in planner, 'short-lived path risk missing')
require('safeLabel()' in planner, 'safe label mapping missing')
require('.name' not in planner.split('private fun DownloadIntakeKind.safeLabel')[0], 'planner must not use raw enum names before safeLabel mapping')
for forbidden in ['startTransfer(', 'enqueue(', 'CoroutineScope', 'Room.databaseBuilder', 'HttpURLConnection']:
    require(forbidden not in planner, f'planner must not perform runtime work: {forbidden}')

require('EngineEscalationPlannerTest' in planner_test, 'planner unit test missing')
require('protectedSignedMediaUsesNativeWithoutLeakingSecrets' in planner_test, 'protected/session test missing')
require('pageHandoffPrefersMediaInspectionAndYtDlp' in planner_test, 'page/yt-dlp test missing')
require('largeDirectFilePrefersAria2WhenNoSessionContextExists' in planner_test, 'large aria2 test missing')
require('forbiddenStatusRecommendsRecaptureInsteadOfBlindRetry' in planner_test, '403 recapture test missing')
for secret in ['sid=secret', 'Bearer secret', 'token=secret']:
    require(secret in planner_test, f'test must assert redaction for {secret}')

require('EngineEscalationPlanner.evaluate' in app, 'XdmApp must evaluate engine escalation plan')
require('externalEngineEscalationPlan = externalEngineEscalation' in app, 'XdmApp must pass plan to AddDownloadScreen')
require('BackendType.Automatic' in app, 'XdmApp must evaluate recommendation from automatic baseline')
require('BrowserSessionHealthPlanner.evaluate' in app, 'Phase52 session health must remain wired')

require('externalEngineEscalationPlan: EngineEscalationPlan? = null' in add_surface, 'AddDownloadScreen parameter missing')
require('val visibleEngineEscalation = externalEngineEscalationPlan.takeIf' in add_surface, 'engine plan must hide after editing URL')
require('EngineEscalationCard(plan)' in add_surface, 'engine escalation card missing')
require('Engine escalation planner' in add_surface, 'semantic screen tag missing')
require('Suggested method' in add_surface or 'plan.title' in add_surface, 'suggested method UI missing')
require('Safe alternatives' in add_surface, 'safe alternatives UI missing')
require('This planner chooses only the next review action' in add_surface, 'review-only UI privacy note missing')
for forbidden in ['requestHeaders', 'Cookie value', 'Authorization value', 'Bearer token', 'plan.url', 'raw URL']:
    require(forbidden not in add_surface, f'Add Download planner UI must not expose {forbidden}')

require('Phase54EngineEscalationPlannerContractTest' in contract, 'Phase54 contract test missing')
require('XDM Android Phase 54 Engine Escalation Planner' in changelog, 'changelog missing Phase54 entry')
require('Phase54 is a planner only' in doc, 'Phase54 doc safety boundary missing')
require('does not start transfers' in doc, 'Phase54 doc must forbid automatic transfer start')
require('raw URLs, raw header names, Cookie values, Authorization values' in doc, 'Phase54 doc privacy line missing')
require('No cookies, Authorization values, bearer tokens, or full URLs are added to normal UI' in phase53_validator, 'Phase53 validator must remain present')
require('private fun repoRoot(): File' in phase52_contract, 'Phase52 warning fix must remain landed')
require('root.parentFile.parentFile' not in phase52_contract, 'Phase52 nullable warning must not return')

try:
    manifest = json.loads(manifest_text)
    require(54 in manifest.get('project', {}).get('implemented_phases', []), 'implemented phases must include 54')
    require(manifest.get('current_overlay') in {'xdm_android_phase54_engine_escalation_planner_overlay.zip', 'xdm_android_phase55_final_release_warning_explainer_overlay.zip', 'xdm_android_phase56_stale_copy_architecture_noise_sweep_overlay.zip', 'xdm_android_phase57_runtime_failure_recovery_ux_overlay.zip', 'xdm_android_phase57_runtime_failure_recovery_ux_r2_overlay.zip', 'xdm_android_phase58_runtime_recovery_execution_guard_overlay.zip', 'xdm_android_phase58_runtime_recovery_execution_guard_r2_overlay.zip', 'xdm_android_phase59_runtime_recovery_action_transparency_overlay.zip', 'xdm_android_phase60_runtime_recovery_flow_seal_overlay.zip', 'xdm_android_phase61_final_gate_validator_harmony_overlay.zip', 'xdm_android_phase62_real_device_operational_smoke_seal_overlay.zip', 'xdm_android_phase63_release_readiness_support_bundle_seal_r2_overlay.zip', 'xdm_android_phase64_final_android_downloader_rc_seal_r2_overlay.zip', 'xdm_android_phase65_diagnostic_export_download_action_fix_overlay.zip'}, 'current overlay must point to Phase54 or a later field-fix overlay')
    p54 = manifest.get('field_bugfix_phase_54', {})
    require(p54.get('room_schema_unchanged') == 14, 'Phase54 must keep Room schema 14')
    require(p54.get('top_level_route_added') is False, 'Phase54 must not add a top-level route')
    require(p54.get('debug_workbench_reopened') is False, 'Phase54 must not reopen Debug Workbench')
    require(p54.get('automatic_transfer_start') is False, 'Phase54 must not start transfers automatically')
    require(p54.get('all_files_permission_added') is False, 'Phase54 must not add all-files permission')
    require(p54.get('automatic_upload') is False, 'Phase54 must not add automatic upload')
    plan = p54.get('planner', {})
    require(plan.get('native_with_captured_session') is True, 'manifest missing Native captured session flag')
    require(plan.get('media_resolver_or_ytdlp_for_pages_and_playlists') is True, 'manifest missing media resolver/yt-dlp flag')
    require(plan.get('aria2_for_large_direct_files') is True, 'manifest missing aria2 large file flag')
    require(plan.get('browser_recapture_for_401_403') is True, 'manifest missing 401/403 recapture flag')
    privacy = p54.get('privacy', {})
    require(privacy.get('normal_ui_human_labels_only') is True, 'manifest missing human-label UI flag')
    for key in ['shows_raw_urls', 'shows_raw_header_names', 'shows_cookie_values', 'shows_authorization_values', 'shows_bearer_tokens', 'shows_credential_query_values']:
        require(privacy.get(key) is False, f'privacy flag must be false: {key}')
except Exception as exc:
    errors.append(f'manifest parse failed: {exc}')

if errors:
    print('Phase 54 engine escalation planner validator failed:')
    for error in errors:
        print(f'- {error}')
    sys.exit(1)
print('Phase 54 engine escalation planner validator passed')
# later accepted overlay: xdm_android_phase60_runtime_recovery_flow_seal_overlay.zip
