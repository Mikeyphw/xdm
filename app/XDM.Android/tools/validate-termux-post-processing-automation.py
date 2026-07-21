#!/usr/bin/env python3
from pathlib import Path

root = Path(__file__).resolve().parents[1]
checks = {
    "models": root / "app/src/main/kotlin/com/mikeyphw/xdm/android/termux/PostProcessingAutomationModels.kt",
    "manager": root / "app/src/main/kotlin/com/mikeyphw/xdm/android/termux/PostProcessingAutomationManager.kt",
    "bridge_models": root / "app/src/main/kotlin/com/mikeyphw/xdm/android/termux/TermuxBridgeModels.kt",
    "templates": root / "app/src/main/kotlin/com/mikeyphw/xdm/android/termux/TermuxShellTemplates.kt",
    "screens": root / "app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt",
    "manifest": root / "PROJECT_MANIFEST.json",
}
for name, path in checks.items():
    if not path.is_file():
        raise SystemExit(f"missing {name}: {path}")

models = checks["models"].read_text()
manager = checks["manager"].read_text()
templates = checks["templates"].read_text()
screens = checks["screens"].read_text()
manifest = checks["manifest"].read_text()

required = [
    (models, "PostProcessingAutomationRule"),
    (models, "PostProcessingAutomationTrigger"),
    (models, "TermuxPostProcessingPlan"),
    (manager, "preview(download"),
    (manager, "runForMedia"),
    (templates, "XDM_POST_PROCESS"),
    (templates, "PostProcessingActionKind.CleanupPartials"),
    (screens, "Post-processing automation"),
    (screens, "Preview rules"),
    (manifest, "termux_post_processing_automation"),
    (manifest, '"raw_shell_exposed": false'),
]
for text, needle in required:
    if needle not in text:
        raise SystemExit(f"missing contract marker: {needle}")

for forbidden in ["Raw shell", "chrootctl", "RUN_COMMAND_COMMAND_DESCRIPTION\", \"Run arbitrary"]:
    if forbidden in manager or forbidden in screens:
        raise SystemExit(f"forbidden marker leaked: {forbidden}")

print("Termux post-processing automation validation passed")
