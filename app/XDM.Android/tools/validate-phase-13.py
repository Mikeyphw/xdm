#!/usr/bin/env python3
from pathlib import Path
import json
root = Path(__file__).resolve().parents[1]
errors = []
def require(path, needle):
    text = (root / path).read_text(encoding='utf-8')
    if needle not in text:
        errors.append(f"missing {needle!r} in {path}")
require('core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/AutomationModels.kt', 'BrowserHandoffPolicy')
require('core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/AutomationModels.kt', 'SensitivePayloadRejected')
require('browser-integration/src/main/kotlin/com/mikeyphw/xdm/android/browser/SharedLinkParser.kt', 'BrowserHandoffContract')
require('app/src/main/kotlin/com/mikeyphw/xdm/android/MainActivity.kt', 'BrowserHandoffContract.ExtraRequestHeaders')
require('app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt', 'AutomationRejectionReason')
require('app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt', 'Browser origins')
require('persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/AppDatabase.kt', 'version = 14')
require('persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/Migrations.kt', 'Migration13To14')
manifest_json = json.loads((root / 'PROJECT_MANIFEST.json').read_text(encoding='utf-8'))
project_version = manifest_json.get('project', {}).get('version', '')
try:
    minor_version = int(project_version.split('.')[1])
except (IndexError, ValueError):
    minor_version = -1
if minor_version < 13:
    errors.append('PROJECT_MANIFEST project.version is older than 0.13.x')
if manifest_json.get('database', {}).get('version') != 14:
    errors.append('PROJECT_MANIFEST database.version is not 14')
if 13 not in manifest_json.get('project', {}).get('implemented_phases', []):
    errors.append('PROJECT_MANIFEST is missing implemented phase 13')
if manifest_json.get('browser_handoff_hardening', {}).get('top_level_route_added') is not False:
    errors.append('Phase 13 must not add a top-level route')
schema = json.loads((root / 'persistence/schemas/com.mikeyphw.xdm.android.persistence.AppDatabase/14.json').read_text(encoding='utf-8'))
if 'version' in schema:
    errors.append('Room schema has unsupported top-level version key')
if schema.get('database', {}).get('version') != 14:
    errors.append('Room schema database.version is not 14')
automation = next((entity for entity in schema.get('database', {}).get('entities', []) if entity.get('tableName') == 'automation_commands'), None)
if not automation:
    errors.append('Room schema is missing automation_commands')
else:
    fields = {field.get('columnName') for field in automation.get('fields', [])}
    for column in ['originPackage','originHost','sanitizedHeaders','rejectionReason']:
        if column not in fields:
            errors.append(f'Room schema is missing automation_commands.{column}')
for rel in ['core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/AutomationModels.kt','browser-integration/src/main/kotlin/com/mikeyphw/xdm/android/browser/SharedLinkParser.kt']:
    data = (root / rel).read_bytes()
    bad = [b for b in data if b < 9 or (13 < b < 32)]
    if bad:
        errors.append(f'control characters found in {rel}')
if errors:
    raise SystemExit('\n'.join(errors))
print('Phase 13 browser handoff hardening validation passed')
