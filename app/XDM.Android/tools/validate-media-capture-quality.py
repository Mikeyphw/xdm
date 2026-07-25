#!/usr/bin/env python3
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
REQUIRED = [
    (ROOT / 'docs/architecture/PHASE-30-MEDIA-CAPTURE-QUALITY.md', 'Media Capture Quality Pass'),
    (ROOT / 'media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaCaptureQuality.kt', 'CaptureQualityDisposition'),
    (ROOT / 'media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaCaptureQuality.kt', 'AnalyticsBeacon'),
    (ROOT / 'media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaCaptureQuality.kt', 'secret-safe capture quality'),
    (ROOT / 'app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt', 'Media capture quality'),
    (ROOT / 'app/src/test/kotlin/com/mikeyphw/xdm/android/ArchitectureContractTest.kt', 'mediaCaptureQualityContractsArePresent'),
    (ROOT / 'media/src/test/kotlin/com/mikeyphw/xdm/android/media/MediaCaptureServiceTest.kt', 'mediaCaptureQualityGroupsDuplicatesAndSuppressesNoise'),
    (ROOT / 'PROJECT_MANIFEST.json', 'media_capture_quality'),
]
missing = []
for path, token in REQUIRED:
    text = path.read_text(encoding='utf-8') if path.is_file() else ''
    if token not in text:
        missing.append(f"{path.relative_to(ROOT)} missing {token}")
if missing:
    raise SystemExit("Phase 30 media capture quality validation failed:\n" + "\n".join(missing))
print("Phase 30 media capture quality validation passed")
