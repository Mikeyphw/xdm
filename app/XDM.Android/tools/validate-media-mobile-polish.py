#!/usr/bin/env python3
from pathlib import Path
import json

ROOT = Path(__file__).resolve().parents[1]
checks = {
    "phase doc": (ROOT / "docs/architecture/PHASE-32-MEDIA-MOBILE-POLISH.md", "No tiny scroll islands"),
    "planner dashboard": (ROOT / "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaMobilePolish.kt", "MediaMobilePolishDashboard"),
    "sticky job": (ROOT / "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaMobilePolish.kt", "StickyCurrentJob"),
    "accessibility": (ROOT / "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaMobilePolish.kt", "AccessibilityLabels"),
    "foldable": (ROOT / "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaMobilePolish.kt", "FoldableReady"),
    "no tiny islands": (ROOT / "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaMobilePolish.kt", "NoTinyScrollIslands"),
    "screen card": (ROOT / "app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt", "Media mobile polish"),
    "screen phase copy": (ROOT / "app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt", "Phase 32 makes the Media stack phone-friendly"),
    "manifest": (ROOT / "PROJECT_MANIFEST.json", "media_mobile_polish"),
    "contract test": (ROOT / "app/src/test/kotlin/com/mikeyphw/xdm/android/ArchitectureContractTest.kt", "mediaMobilePolishContractsArePresent"),
    "unit test": (ROOT / "media/src/test/kotlin/com/mikeyphw/xdm/android/media/MediaCaptureServiceTest.kt", "mobilePolishDeckKeepsMediaStackPhoneFriendlyAndSecretSafe"),
}
for name, (path, token) in checks.items():
    if not path.is_file():
        raise SystemExit(f"missing {name}: {path}")
    text = path.read_text(encoding="utf-8")
    if token not in text:
        raise SystemExit(f"missing {name} token {token!r} in {path}")

screens = (ROOT / "app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt").read_text(encoding="utf-8")
if "enum class AppRoute" in screens:
    forbidden_routes = ["Mobile UX", "Polish", "Media Polish"]
    for token in forbidden_routes:
        if f'label = "{token}"' in screens:
            raise SystemExit(f"forbidden top-level route label: {token}")
planner = (ROOT / "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaMobilePolish.kt").read_text(encoding="utf-8")
if "Bearer\\\\s+(?!<redacted>)[A-Za-z0-9._~+/=-]+" in planner:
    raise SystemExit("mobile polish must use a prose-safe Bearer scanner")
for bad in ["CookieManager", "addJavascriptInterface", "raw shell", "authorization=", "cookie=", "bearer tokens"]:
    if bad in planner:
        raise SystemExit(f"privacy/mobile polish validator rejected token: {bad}")

manifest = json.loads((ROOT / "PROJECT_MANIFEST.json").read_text(encoding="utf-8"))
project_phases = manifest.get("project", {}).get("implemented_phases", [])
root_phases = manifest.get("implemented_phases", [])
if 32 not in project_phases:
    raise SystemExit("PROJECT_MANIFEST project.implemented_phases is missing phase 32")
if 32 not in root_phases:
    raise SystemExit("PROJECT_MANIFEST implemented_phases is missing phase 32")
if manifest.get("next_phase") not in {"media_final_validation_gate", "complete"}:
    raise SystemExit("PROJECT_MANIFEST next_phase must advance to media_final_validation_gate after Phase 32 or complete after Phase 33")
if manifest.get("media_mobile_polish", {}).get("no_tiny_scroll_islands") is not True:
    raise SystemExit("PROJECT_MANIFEST media_mobile_polish.no_tiny_scroll_islands must be true")

print("Phase 32 media mobile polish validation passed")
