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
final_gate = manifest.get("final_public_release_gate", {})
project_version = project.get("version", "")
try:
    minor_version = int(project_version.split(".")[1])
except (IndexError, ValueError):
    minor_version = -1
if minor_version < 17:
    errors.append("PROJECT_MANIFEST project.version is older than 0.17.x")
if "alpha" in project_version.lower():
    errors.append("PROJECT_MANIFEST project.version must not be alpha for Phase 17")
if 17 not in project.get("implemented_phases", []):
    errors.append("PROJECT_MANIFEST is missing implemented phase 17")
if str(manifest.get("next_phase")).lower() not in {"complete", "post17-parity"}:
    errors.append("PROJECT_MANIFEST next_phase must be complete or post17-parity")
if database.get("version") != 14:
    errors.append("Phase 17 must keep database.version at 14")
for key in [
    "release_candidate",
    "full_validation_required",
    "full_validation_gate_script",
    "static_validators_complete",
    "release_docs_complete",
    "diagnostics_redacted",
    "install_update_ready",
    "aria2_payload_gate_required_for_publishable_artifacts",
    "signed_release_verification_documented",
    "artifact_checksums_documented",
]:
    if final_gate.get(key) is not True:
        errors.append(f"final_public_release_gate.{key} is not true")
if final_gate.get("schema_version_unchanged") != 14 or final_gate.get("room_schema_locked") != 14:
    errors.append("final_public_release_gate must lock Room schema at 14")
if final_gate.get("package_identity_locked") != "com.mikeyphw.xdm.android":
    errors.append("final_public_release_gate.package_identity_locked is not com.mikeyphw.xdm.android")
if final_gate.get("top_level_route_added") is not False:
    errors.append("Phase 17 must not add a top-level route")

build_gradle = require_file("app/build.gradle.kts")
version_code_match = re.search(r'versionCode\s*=\s*(\d+)', build_gradle)
if not version_code_match or int(version_code_match.group(1)) < 18:
    errors.append("app/build.gradle.kts versionCode is older than phase 17")
version_name_match = re.search(r'versionName\s*=\s*"0\.(\d+)\.0-rc\d+"', build_gradle)
if not version_name_match or int(version_name_match.group(1)) < 17:
    errors.append("app/build.gradle.kts missing a 0.17+ release-candidate versionName")
for needle in [
    'applicationId = "com.mikeyphw.xdm.android"',
    'applicationIdSuffix = ".beta"',
    'applicationIdSuffix = ".debug"',
    'warningsAsErrors = true',
]:
    if needle not in build_gradle:
        errors.append(f"app/build.gradle.kts missing {needle!r}")

require_text("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/FinalReleaseGateModels.kt", "FinalPublicReleaseGate")
require_text("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/FinalReleaseGateModels.kt", "FinalReleaseGateReport")
require_text("core-model/src/test/kotlin/com/mikeyphw/xdm/android/model/FinalReleaseGateModelsTest.kt", "cleanSignedReleaseReportPassesFinalGate")
require_text("app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt", "FinalPublicReleaseGate.evaluate")
require_text("app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt", "Final release gate")
require_text("app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt", "Release readiness")
require_text("docs/architecture/PHASE-17-FINAL-RELEASE-GATE.md", "full devtool validation")
require_text("tools/run-final-release-gate.sh", "validate-phase-17.py")
require_text("app/src/test/kotlin/com/mikeyphw/xdm/android/ArchitectureContractTest.kt", "phaseSeventeenFinalReleaseGateContractsArePresent")

workflow = require_file(".github/workflows/android.yml")
for validator in ["validate-phase-13.py", "validate-phase-14.py", "validate-phase-15.py", "validate-phase-16.py", "validate-phase-17.py"]:
    if validator not in workflow:
        errors.append(f"Android CI is missing {validator}")
for task in ["lintBeta", "assembleBeta", "verify-aria2-runtime.py", "run-final-release-gate.sh"]:
    if task not in workflow:
        errors.append(f"Android CI is missing {task}")

schema_path = root / "persistence/schemas/com.mikeyphw.xdm.android.persistence.AppDatabase/14.json"
if not schema_path.is_file():
    errors.append("Room schema 14.json is missing")
else:
    schema = json.loads(schema_path.read_text(encoding="utf-8"))
    if "version" in schema:
        errors.append("Room schema has unsupported top-level version key")
    if schema.get("database", {}).get("version") != 14:
        errors.append("Room schema database.version is not 14")

app_shell = require_file("app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApp.kt")
routes = re.findall(r'AppRoute\.([A-Za-z]+)', app_shell)
if "Release" in routes or "Final" in routes:
    errors.append("Phase 17 added a forbidden top-level release route")

for rel in [
    "core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/FinalReleaseGateModels.kt",
    "app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt",
    "app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt",
]:
    data = (root / rel).read_bytes()
    bad = [b for b in data if b < 9 or (13 < b < 32)]
    if bad:
        errors.append(f"control characters found in {rel}")

if errors:
    raise SystemExit("\n".join(errors))
print("Phase 17 final public release gate validation passed")
