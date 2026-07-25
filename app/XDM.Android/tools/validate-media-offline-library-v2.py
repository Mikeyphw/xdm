#!/usr/bin/env python3
from pathlib import Path
root = Path(__file__).resolve().parents[1]
checks = {
    'docs/architecture/PHASE-28-MEDIA-OFFLINE-LIBRARY-V2.md': ['filterable', 'sortable', 'sidecar-aware'],
    'media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaOfflineLibraryV2.kt': ['OfflineLibraryV2Filter', 'OfflineLibraryV2SortKey', 'OfflineLibraryV2Dashboard', 'safeExportJson', 'RemoveSidecar'],
    'app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt': ['Offline Library 2.0', 'Phase 28 makes completed media filterable', 'OfflineLibraryV2Card(library)'],
    'PROJECT_MANIFEST.json': ['media_offline_library_v2', 'no_validation_until_final_phase'],
}
for rel, tokens in checks.items():
    p = root / rel
    if not p.is_file():
        raise SystemExit(f'missing {rel}')
    text = p.read_text(errors='replace')
    for token in tokens:
        if token not in text:
            raise SystemExit(f'missing {token!r} in {rel}')
screens = (root/'app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt').read_text(errors='replace')
if 'fun MediaInboxScreen' not in screens:
    raise SystemExit('media inbox screen missing')
# Phase 6 intentionally promotes Library while preserving the Phase 28 planner and cards.
print('Phase 28 media offline library v2 validation passed')
