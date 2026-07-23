#!/usr/bin/env python3
from pathlib import Path
import sys
ROOT = Path(__file__).resolve().parents[1]
checks = [
    (ROOT/'media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaTermuxRuntimeAdapter.kt', ['MediaTermuxRuntimeAdapter','TermuxRuntimeLaunchPlan','TermuxMediaRuntimeCapabilityReport','Netscape cookie file','aria2 input file','delete after terminal state','rawShell=false']),
    (ROOT/'app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt', ['Media Termux runtime adapter','MediaTermuxRuntimeAdapterCard(termuxRuntime)','Phase 26 turns worker bridge requests']),
    (ROOT/'media/src/test/kotlin/com/mikeyphw/xdm/android/media/MediaCaptureServiceTest.kt', ['termuxRuntimeAdapterBuildsTypedYtDlpPlanWithTransientCookieCleanup','termuxRuntimeAdapterBlocksMissingToolsWithInstallHelpButNoAutoInstall','termuxRuntimeAdapterBuildsAria2TransientInputAndSessionCleanup']),
    (ROOT/'PROJECT_MANIFEST.json', ['media_termux_runtime_adapter','no_validation_until_final_phase','"top_level_route_added": false']),
]
missing=[]
for path,tokens in checks:
    text = path.read_text() if path.exists() else ''
    for token in tokens:
        if token not in text:
            missing.append(f'{path.relative_to(ROOT)} missing {token}')
for path in [ROOT/'app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt', ROOT/'media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaTermuxRuntimeAdapter.kt']:
    text = path.read_text()
    for secret in ['secret-cookie','secret-token','secret-session','Authorization: Bearer']:
        if secret in text:
            missing.append(f'{path.relative_to(ROOT)} contains test secret {secret}')
    for raw_shell in ['onClick = {}', 'Runtime" || it.label == "yt-dlp"']:
        pass
if missing:
    print('\n'.join(missing), file=sys.stderr)
    sys.exit(1)
print('Phase 26 media Termux runtime adapter validation passed')
