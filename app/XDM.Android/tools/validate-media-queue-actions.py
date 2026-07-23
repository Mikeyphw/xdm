#!/usr/bin/env python3
from pathlib import Path
import sys
ROOT = Path(__file__).resolve().parents[1]
checks = [
    (ROOT/'media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaQueueActions.kt', ['MediaQueueActionKind','MediaQueueActionPlanner','Cleanup finished','Cancel media','secretPatterns']),
    (ROOT/'app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt', ['Media queue actions','MediaQueueActionsCard(queueActions)','Phase 24 turns telemetry']),
    (ROOT/'media/src/test/kotlin/com/mikeyphw/xdm/android/media/MediaCaptureServiceTest.kt', ['mediaQueueActionsExposeLaunchRetryCancelAndCleanupWithoutSecrets','mediaQueueActionsExplainBlockedPreQueueStates']),
    (ROOT/'PROJECT_MANIFEST.json', ['media_queue_actions','no_validation_until_final_phase','"top_level_route_added": false']),
]
missing=[]
for path,tokens in checks:
    text = path.read_text() if path.exists() else ''
    for token in tokens:
        if token not in text:
            missing.append(f'{path.relative_to(ROOT)} missing {token}')
for path in [ROOT/'app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt', ROOT/'media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaQueueActions.kt']:
    text = path.read_text()
    for secret in ['secret-cookie','secret-token','secret-session','Authorization: Bearer']:
        if secret in text:
            missing.append(f'{path.relative_to(ROOT)} contains test secret {secret}')
if missing:
    print('\n'.join(missing), file=sys.stderr)
    sys.exit(1)
print('Phase 24 media queue actions validation passed')
