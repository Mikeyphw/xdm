#!/usr/bin/env python3
from pathlib import Path
import sys
ROOT = Path(__file__).resolve().parents[1]
checks = [
    (ROOT/'media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaWorkerBridge.kt', ['MediaWorkerBridgeKind','MediaWorkerBridgePlanner','Android UIDT worker','WorkManager foreground worker','Termux yt-dlp adapter','rawShellExposed']),
    (ROOT/'app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt', ['Media worker bridge','MediaWorkerBridgeCard(workerBridge)','Phase 25 converts ready media actions']),
    (ROOT/'media/src/test/kotlin/com/mikeyphw/xdm/android/media/MediaCaptureServiceTest.kt', ['mediaWorkerBridgeBuildsUidtRequestForDirectMediaWithoutSecrets','mediaWorkerBridgeBuildsTypedTermuxYtDlpRequestWithCleanupOwnedSecrets']),
    (ROOT/'PROJECT_MANIFEST.json', ['media_worker_bridge','no_validation_until_final_phase','"top_level_route_added": false']),
]
missing=[]
for path,tokens in checks:
    text = path.read_text() if path.exists() else ''
    for token in tokens:
        if token not in text:
            missing.append(f'{path.relative_to(ROOT)} missing {token}')
for path in [ROOT/'app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt', ROOT/'media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaWorkerBridge.kt']:
    text = path.read_text()
    for secret in ['secret-cookie','secret-token','secret-session','Authorization: Bearer']:
        if secret in text:
            missing.append(f'{path.relative_to(ROOT)} contains test secret {secret}')
if missing:
    print('\n'.join(missing), file=sys.stderr)
    sys.exit(1)
print('Phase 25 media worker bridge validation passed')
