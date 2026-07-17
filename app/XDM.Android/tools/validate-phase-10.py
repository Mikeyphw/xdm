#!/usr/bin/env python3
from pathlib import Path
import json, re, sys
ROOT = Path(__file__).resolve().parents[1]
checks = {
    "media models": (ROOT / "core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DownloadModels.kt", "data class MediaCaptureRecord"),
    "media capture service": (ROOT / "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaInboxContract.kt", "class MediaCaptureService"),
    "hls variant parser": (ROOT / "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaInboxContract.kt", "parseHlsPlaylist"),
    "media dao": (ROOT / "persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/MediaCaptureDao.kt", "interface MediaCaptureDao"),
    "media entity": (ROOT / "persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/Entities.kt", "data class MediaCaptureEntity"),
    "migration 9 to 10": (ROOT / "persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/Migrations.kt", "Migration9To10"),
    "schema": (ROOT / "persistence/schemas/com.mikeyphw.xdm.android.persistence.AppDatabase/10.json", '"version": 10'),
    "share intent": (ROOT / "app/src/main/kotlin/com/mikeyphw/xdm/android/MainActivity.kt", "handleExternalIntent"),
    "media route ui": (ROOT / "app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt", "fun MediaInboxScreen"),
    "route wiring": (ROOT / "app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApp.kt", "MediaInboxScreen"),
    "architecture": (ROOT / "docs/architecture/PHASE-10-MEDIA-CAPTURE-INTELLIGENCE.md", "No new top-level route was added"),
    "tests": (ROOT / "media/src/test/kotlin/com/mikeyphw/xdm/android/media/MediaCaptureServiceTest.kt", "hlsPlaylistVariantsAreParsedWithoutNetwork"),
}
errors=[]
for label,(path,needle) in checks.items():
    if not path.is_file(): errors.append(f"missing {label}: {path.relative_to(ROOT)}"); continue
    text=path.read_text(encoding="utf-8")
    if needle not in text: errors.append(f"{label} marker missing: {needle}")
    if "TODO(" in text or "TODO:" in text: errors.append(f"unfinished TODO in {path.relative_to(ROOT)}")
manifest=json.loads((ROOT/'PROJECT_MANIFEST.json').read_text())
if 10 not in manifest['project']['implemented_phases']: errors.append('project manifest does not declare Phase 10')
if manifest['database']['version'] < 10: errors.append('project manifest regressed below database v10')
app_database=(ROOT/'persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/AppDatabase.kt').read_text()
match=re.search(r"version\s*=\s*(\d+)", app_database)
if match is None or int(match.group(1)) < 10: errors.append('Room database regressed below v10')
screens=(ROOT/'app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt').read_text()
if 'onClick = {}' in screens: errors.append('Phase 10 introduced an inert UI action')
if 'AppRoute.MediaCapture' in screens or 'AppRoute.MediaDetails' in screens: errors.append('Phase 10 introduced a forbidden top-level route')
if 'kotlin.test' in ''.join(p.read_text(encoding='utf-8') for p in (ROOT/'media/src/test').rglob('*.kt')): errors.append('Phase 10 media tests must use JUnit imports')
if errors:
    print('Phase 10 validation failed:')
    for error in errors: print(f'- {error}')
    sys.exit(1)
print('Phase 10 validation passed: media capture service, persisted metadata, share/view intents, Media route actions, schema v10, and topography constraints are present')
