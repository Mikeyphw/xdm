#!/usr/bin/env python3
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
REQUIRED = [
    (ROOT / 'docs/architecture/PHASE-31-SESSION-PRIVACY-CLEANUP-AUDIT.md', 'Session Privacy + Cleanup Audit'),
    (ROOT / 'media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaSessionPrivacyAudit.kt', 'MediaPrivacySurface'),
    (ROOT / 'media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaSessionPrivacyAudit.kt', 'TermuxCommandPreview'),
    (ROOT / 'media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaSessionPrivacyAudit.kt', 'durable secret-safe'),
    (ROOT / 'app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt', 'Session privacy audit'),
    (ROOT / 'app/src/test/kotlin/com/mikeyphw/xdm/android/ArchitectureContractTest.kt', 'mediaSessionPrivacyAuditContractsArePresent'),
    (ROOT / 'media/src/test/kotlin/com/mikeyphw/xdm/android/media/MediaCaptureServiceTest.kt', 'sessionPrivacyAuditBlocksDurableSecretLeaks'),
    (ROOT / 'PROJECT_MANIFEST.json', 'media_session_privacy_audit'),
]
missing = []
for path, token in REQUIRED:
    text = path.read_text(encoding='utf-8') if path.is_file() else ''
    if token not in text:
        missing.append(f"{path.relative_to(ROOT)} missing {token}")
if missing:
    raise SystemExit("Phase 31 session privacy audit validation failed:\n" + "\n".join(missing))
print("Phase 31 session privacy audit validation passed")
