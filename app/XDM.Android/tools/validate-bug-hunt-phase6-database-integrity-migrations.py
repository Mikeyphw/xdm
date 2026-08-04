#!/usr/bin/env python3
from __future__ import annotations
import sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
errors=[]
def text(rel):
    p=ROOT/rel
    if not p.is_file():
        errors.append(f'missing {rel}')
        return ''
    return p.read_text(encoding='utf-8')
contract=text('persistence/src/test/kotlin/com/mikeyphw/xdm/android/persistence/BugHuntPhase6DatabaseIntegrityContractTest.kt')
post=text('persistence/src/androidTest/kotlin/com/mikeyphw/xdm/android/persistence/PostProcessingMigrationTest.kt')
migrations=text('persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/Migrations.kt')
schema_dir=ROOT/'persistence/schemas/com.mikeyphw.xdm.android.persistence.AppDatabase'
for needle in ['foreignKeys','MigrationTestHelper','runMigrationsAndValidate','deleteDownloadGraph']:
    if needle not in contract and needle not in post and needle not in migrations:
        errors.append(f'Phase6 persistence coverage missing {needle}')
for version in range(4,18):
    if not (schema_dir/f'{version}.json').is_file():
        errors.append(f'missing retained Room schema {version}.json')
if errors:
    print('Bug-hunt Phase 6 database integrity/migration validator failed:')
    for e in errors: print('- '+e)
    sys.exit(1)
print('Bug-hunt Phase 6 database integrity/migration validator passed')
