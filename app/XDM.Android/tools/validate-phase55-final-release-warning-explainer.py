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

model = read('app/XDM.Android/core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/FinalReleaseGateModels.kt')
model_test = read('app/XDM.Android/core-model/src/test/kotlin/com/mikeyphw/xdm/android/model/FinalReleaseGateModelsTest.kt')
developer_tools = read('app/XDM.Android/app/src/main/kotlin/com/mikeyphw/xdm/android/ui/developer/DeveloperToolsScreen.kt')
view_model = read('app/XDM.Android/app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt')
contract = read('app/XDM.Android/app/src/test/kotlin/com/mikeyphw/xdm/android/Phase55FinalReleaseWarningExplainerContractTest.kt')
manifest_text = read('app/XDM.Android/PROJECT_MANIFEST.json')
changelog = read('CHANGELOG.md')
doc = read('app/XDM.Android/docs/architecture/PHASE-55-FINAL-RELEASE-WARNING-EXPLAINER.md')
phase54_validator = read('app/XDM.Android/tools/validate-phase54-engine-escalation-planner.py')
phase48_gate = read('app/XDM.Android/tools/run-phase-48-final-release-gate.sh')
final_gate = read('app/XDM.Android/tools/run-final-release-gate.sh')

require('data class FinalReleaseGateExplanation' in model, 'FinalReleaseGateExplanation model missing')
require('data class FinalReleaseGateOwner' in model, 'FinalReleaseGateOwner model missing')
require('object FinalReleaseGateExplainer' in model, 'FinalReleaseGateExplainer missing')
require('val explanations: List<FinalReleaseGateExplanation>' in model, 'FinalReleaseGateReport explanations missing')
require('val actionableExplanations' in model, 'FinalReleaseGateReport actionable explanations missing')
require('fun redactedExplanationSummary()' in model, 'redactedExplanationSummary missing')
for needle in ['Impact:', 'Safe to ignore:', 'Fix action:', 'Owning check:']:
    require(needle in model, f'redacted explanation summary missing {needle}')
for needle in ['tools/verify-aria2-runtime.py', 'tools/run-final-release-gate.sh', 'FinalReleaseGateModelsTest']:
    require(needle in model, f'owning check mapping missing {needle}')
require('Yes for Native-only debug testing' in model, 'aria2 safe-to-ignore guidance missing')
require('Yes in debug diagnostics before a full gate run' in model, 'debug full-validation guidance missing')
require('Run the full Devtool selected-task validation' in model, 'full-validation fix action missing')

require('debugWarningsIncludeActionableReleaseExplanations' in model_test, 'Phase55 model test missing')
require('assertFalse(redacted.contains("aria2.payload"))' in model_test, 'model test must assert raw aria2 id is hidden')
require('assertFalse(redacted.contains("full.validation"))' in model_test, 'model test must assert raw full validation id is hidden')

require('Release warning explainer' in developer_tools, 'Developer UI must show Release warning explainer')
for needle in ['Impact: ${explanation.impact}', 'Safe to ignore: ${explanation.safeToIgnore}', 'Fix action: ${explanation.fixAction}', 'Owning check: ${explanation.owner.validator}']:
    require(needle in developer_tools, f'Developer UI missing {needle}')
require('finalReleaseGateReport.actionableExplanations.ifEmpty' in developer_tools, 'UI must prefer actionable explanations')
require('state.finalReleaseGateReport.checks.take' not in developer_tools, 'UI must not render bare final-gate check list')
require('check.id' not in developer_tools, 'normal UI must not render raw final-gate ids')
require('FinalReleaseGateSeverity' not in developer_tools, 'normal UI must not render raw enum severity mapping')
for forbidden in ['Cookie value', 'Authorization value', 'Bearer token', 'signed URL', 'sourceUrl', 'requestHeaders']:
    require(forbidden not in developer_tools, f'Developer UI must not expose {forbidden}')

require('finalReleaseGateReport.redactedExplanationSummary()' in view_model, 'support report must use redacted explanation summary')
require('finalReleaseGateReport.redactedSummary()' not in view_model, 'support report must not use bare final gate summary only')

