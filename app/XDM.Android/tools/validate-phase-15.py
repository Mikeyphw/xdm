#!/usr/bin/env python3
from pathlib import Path
import json
import re

root = Path(__file__).resolve().parents[1]
errors = []

def require_file(path: str) -> str:
    target = root / path
    if not target.is_file():
        errors.append(f"missing file: {path}")
        return ""
    return target.read_text(encoding="utf-8")

def require_text(path: str, needle: str) -> None:
    text = require_file(path)
    if needle not in text:
        errors.append(f"missing {needle!r} in {path}")

manifest = json.loads(require_file("PROJECT_MANIFEST.json") or "{}")
project = manifest.get("project", {})
database = manifest.get("database", {})
ux = manifest.get("ux_accessibility_polish", {})
project_version = project.get("version", "")
try:
    minor_version = int(project_version.split(".")[1])
except (IndexError, ValueError):
    minor_version = -1
if minor_version < 15:
    errors.append("PROJECT_MANIFEST project.version is older than 0.15.x")
if 15 not in project.get("implemented_phases", []):
    errors.append("PROJECT_MANIFEST is missing implemented phase 15")
if database.get("version") != 13:
    errors.append("Phase 15 must keep database.version at 13")
next_phase_raw = manifest.get("next_phase", "0")
try:
    next_phase = int(str(next_phase_raw))
except (TypeError, ValueError):
    next_phase = 999 if str(next_phase_raw).lower() == "complete" else 0
if next_phase < 16:
    errors.append("PROJECT_MANIFEST next_phase is older than 16")
for key in [
    "compact_phone_cards",
    "accessible_action_labels",
    "state_descriptions",
    "stable_touch_targets",
    "settings_health_summary",
    "diagnostics_copy_action_accessible",
]:
    if ux.get(key) is not True:
        errors.append(f"ux_accessibility_polish.{key} is not true")
if ux.get("schema_version_unchanged") != 13:
    errors.append("ux_accessibility_polish.schema_version_unchanged is not 13")
if ux.get("top_level_route_added") is not False:
    errors.append("Phase 15 must not add a top-level route")

build_gradle = require_file("app/build.gradle.kts")
version_code_match = re.search(r'versionCode\s*=\s*(\d+)', build_gradle)
if not version_code_match or int(version_code_match.group(1)) < 16:
    errors.append("app/build.gradle.kts versionCode is older than phase 15")
version_name_match = re.search(r'versionName\s*=\s*"0\.(\d+)\.0-(?:alpha01|rc01)"', build_gradle)
if not version_name_match or int(version_name_match.group(1)) < 15:
    errors.append("app/build.gradle.kts versionName is older than 0.15.0-alpha01/rc01")

screens = require_file("app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt")
app_shell = require_file("app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApp.kt")
for needle in [
    "Download overview",
    "Phase 15 polish",
    "stateDescription",
    "contentDescription",
    "sizeIn(minWidth = 48.dp",
    "Copy privacy-safe release summary",
]:
    if needle not in screens:
        errors.append(f"Screens.kt missing {needle!r}")
if "stateDescription" not in app_shell:
    errors.append("XdmApp.kt must expose navigation state descriptions")
if "AppRoute.entries" not in app_shell:
    errors.append("XdmApp.kt must keep route topology central")
require_text("docs/architecture/PHASE-15-UX-ACCESSIBILITY-POLISH.md", "No Room schema bump")
require_text("app/src/test/kotlin/com/mikeyphw/xdm/android/ArchitectureContractTest.kt", "phaseFifteenUxAccessibilityContractsArePresent")

schema_path = root / "persistence/schemas/com.mikeyphw.xdm.android.persistence.AppDatabase/13.json"
if not schema_path.is_file():
    errors.append("Room schema 13.json is missing")
else:
    schema = json.loads(schema_path.read_text(encoding="utf-8"))
    if "version" in schema:
        errors.append("Room schema has unsupported top-level version key")
    if schema.get("database", {}).get("version") != 13:
        errors.append("Room schema database.version is not 13")

routes = re.findall(r'AppRoute\.([A-Za-z]+)', app_shell)
if "Accessibility" in routes or "UX" in routes:
    errors.append("Phase 15 added a forbidden top-level UX/accessibility route")

for rel in [
    "app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt",
    "app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApp.kt",
    "app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt",
]:
    data = (root / rel).read_bytes()
    bad = [b for b in data if b < 9 or (13 < b < 32)]
    if bad:
        errors.append(f"control characters found in {rel}")

if errors:
    raise SystemExit("\n".join(errors))
print("Phase 15 UX/accessibility polish validation passed")
