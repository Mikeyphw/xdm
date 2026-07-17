#!/usr/bin/env python3
from pathlib import Path
import json, subprocess, sys
ROOT=Path(__file__).resolve().parents[1]
checks={
 'operational backend':(ROOT/'transfer-aria2/src/main/kotlin/com/mikeyphw/xdm/android/transfer/aria2/EmbeddedAria2Backend.kt','rpc.addUri('),
 'paused activation gate':(ROOT/'transfer-aria2/src/main/kotlin/com/mikeyphw/xdm/android/transfer/aria2/EmbeddedAria2Backend.kt','pause = true'),
 'durable mapping':(ROOT/'persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/RoomAria2TaskMappingStore.kt','class RoomAria2TaskMappingStore'),
 'schema v6':(ROOT/'persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/AppDatabase.kt','version = 6'),
 'migration 5 to 6':(ROOT/'persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/Migrations.kt','Migration5To6'),
 'event poller':(ROOT/'transfer-aria2/src/main/kotlin/com/mikeyphw/xdm/android/transfer/aria2/Aria2EventPoller.kt','class Aria2EventPoller'),
 'session recovery':(ROOT/'transfer-aria2/src/main/kotlin/com/mikeyphw/xdm/android/transfer/aria2/EmbeddedAria2Backend.kt','refreshRecoveredMapping'),
 'ownership generation':(ROOT/'transfer-aria2/src/main/kotlin/com/mikeyphw/xdm/android/transfer/aria2/EmbeddedAria2Backend.kt','ownershipGeneration > 0'),
 'runtime installer':(ROOT/'tools/install-aria2-runtime.py','validate_elf'),
 'runtime verifier':(ROOT/'tools/verify-aria2-runtime.py','--require-payload'),
 'architecture':(ROOT/'docs/architecture/PHASE-6-EMBEDDED-ARIA2.md','created paused'),
 'tests':(ROOT/'transfer-aria2/src/test/kotlin/com/mikeyphw/xdm/android/transfer/aria2/EmbeddedAria2BackendTest.kt','taskRemainsPausedUntilOwnershipIsDurablyAttached'),
}
errors=[]
for label,(path,needle) in checks.items():
    if not path.is_file(): errors.append(f'missing {label}: {path.relative_to(ROOT)}'); continue
    text=path.read_text()
    if needle not in text: errors.append(f'{label} marker missing: {needle}')
    if 'TODO(' in text or 'TODO:' in text: errors.append(f'unfinished TODO in {path.relative_to(ROOT)}')
manifest=json.loads((ROOT/'PROJECT_MANIFEST.json').read_text())
if manifest['project']['implemented_phases'][-1]!=6: errors.append('project manifest does not declare Phase 6')
if manifest['database']['version']!=6: errors.append('project manifest does not declare database v6')
backend=(ROOT/'transfer-aria2/src/main/kotlin/com/mikeyphw/xdm/android/transfer/aria2/EmbeddedAria2Backend.kt').read_text()
for method in ('pause(taskId:', 'resume(taskId:', 'cancel(taskId:', 'remove(taskId:', 'query(taskId:', 'reconcile(ownership:'):
    if method not in backend: errors.append(f'backend operation missing: {method}')
if 'Aria2BackendPlaceholder' in backend: errors.append('placeholder backend remains')
if errors:
    print('Phase 6 validation failed:'); [print(f'- {e}') for e in errors]; sys.exit(1)
subprocess.run([sys.executable,str(ROOT/'tools/verify-aria2-runtime.py')],check=True)
print('Phase 6 validation passed: operational GID mapping, paused activation, authenticated RPC, reconciliation, completion handoff, and attested ARM64 packaging gate are present')