require('Phase55FinalReleaseWarningExplainerContractTest' in contract, 'Phase55 contract test missing')
require('Release warning explainer' in contract, 'Phase55 contract must inspect UI card')
require('XDM Android Phase 55 Final Release Warning Explainer' in changelog, 'changelog missing Phase55 entry')
require('Phase55 explains final-release warnings' in doc, 'Phase55 doc missing scope')
require('does not change release criteria' in doc, 'Phase55 doc must preserve release criteria')
require('no raw final-gate check ids in normal UI' in doc, 'Phase55 doc missing raw id boundary')
require('Phase 54 engine escalation planner validator passed' in phase54_validator, 'Phase54 validator must remain present')
require('validate-phase55-final-release-warning-explainer.py' in phase48_gate, 'Phase48 final gate must include Phase55 validator')
require('tools/validate-phase55-final-release-warning-explainer.py' in final_gate, 'Final release gate must include Phase55 validator')

try:
    manifest = json.loads(manifest_text)
    require(55 in manifest.get('project', {}).get('implemented_phases', []), 'implemented phases must include 55')
    require(manifest.get('current_overlay') in {'xdm_android_phase55_final_release_warning_explainer_overlay.zip', 'xdm_android_phase56_stale_copy_architecture_noise_sweep_overlay.zip', 'xdm_android_phase57_runtime_failure_recovery_ux_overlay.zip', 'xdm_android_phase57_runtime_failure_recovery_ux_r2_overlay.zip', 'xdm_android_phase58_runtime_recovery_execution_guard_overlay.zip', 'xdm_android_phase58_runtime_recovery_execution_guard_r2_overlay.zip', 'xdm_android_phase59_runtime_recovery_action_transparency_overlay.zip', 'xdm_android_phase60_runtime_recovery_flow_seal_overlay.zip', 'xdm_android_phase61_final_gate_validator_harmony_overlay.zip', 'xdm_android_phase62_real_device_operational_smoke_seal_overlay.zip', 'xdm_android_phase63_release_readiness_support_bundle_seal_r2_overlay.zip', 'xdm_android_phase64_final_android_downloader_rc_seal_r2_overlay.zip', 'xdm_android_phase65_diagnostic_export_download_action_fix_overlay.zip'}, 'current overlay must point to Phase55 or a later accepted field-fix overlay')
    require(manifest.get('next_phase') in {'field_bugfix_phase_56_stale_copy_architecture_noise_sweep', 'field_bugfix_phase_57_release_prep_or_complete', 'field_bugfix_phase_58_targeted_follow_up_or_complete', 'field_bugfix_phase_59_targeted_follow_up_or_complete', 'field_bugfix_phase_60_targeted_follow_up_or_complete', 'field_bugfix_phase_61_targeted_follow_up_or_complete', 'phase63_release_readiness_support_bundle_seal', 'complete', 'phase64_final_android_downloader_rc_seal'}, 'next phase must point to Phase56 stale copy sweep or later release prep')
    p55 = manifest.get('field_bugfix_phase_55', {})
    require(p55.get('room_schema_unchanged') == 14, 'Phase55 must keep Room schema 14')
    require(p55.get('top_level_route_added') is False, 'Phase55 must not add a top-level route')
    require(p55.get('debug_workbench_reopened') is False, 'Phase55 must not reopen Debug Workbench')
    require(p55.get('automatic_transfer_start') is False, 'Phase55 must not start transfers automatically')
    require(p55.get('all_files_permission_added') is False, 'Phase55 must not add all-files permission')
    require(p55.get('automatic_upload') is False, 'Phase55 must not add automatic upload')
    require(p55.get('release_criteria_changed') is False, 'Phase55 must not change release criteria')
    explainer = p55.get('explainer', {})
    for key in ['impact', 'safe_to_ignore', 'fix_action', 'owning_validator_or_test', 'redacted_support_summary']:
        require(explainer.get(key) is True, f'explainer flag missing: {key}')
    require(explainer.get('raw_check_ids_in_normal_ui') is False, 'raw final-gate ids must stay out of normal UI')
    privacy = p55.get('privacy', {})
    for key in ['shows_raw_urls', 'shows_cookie_values', 'shows_authorization_values', 'shows_bearer_tokens', 'shows_credential_query_values']:
        require(privacy.get(key) is False, f'privacy flag must be false: {key}')
    require(privacy.get('normal_ui_human_labels_only') is True, 'manifest missing human-label UI flag')
except Exception as exc:
    errors.append(f'manifest parse failed: {exc}')

if errors:
    print('Phase 55 final release warning explainer validator failed:')
    for error in errors:
        print(f'- {error}')
    sys.exit(1)
print('Phase 55 final release warning explainer validator passed')
# later accepted overlay: xdm_android_phase60_runtime_recovery_flow_seal_overlay.zip
