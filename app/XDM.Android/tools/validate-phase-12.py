#!/usr/bin/env python3
from pathlib import Path
import json
import re
root = Path(__file__).resolve().parents[1]
errors = []
def require(path, needle):
    text = (root / path).read_text(encoding='utf-8')
    if needle not in text:
        errors.append(f"missing {needle!r} in {path}")
require('core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/AutomationModels.kt', 'AutomationCommandStatus')
require('tasker-plugin/src/main/kotlin/com/mikeyphw/xdm/android/tasker/TaskerContract.kt', 'ExtraIdempotencyKey')
require('app/src/main/kotlin/com/mikeyphw/xdm/android/MainActivity.kt', 'handleExternalIntent')
require('app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt', 'processAutomationCommand')
require('persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/AutomationCommandDao.kt', 'findByIdempotencyKey')
require('persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/AppDatabase.kt', 'version = 12')
require('persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/Migrations.kt', 'Migration11To12')
require('app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt', 'Automation commands')
manifest = (root / 'app/src/main/AndroidManifest.xml').read_text(encoding='utf-8')
for action in ['ADD_URL','CAPTURE_MEDIA','PAUSE_ALL','RESUME_ALL']:
    if f'com.mikeyphw.xdm.android.{action}' not in manifest:
        errors.append(f'missing manifest action {action}')

manifest_json = json.loads((root / 'PROJECT_MANIFEST.json').read_text(encoding='utf-8'))
if manifest_json.get('project', {}).get('version') != '0.12.0-alpha01':
    errors.append('PROJECT_MANIFEST project.version is not 0.12.0-alpha01')
if manifest_json.get('database', {}).get('version') != 12:
    errors.append('PROJECT_MANIFEST database.version is not 12')
if 12 not in manifest_json.get('project', {}).get('implemented_phases', []):
    errors.append('PROJECT_MANIFEST is missing implemented phase 12')
if manifest_json.get('automation_intake', {}).get('top_level_route_added') is not False:
    errors.append('PROJECT_MANIFEST automation_intake top_level_route_added must be false')

schema = json.loads((root / 'persistence/schemas/com.mikeyphw.xdm.android.persistence.AppDatabase/12.json').read_text(encoding='utf-8'))
if 'version' in schema:
    errors.append('Room schema has unsupported top-level version key')
if schema.get('database', {}).get('version') != 12:
    errors.append('Room schema database.version is not 12')
if 'automation_commands' not in {entity.get('tableName') for entity in schema.get('database', {}).get('entities', [])}:
    errors.append('Room schema is missing automation_commands')
for rel in ['core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/AutomationModels.kt','app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt']:
    data = (root / rel).read_bytes()
    bad = [b for b in data if b < 9 or (13 < b < 32)]
    if bad:
        errors.append(f'control characters found in {rel}')
if errors:
    raise SystemExit('\n'.join(errors))
print('Phase 12 automation intake validation passed')
