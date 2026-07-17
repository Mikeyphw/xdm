#!/usr/bin/env python3
from pathlib import Path
import json, re, sys
ROOT = Path(__file__).resolve().parents[1]
checks = {
    "resolution status model": (ROOT / "core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DownloadModels.kt", "enum class MediaResolutionStatus"),
    "variant expiry model": (ROOT / "core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DownloadModels.kt", "fun isExpired"),
    "dash resolver": (ROOT / "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaInboxContract.kt", "parseDashManifest"),
    "media variant entity": (ROOT / "persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/Entities.kt", "data class MediaVariantEntity"),
    "media variant dao": (ROOT / "persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/MediaCaptureDao.kt", "observeVariants"),
    "migration 10 to 11": (ROOT / "persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/Migrations.kt", "Migration10To11"),
    "schema": (ROOT / "persistence/schemas/com.mikeyphw.xdm.android.persistence.AppDatabase/11.json", '"version": 11'),
    "media route variant ui": (ROOT / "app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt", "Variants"),
    "route wiring": (ROOT / "app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApp.kt", "selectMediaVariant"),
    "architecture": (ROOT / "docs/architecture/PHASE-11-MEDIA-RESOLUTION-VARIANTS.md", "No new top-level route was added"),
    "tests": (ROOT / "media/src/test/kotlin/com/mikeyphw/xdm/android/media/MediaCaptureServiceTest.kt", "dashManifestVariantsAreParsedWithoutNetwork"),
}
errors=[]
for label,(file,needle) in checks.items():
    if not file.is_file(): errors.append(f"missing {label}: {file.relative_to(ROOT)}"); continue
    text=file.read_text(encoding="utf-8")
    if needle not in text: errors.append(f"{label} marker missing: {needle}")
    if "TODO(" in text or "TODO:" in text: errors.append(f"unfinished TODO in {file.relative_to(ROOT)}")
manifest=json.loads((ROOT/'PROJECT_MANIFEST.json').read_text())
if 11 not in manifest['project']['implemented_phases']: errors.append('project manifest does not declare Phase 11')
if manifest['database']['version'] != 11: errors.append('project manifest does not declare database v11')
app_database=(ROOT/'persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/AppDatabase.kt').read_text()
match=re.search(r"version\s*=\s*(\d+)", app_database)
if match is None or int(match.group(1)) != 11: errors.append('Room database is not v11')
screens=(ROOT/'app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt').read_text()
if 'onClick = {}' in screens: errors.append('Phase 11 introduced an inert UI action')
if 'AppRoute.MediaResolution' in screens or 'AppRoute.MediaDetails' in screens: errors.append('Phase 11 introduced a forbidden top-level route')
media_tests=''.join(p.read_text(encoding='utf-8') for p in (ROOT/'media/src/test').rglob('*.kt'))
if 'kotlin.test' in media_tests: errors.append('Phase 11 media tests must use JUnit imports')
if errors:
    print('Phase 11 validation failed:')
    for error in errors: print(f'- {error}')
    sys.exit(1)
print('Phase 11 validation passed: media resolution, variant persistence, selected variant UI, schema v11, and topography constraints are present')
