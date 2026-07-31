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

native_models = read('app/XDM.Android/transfer-native/src/main/kotlin/com/mikeyphw/xdm/android/transfer/nativeengine/NativeTransferModels.kt')
native_backend = read('app/XDM.Android/transfer-native/src/main/kotlin/com/mikeyphw/xdm/android/transfer/nativeengine/NativeHttpDownloadBackend.kt')
native_marker = read('app/XDM.Android/transfer-native/src/main/kotlin/com/mikeyphw/xdm/android/transfer/nativeengine/NativeBackendMarker.kt')
release_readiness = read('app/XDM.Android/core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/ReleaseReadinessModels.kt')
final_gate_model = read('app/XDM.Android/core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/FinalReleaseGateModels.kt')
activity = read('app/XDM.Android/core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/OperationalActivity.kt')
activity_test = read('app/XDM.Android/core-model/src/test/kotlin/com/mikeyphw/xdm/android/model/OperationalActivityTest.kt')
planner = read('app/XDM.Android/media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaDownloadPlanner.kt')
resolver = read('app/XDM.Android/media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaResolverWorkspace.kt')
sniffing = read('app/XDM.Android/media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaSniffingEngine.kt')
resolver_test = read('app/XDM.Android/media/src/test/kotlin/com/mikeyphw/xdm/android/media/MediaResolverWorkspaceTest.kt')
contract = read('app/XDM.Android/app/src/test/kotlin/com/mikeyphw/xdm/android/Phase56StaleCopyArchitectureNoiseContractTest.kt')
phase50_validator = read('app/XDM.Android/tools/validate-phase50-operational-repair.py')
phase55_validator = read('app/XDM.Android/tools/validate-phase55-final-release-warning-explainer.py')
manifest_text = read('app/XDM.Android/PROJECT_MANIFEST.json')
changelog = read('CHANGELOG.md')
doc = read('app/XDM.Android/docs/architecture/PHASE-56-STALE-COPY-ARCHITECTURE-NOISE-SWEEP.md')
phase48_gate = read('app/XDM.Android/tools/run-phase-48-final-release-gate.sh')
final_gate = read('app/XDM.Android/tools/run-final-release-gate.sh')

require('This destination type is not available to the native transfer engine' in native_models, 'native destination failure must use human copy')
require('SAF arrives in Phase' not in native_models, 'stale SAF phase copy must be removed')
require('Phase 3 native engine' not in native_models, 'native destination error must not mention Phase 3')
require('Server access was denied (HTTP $statusCode)' in native_backend, 'HTTP access failure must use human copy')
require('Metadata probe failed with HTTP $statusCode' not in native_backend, 'raw metadata probe failure copy must be removed')
require('const val phase' not in native_marker and 'capabilityLabel' in native_marker, 'native marker must not expose stale phase constant')
require('Phase 16 install/update readiness' not in release_readiness, 'install/update readiness details must not mention Phase 16')
require('Phase 16 must not migrate' not in release_readiness, 'schema details must not mention Phase 16')
require('Phase 17 artifacts' not in final_gate_model, 'final gate version copy must not mention Phase 17')
require('validators through Phase 17' not in final_gate_model, 'final gate validator copy must not mention Phase 17')

require('Methods: ${context.enabledEngines.map(::engineLabel)' in activity, 'diagnostic export must render Methods labels')
require('Method: ' in activity and 'engine=' not in activity, 'diagnostic events must not render engine= machine key')
require('recoveryClassificationLabel(record.classification)' in activity, 'recovery events must humanize classification')
require('handoffSourceLabel(record.source)' in activity, 'handoff events must humanize source')
require('handoffStatusLabel(record.status)' in activity, 'handoff events must humanize status')
require('diagnostics export uses human method labels instead of engine keys' in activity_test, 'operational activity label regression test missing')
require('recovery and handoff events use human labels' in activity_test, 'recovery/handoff label regression test missing')

require('Source type: ${kind.humanLabel()}' in planner, 'media planner must label source type')
require('request: ${intent.humanLabel()}' in planner, 'media planner must label intent')
require('Kind: ${kind.name}' not in planner and 'intent: ${intent.name}' not in planner, 'media planner must not render raw enum names')
require('capture.kind.humanLabel()' in resolver, 'resolver history/probe must humanize media kind')
require('variant.kind.humanLabel()' in resolver, 'resolver variant detail fallback must humanize kind')
require('input.source.humanLabel()' in sniffing, 'sniffing diagnostics must humanize request source')
require('mediaPlannerExplanationUsesHumanLabels' in resolver_test, 'media planner human-label test missing')
require('resolverWorkspaceFallbacksUseHumanKindLabels' in resolver_test, 'resolver human-label test missing')

