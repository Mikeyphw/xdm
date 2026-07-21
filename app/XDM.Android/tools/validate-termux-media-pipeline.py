#!/usr/bin/env python3
from pathlib import Path
root = Path(__file__).resolve().parents[1]
checks = {
    "models": root / "app/src/main/kotlin/com/mikeyphw/xdm/android/termux/TermuxMediaPipelineModels.kt",
    "manager": root / "app/src/main/kotlin/com/mikeyphw/xdm/android/termux/TermuxMediaPipelineManager.kt",
    "templates": root / "app/src/main/kotlin/com/mikeyphw/xdm/android/termux/TermuxShellTemplates.kt",
    "screens": root / "app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt",
    "manifest": root / "PROJECT_MANIFEST.json",
}
missing = [name for name, path in checks.items() if not path.is_file()]
if missing:
    raise SystemExit(f"missing Phase 9 files: {missing}")
text = "\n".join(path.read_text() for path in checks.values())
for needle in ["TermuxMediaPipelineStatus", "YtDlpDownload", "yt-dlp download", "FFprobe", "Fast-start MP4", "raw_shell_exposed", "no_chroot_support"]:
    if needle not in text:
        raise SystemExit(f"missing Phase 9 contract marker: {needle}")
if "onClick = {}" in checks["screens"].read_text():
    raise SystemExit("placeholder click handler detected")
print("Termux media pipeline contract OK")
