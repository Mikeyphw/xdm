#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
checks = {
    "doc": ROOT / "docs/architecture/PHASE-21-MEDIA-DOWNLOAD-ENGINE-HARDENING.md",
    "execution": ROOT / "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaExecutionLibrary.kt",
    "screens": ROOT / "app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt",
    "viewmodel": ROOT / "app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt",
    "player": ROOT / "app/src/main/kotlin/com/mikeyphw/xdm/android/Media3PlayerScreen.kt",
    "handoff": ROOT / "scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/MediaRequestHandoffStore.kt",
    "manifest": ROOT / "PROJECT_MANIFEST.json",
}
missing = [name for name, path in checks.items() if not path.is_file()]
if missing:
    raise SystemExit(f"missing files: {missing}")
texts = {name: path.read_text() for name, path in checks.items()}
required = {
    "execution": ["MediaExecutionLane", "MediaBackgroundExecutionPolicy", "MediaTempCookieFilePlan", "Aria2TransientInputPlan", "MediaSecretLeakReport", "MediaExecutionEnginePlan", "# Netscape HTTP Cookie File"],
    "screens": ["Download engine hardening", "UIDT / WorkManager fallback / foreground service policy", "No cookie leaks"],
    "viewmodel": ["enginePlan.safeSummary", "cleanupActions = enginePlan.cleanupActions", "tempCookieFileName = enginePlan.tempCookieFile?.fileName"],
    "player": ["onPlayerError", "Retry player prepare", "Media3 player error diagnostics"],
    "handoff": ["cleanupActions", "tempCookieFileName", "verifyForgotten", "process-local handoff"],
    "manifest": ["media_download_engine_hardening", "no_validation_until_final_phase", "top_level_route_added"],
}
for name, needles in required.items():
    haystack = texts[name]
    for needle in needles:
        if needle not in haystack:
            raise SystemExit(f"{name} missing {needle!r}")
for path_name in ("execution", "screens", "viewmodel", "player", "handoff"):
    if "secret-cookie" in texts[path_name] or "secret-auth" in texts[path_name]:
        raise SystemExit(f"test secret leaked into main source {path_name}")
if "ytDlpFormatSelector!!" in (ROOT / "media/src/test/kotlin/com/mikeyphw/xdm/android/media/MediaCaptureServiceTest.kt").read_text():
    raise SystemExit("unnecessary non-null assertion remains in media tests")
print("Phase 21 media download engine hardening validation passed")