require('Server access was denied (HTTP $statusCode)' in phase50_validator, 'Phase50 validator must be updated for Phase56 copy')
require('xdm_android_phase56_stale_copy_architecture_noise_sweep_overlay.zip' in phase55_validator and 'xdm_android_phase57_runtime_failure_recovery_ux_overlay.zip' in phase55_validator and 'xdm_android_phase57_runtime_failure_recovery_ux_r2_overlay.zip' in phase55_validator and 'xdm_android_phase58_runtime_recovery_execution_guard_overlay.zip' in phase55_validator and 'xdm_android_phase58_runtime_recovery_execution_guard_r2_overlay.zip' in phase55_validator and 'xdm_android_phase59_runtime_recovery_action_transparency_overlay.zip' in phase55_validator and 'xdm_android_phase60_runtime_recovery_flow_seal_overlay.zip' in phase55_validator and 'xdm_android_phase61_final_gate_validator_harmony_overlay.zip' in phase55_validator, 'Phase55 validator must tolerate Phase56/Phase57 and later accepted overlays')
require('Phase56StaleCopyArchitectureNoiseContractTest' in contract, 'Phase56 contract test missing')
require('Phase56 removes stale implementation copy' in doc, 'Phase56 doc missing scope')
require('no release criteria change' in doc, 'Phase56 doc missing release boundary')
require('XDM Android Phase 56 Stale Copy / Architecture Noise Sweep' in changelog, 'changelog missing Phase56 entry')
require('validate-phase56-stale-copy-architecture-noise-sweep.py' in phase48_gate, 'Phase48 gate must include Phase56 validator')
require('tools/validate-phase56-stale-copy-architecture-noise-sweep.py' in final_gate, 'Final release gate must include Phase56 validator')

try:
    manifest = json.loads(manifest_text)
    implemented = manifest.get('project', {}).get('implemented_phases', [])
    require(56 in implemented, 'implemented phases must include 56')
    require(manifest.get('current_overlay') in {'xdm_android_phase56_stale_copy_architecture_noise_sweep_overlay.zip', 'xdm_android_phase57_runtime_failure_recovery_ux_overlay.zip', 'xdm_android_phase57_runtime_failure_recovery_ux_r2_overlay.zip', 'xdm_android_phase58_runtime_recovery_execution_guard_overlay.zip', 'xdm_android_phase58_runtime_recovery_execution_guard_r2_overlay.zip', 'xdm_android_phase59_runtime_recovery_action_transparency_overlay.zip', 'xdm_android_phase60_runtime_recovery_flow_seal_overlay.zip', 'xdm_android_phase61_final_gate_validator_harmony_overlay.zip', 'xdm_android_phase62_real_device_operational_smoke_seal_overlay.zip', 'xdm_android_phase63_release_readiness_support_bundle_seal_r2_overlay.zip', 'xdm_android_phase64_final_android_downloader_rc_seal_r2_overlay.zip', 'xdm_android_phase65_diagnostic_export_download_action_fix_overlay.zip'}, 'current overlay must point to Phase56 or a later accepted field-fix overlay')
    require(manifest.get('next_phase') in {'field_bugfix_phase_57_release_prep_or_complete', 'field_bugfix_phase_58_targeted_follow_up_or_complete', 'field_bugfix_phase_59_targeted_follow_up_or_complete', 'field_bugfix_phase_60_targeted_follow_up_or_complete', 'field_bugfix_phase_61_targeted_follow_up_or_complete', 'phase63_release_readiness_support_bundle_seal', 'complete', 'phase64_final_android_downloader_rc_seal'}, 'next phase must point to release prep / complete')
    p56 = manifest.get('field_bugfix_phase_56', {})
    require(p56.get('room_schema_unchanged') == 14, 'Phase56 must keep Room schema 14')
    for key in ['top_level_route_added', 'debug_workbench_reopened', 'automatic_transfer_start', 'all_files_permission_added', 'automatic_upload', 'release_criteria_changed']:
        require(p56.get(key) is False, f'Phase56 boundary must be false: {key}')
    sweep = p56.get('sweep', {})
    for key in ['runtime_phase_copy_removed', 'native_error_copy_humanized', 'operational_diagnostics_human_labels', 'media_copy_human_labels', 'validators_forward_compatible']:
        require(sweep.get(key) is True, f'Phase56 sweep flag missing: {key}')
    privacy = p56.get('privacy', {})
    for key in ['shows_raw_urls', 'shows_cookie_values', 'shows_authorization_values', 'shows_bearer_tokens', 'shows_credential_query_values']:
        require(privacy.get(key) is False, f'privacy flag must be false: {key}')
except Exception as exc:
    errors.append(f'manifest parse failed: {exc}')

if errors:
    print('Phase 56 stale copy / architecture noise sweep validator failed:')
    for error in errors:
        print(f'- {error}')
    sys.exit(1)
print('Phase 56 stale copy / architecture noise sweep validator passed')
# later accepted overlay: xdm_android_phase60_runtime_recovery_flow_seal_overlay.zip
