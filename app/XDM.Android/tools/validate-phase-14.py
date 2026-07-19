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
release = manifest.get("release_security_hardening", {})
if project.get("version") != "0.14.0-alpha01":
    errors.append("PROJECT_MANIFEST project.version is not 0.14.0-alpha01")
if 14 not in project.get("implemented_phases", []):
    errors.append("PROJECT_MANIFEST is missing implemented phase 14")
if database.get("version") != 13:
    errors.append("Phase 14 must keep database.version at 13")
if manifest.get("next_phase") != "15":
    errors.append("PROJECT_MANIFEST next_phase is not 15")
for key in ["privacy_safe_diagnostics", "redacted_diagnostic_summary", "release_gate_script", "beta_build_type_retained"]:
    if release.get(key) is not True:
        errors.append(f"release_security_hardening.{key} is not true")
if release.get("schema_version_unchanged") != 13:
    errors.append("release_security_hardening.schema_version_unchanged is not 13")
if release.get("top_level_route_added") is not False:
    errors.append("Phase 14 must not add a top-level route")

build_gradle = require_file("app/build.gradle.kts")
for needle in [
    "versionCode = 15",
    'versionName = "0.14.0-alpha01"',
    'create("beta")',
    'signingConfigs',
    'warningsAsErrors = true',
]:
    if needle not in build_gradle:
        errors.append(f"app/build.gradle.kts missing {needle!r}")
if re.search(r'getByName\("release"\)\s*\{[^}]*isDebuggable\s*=\s*true', build_gradle, re.S):
    errors.append("release build type must not be debuggable")

require_text("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/ReleaseSecurityModels.kt", "PrivacyDiagnosticsRedactor")
require_text("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/ReleaseSecurityModels.kt", "ReleaseSecurityGate")
require_text("core-model/src/test/kotlin/com/mikeyphw/xdm/android/model/ReleaseSecurityModelsTest.kt", "redactsBearerTokensSensitiveHeadersAndQuerySecrets")
require_text("app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt", "Release safety")
require_text("app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt", "clipboard.setText")
require_text("docs/architecture/PHASE-14-RELEASE-SAFETY.md", "Room at schema v13")
require_text("tools/validate-phase-13.py", "database.version")

schema_path = root / "persistence/schemas/com.mikeyphw.xdm.android.persistence.AppDatabase/13.json"
if not schema_path.is_file():
    errors.append("Room schema 13.json is missing")
else:
    schema = json.loads(schema_path.read_text(encoding="utf-8"))
    if "version" in schema:
        errors.append("Room schema has unsupported top-level version key")
    if schema.get("database", {}).get("version") != 13:
        errors.append("Room schema database.version is not 13")

for rel in [
    "core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/ReleaseSecurityModels.kt",
    "app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt",
    "app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt",
]:
    data = (root / rel).read_bytes()
    bad = [b for b in data if b < 9 or (13 < b < 32)]
    if bad:
        errors.append(f"control characters found in {rel}")

if errors:
    raise SystemExit("\n".join(errors))
print("Phase 14 release safety validation passed")
