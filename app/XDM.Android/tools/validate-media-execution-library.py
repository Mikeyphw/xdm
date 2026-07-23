#!/usr/bin/env python3
from pathlib import Path
import json
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
checks = [
    (ROOT / 'media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaExecutionLibrary.kt', [
        'MediaExecutionStage', 'MediaQueuedDownloadSpec', 'OfflineMediaSidecarMetadata',
        'OfflineMediaLibraryItem', 'redactMediaUrl', 'toRedactedJson',
    ]),
    (ROOT / 'scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/MediaRequestHandoffStore.kt', [
        'process-local handoff', 'ConcurrentHashMap', 'redactedSummary',
    ]),
    (ROOT / 'scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferExecutionRuntime.kt', [
        'MediaRequestHandoffStore.forDownload', 'headers = mediaHandoff?.headers.orEmpty()',
        'isExpiringUrl = mediaHandoff?.isExpiringUrl == true', 'MediaRequestHandoffStore.forget(downloadId)',
    ]),
    (ROOT / 'app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt', [
        'mediaExecutionPlanner.queueSpec', 'MediaRequestHandoffStore.remember',
        'downloadWithYtDlp(record, variants, selection)',
    ]),
    (ROOT / 'app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt', [
        'Media download execution', 'Retry media', 'Resume media', 'Open player',
        'onDownload(capture, mediaPlan.trackSelection)',
    ]),
    (ROOT / 'docs/architecture/PHASE-20-MEDIA-EXECUTION-LIBRARY.md', [
        'Phase 20', 'No DRM bypass', 'No raw shell commands',
    ]),
]
for path, tokens in checks:
    if not path.is_file():
        sys.exit(f'missing required file: {path.relative_to(ROOT)}')
    text = path.read_text()
    for token in tokens:
        if token not in text:
            sys.exit(f'missing token {token!r} in {path.relative_to(ROOT)}')

screens = (ROOT / 'app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt').read_text()
if re.search(r'AppRoute\.(Library|Player|Resolver|Browser)', screens):
    sys.exit('phase 20 must not introduce new top-level media routes')

manifest = json.loads((ROOT / 'PROJECT_MANIFEST.json').read_text())
phase = manifest.get('media_execution_library', {})
if not phase.get('redacted_sidecar_metadata') or phase.get('top_level_route_added'):
    sys.exit('manifest media_execution_library contract is incomplete')

for path in [ROOT / 'media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaExecutionLibrary.kt']:
    text = path.read_text()
    forbidden = ['secret-cookie', 'secret-auth', 'SID=', 'Bearer secret']
    for token in forbidden:
        if token in text:
            sys.exit(f'forbidden sample secret leaked into production source: {token}')

print('Media execution/library validation passed')
