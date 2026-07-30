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

model = read('app/XDM.Android/core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/RecoveryStorageDoctor.kt')
screen = read('app/XDM.Android/app/src/main/kotlin/com/mikeyphw/xdm/android/ui/recovery/RecoveryScreen.kt')
app = read('app/XDM.Android/app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApp.kt')
view_model = read('app/XDM.Android/app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt')
manifest_text = read('app/XDM.Android/PROJECT_MANIFEST.json')
changelog = read('CHANGELOG.md')
doc = read('app/XDM.Android/docs/architecture/PHASE-51-RECOVERY-STORAGE-DOCTOR.md')
model_test = read('app/XDM.Android/core-model/src/test/kotlin/com/mikeyphw/xdm/android/model/RecoveryStorageDoctorTest.kt')
contract_test = read('app/XDM.Android/app/src/test/kotlin/com/mikeyphw/xdm/android/Phase51RecoveryStorageDoctorContractTest.kt')

require('data class RecoveryStorageDoctorSummary' in model, 'RecoveryStorageDoctor summary model missing')
require('object RecoveryStorageDoctor' in model, 'RecoveryStorageDoctor planner missing')
require('fun summarize(records: List<RecoveryRecord>)' in model, 'RecoveryStorageDoctor summarize API missing')
require('fun exportReport()' in model, 'RecoveryStorageDoctor redacted export missing')
require('Privacy: raw paths, source URLs, tokens, cookies, and authorization values are not included.' in model, 'redacted report privacy line missing')
require('fun safeArtifactLabel(record: RecoveryRecord)' in model, 'safe artifact labels missing')
require('artifactPath' not in model.split('fun exportReport()', 1)[-1].split('}', 1)[0], 'export report must not include raw artifact paths')
require('File(' not in model and '.delete()' not in model, 'Phase51 model must not delete files')

require('Recovery + Storage Doctor' in screen, 'Recovery screen must show Storage Doctor card')
require('Validate all safely' in screen, 'Recovery screen validate-all action missing')
require('Copy recovery report' in screen, 'Recovery screen report copy action missing')
require('Storage Doctor never deletes files automatically' in screen, 'Recovery UI safety copy missing')
require('RecoveryStorageDoctor.safeArtifactLabel(record)' in screen, 'Recovery expanded details must use safe artifact labels')
require('Artifact path: ${record.artifactPath}' not in screen, 'Recovery UI must not render raw artifact paths')
require('Download ID: $it' not in screen and 'Download ID:' not in screen, 'Recovery UI must not render raw download IDs')
require('ClipData.newPlainText("XDM recovery report", report)' in screen, 'Recovery report copy must use redacted report builder')

require('viewModel::validateAllRecoveryRecords' in app, 'Recovery route must pass validate-all callback')
require('fun validateAllRecoveryRecords' in view_model, 'ViewModel validate-all function missing')
require('records.forEach' in view_model and 'queueIntelligenceCoordinator.requestStart' in view_model, 'validate-all must use existing queue validation path')
require('delete()' not in view_model.split('fun validateAllRecoveryRecords', 1)[-1].split('fun ingestAutomationCommand', 1)[0], 'validate-all must not delete files')

require('Phase 51 Recovery + Storage Doctor' in changelog, 'changelog missing phase 51 entry')
require('No automatic deletion' in doc, 'Phase51 doc must state no automatic deletion')
require('No raw paths in normal UI' in doc, 'Phase51 doc must state no raw paths in normal UI')
require('No Room migration' in doc or 'schema remains 14' in doc, 'Phase51 doc must state schema boundary')
require('RecoveryStorageDoctorTest' in contract_test or 'RecoveryStorageDoctorContract' in contract_test, 'Phase51 contract test must reference doctor coverage')
require('summaryGroupsRecoveryProblemsWithoutRawArtifacts' in model_test, 'RecoveryStorageDoctor model regression test missing')
require('recoveryScreenAddsStorageDoctorWithoutRawPathLeak' in contract_test, 'Phase51 UI privacy contract missing')

try:
    manifest = json.loads(manifest_text)
    require(51 in manifest.get('project', {}).get('implemented_phases', []), 'project implemented phases must include 51')
    p51 = manifest.get('field_bugfix_phase_51', {})
    require(p51.get('room_schema_unchanged') == 14, 'Phase51 must keep Room schema 14')
    require(p51.get('top_level_route_added') is False, 'Phase51 must not add a top-level route')
    require(p51.get('debug_workbench_reopened') is False, 'Phase51 must not reopen Debug Workbench')
    require(p51.get('automatic_deletion') is False, 'Phase51 must not automatically delete files')
    privacy = p51.get('normal_ui_privacy', {})
    require(privacy.get('raw_artifact_paths_rendered') is False, 'Phase51 privacy must forbid raw artifact paths')
    require(privacy.get('secrets_rendered') is False, 'Phase51 privacy must forbid secrets')
except Exception as exc:
    errors.append(f'manifest parse failed: {exc}')

if errors:
    print('Phase 51 recovery/storage doctor validator failed:')
    for error in errors:
        print(f'- {error}')
    sys.exit(1)
print('Phase 51 recovery/storage doctor validator passed')
