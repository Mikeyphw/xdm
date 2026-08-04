#!/usr/bin/env python3
from __future__ import annotations
import sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
errors=[]
def require_file(rel, needles):
    p=ROOT/rel
    if not p.is_file():
        errors.append(f'missing {rel}')
        return
    text=p.read_text(encoding='utf-8')
    for needle in needles:
        if needle not in text:
            errors.append(f'{rel} missing {needle}')
require_file('media/src/test/kotlin/com/mikeyphw/xdm/android/media/MediaSniffingEngineTest.kt',['HlsPlaylist','DashManifest'])
require_file('media/src/test/kotlin/com/mikeyphw/xdm/android/media/BrowserHandoffMediaCoordinatorTest.kt',['BrowserHandoffMediaCoordinator'])
require_file('browser-extension/src/test/kotlin/com/mikeyphw/xdm/android/browserextension/BugHuntPhase5BrowserExtensionContractTest.kt',['onSendHeaders.addListener','stableMediaId'])
require_file('tools/validate-bug-hunt-phase5-browser-handoff-media.py',['Bug-hunt Phase 5'])
for rel in ['tools/validate-phase53-extension-detection-quality-gate.py','tools/validate-phase54-engine-escalation-planner.py','tools/validate-phase52-browser-session-health.py']:
    if not (ROOT/rel).is_file(): errors.append(f'missing supporting validator {rel}')
if errors:
    print('Bug-hunt Phase 5 browser/media validator failed:')
    for e in errors: print('- '+e)
    sys.exit(1)
print('Bug-hunt Phase 5 browser handoff/media sniffing validator passed')
