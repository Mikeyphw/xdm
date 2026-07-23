#!/usr/bin/env python3
from pathlib import Path
root = Path(__file__).resolve().parents[1]
checks = {
    'docs/architecture/PHASE-29-MEDIA-PLAYER-DIAGNOSTICS.md': ['source, network, decoder', 'no DRM bypass', 'playback-position'],
    'media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaPlayerDiagnostics.kt': ['MediaPlayerDiagnosticBucket', 'RetryPrepare', 'Protected media diagnostics only', 'MediaPlayerPositionMemoryPlan'],
    'app/src/main/kotlin/com/mikeyphw/xdm/android/Media3PlayerScreen.kt': ['Player 2.0 diagnostics', 'Retry player prepare', 'Track availability', 'Playback position'],
    'app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt': ['Player diagnostics deck', 'Phase 29 makes Media3 playback failures'],
    'PROJECT_MANIFEST.json': ['media_player_diagnostics', 'no_validation_until_final_phase'],
}
for rel, tokens in checks.items():
    p = root / rel
    if not p.is_file():
        raise SystemExit(f'missing {rel}')
    text = p.read_text(errors='replace')
    for token in tokens:
        if token not in text:
            raise SystemExit(f'missing {token!r} in {rel}')
player = (root/'app/src/main/kotlin/com/mikeyphw/xdm/android/Media3PlayerScreen.kt').read_text(errors='replace')
if 'bypass' in player.lower() and 'does not bypass DRM' not in player:
    raise SystemExit('player wording suggests bypass')
print('Phase 29 media player diagnostics validation passed')
