#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
checks = {
    "models": ROOT / "app/src/main/kotlin/com/mikeyphw/xdm/android/termux/TermuxBridgeModels.kt",
    "manager": ROOT / "app/src/main/kotlin/com/mikeyphw/xdm/android/termux/TermuxBridgeManager.kt",
    "store": ROOT / "app/src/main/kotlin/com/mikeyphw/xdm/android/termux/TermuxRunStore.kt",
    "templates": ROOT / "app/src/main/kotlin/com/mikeyphw/xdm/android/termux/TermuxShellTemplates.kt",
    "screens": ROOT / "app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt",
    "manifest": ROOT / "PROJECT_MANIFEST.json",
}
for name, path in checks.items():
    if not path.is_file():
        raise SystemExit(f"missing {name}: {path}")
text = {name: path.read_text(encoding="utf-8") for name, path in checks.items()}
required = [
    ("models", "RootActionAuditRecord"),
    ("models", "RootProbe"),
    ("manager", "collectRootProcessDiagnostics"),
    ("manager", "killStuckTermuxAria2Daemon"),
    ("store", "recordRootActionLaunch"),
    ("templates", "su -c"),
    ("templates", "XDM_ROOT_ACTION"),
    ("screens", "Optional root actions"),
    ("screens", "Root audit"),
    ("manifest", "termux_optional_root"),
]
for name, needle in required:
    if needle not in text[name]:
        raise SystemExit(f"missing {needle!r} in {name}")
if "chroot" in text["manifest"].split("termux_optional_root", 1)[-1].lower() and '"no_chroot_support": true' not in text["manifest"]:
    raise SystemExit("root mode must remain chroot-free")
print("Termux optional root mode contract looks complete.")
