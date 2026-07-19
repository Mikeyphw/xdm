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
readiness = manifest.get("release_install_readiness", {})
project_version = project.get("version", "")
try:
    minor_version = int(project_version.split(".")[1])
except (IndexError, ValueError):
    minor_version = -1
if minor_version < 16:
    errors.append("PROJECT_MANIFEST project.version is older than 0.16.x")
if 16 not in project.get("implemented_phases", []):
    errors.append("PROJECT_MANIFEST is missing implemented phase 16")
if database.get("version") != 13:
    errors.append("Phase 16 must keep database.version at 13")
if str(manifest.get("next_phase")) != "17":
    errors.append("PROJECT_MANIFEST next_phase is not 17")
for key in [
    "install_update_gate_script",
    "recovery_surface_ready",
    "redacted_diagnostic_bundle_ready",
    "aria2_payload_gate_retained",
    "ci_static_validators_updated",
    "deprecated_clipboard_api_removed",
]:
    if readiness.get(key) is not True:
        errors.append(f"release_install_readiness.{key} is not true")
if readiness.get("schema_version_unchanged") != 13:
    errors.append("release_install_readiness.schema_version_unchanged is not 13")
if readiness.get("package_id_stable") != "com.mikeyphw.xdm.android":
    errors.append("release_install_readiness.package_id_stable is not com.mikeyphw.xdm.android")
if readiness.get("top_level_route_added") is not False:
    errors.append("Phase 16 must not add a top-level route")

build_gradle = require_file("app/build.gradle.kts")
if "versionCode = 17" not in build_gradle:
    errors.append("app/build.gradle.kts missing versionCode = 17")
if 'versionName = "0.16.0-alpha01"' not in build_gradle:
    errors.append("app/build.gradle.kts missing versionName 0.16.0-alpha01")
for needle in ['applicationId = "com.mikeyphw.xdm.android"', 'applicationIdSuffix = ".beta"', 'applicationIdSuffix = ".debug"']:
    if needle not in build_gradle:
        errors.append(f"app/build.gradle.kts missing {needle!r}")

require_text("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/ReleaseReadinessModels.kt", "ReleaseInstallReadinessGate")
require_text("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/ReleaseReadinessModels.kt", "InstallUpdateReadinessReport")
require_text("core-model/src/test/kotlin/com/mikeyphw/xdm/android/model/ReleaseReadinessModelsTest.kt", "cleanBetaReadinessReportHasNoBlockingChecks")
require_text("app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt", "ReleaseInstallReadinessGate.evaluate")
require_text("app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt", "Install/update readiness")
require_text("app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt", "Phase 16 readiness")
require_text("app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt", "ClipboardManager")
require_text("app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt", "setPrimaryClip")
if "LocalClipboardManager" in require_file("app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt"):
    errors.append("Screens.kt still uses deprecated LocalClipboardManager")
require_text("docs/architecture/PHASE-16-PACKAGING-RECOVERY-READINESS.md", "Room at schema v13")
require_text("app/src/test/kotlin/com/mikeyphw/xdm/android/ArchitectureContractTest.kt", "phaseSixteenPackagingRecoveryReadinessContractsArePresent")

workflow = require_file(".github/workflows/android.yml")
for validator in ["validate-phase-11.py", "validate-phase-12.py", "validate-phase-13.py", "validate-phase-14.py", "validate-phase-15.py", "validate-phase-16.py"]:
    if validator not in workflow:
        errors.append(f"Android CI is missing {validator}")
for task in ["lintBeta", "assembleBeta", "verify-aria2-runtime.py"]:
    if task not in workflow:
        errors.append(f"Android CI is missing {task}")

schema_path = root / "persistence/schemas/com.mikeyphw.xdm.android.persistence.AppDatabase/13.json"
if not schema_path.is_file():
    errors.append("Room schema 13.json is missing")
else:
    schema = json.loads(schema_path.read_text(encoding="utf-8"))
    if "version" in schema:
        errors.append("Room schema has unsupported top-level version key")
    if schema.get("database", {}).get("version") != 13:
        errors.append("Room schema database.version is not 13")

app_shell = require_file("app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApp.kt")
routes = re.findall(r'AppRoute\.([A-Za-z]+)', app_shell)
if "Packaging" in routes or "Updates" in routes:
    errors.append("Phase 16 added a forbidden top-level packaging/update route")

for rel in [
    "core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/ReleaseReadinessModels.kt",
    "app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt",
    "app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt",
]:
    data = (root / rel).read_bytes()
    bad = [b for b in data if b < 9 or (13 < b < 32)]
    if bad:
        errors.append(f"control characters found in {rel}")

if errors:
    raise SystemExit("\n".join(errors))
print("Phase 16 packaging/recovery/install-update readiness validation passed")
