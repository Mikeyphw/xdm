#!/usr/bin/env python3
from __future__ import annotations

import json
import re
import sys
from pathlib import Path


def find_root() -> Path:
    cursor = Path.cwd().resolve()
    for candidate in [cursor, *cursor.parents]:
        if (candidate / "settings.gradle.kts").is_file() and (candidate / "PROJECT_MANIFEST.json").is_file():
            return candidate
        nested = candidate / "app" / "XDM.Android"
        if (nested / "settings.gradle.kts").is_file() and (nested / "PROJECT_MANIFEST.json").is_file():
            return nested
    raise SystemExit("Android root not found")

ROOT = find_root()
ERRORS: list[str] = []


def text(relative: str) -> str:
    path = ROOT / relative
    if not path.is_file():
        ERRORS.append(f"Missing required file: {relative}")
        return ""
    return path.read_text(encoding="utf-8", errors="replace")


def require(condition: bool, message: str) -> None:
    if not condition:
        ERRORS.append(message)

manifest = json.loads(text("PROJECT_MANIFEST.json") or "{}")
build = text("app/build.gradle.kts")
database = text("persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/AppDatabase.kt")
final_gate = text("tools/run-final-release-gate.sh")
media_privacy = text("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaSessionPrivacyAudit.kt")
media_quality = text("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaCaptureQuality.kt")
media_final = text("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaFinalValidationGate.kt")
termux_templates = text("app/src/main/kotlin/com/mikeyphw/xdm/android/termux/TermuxShellTemplates.kt") + media_final

media_gate = manifest.get("media_final_validation_gate", {})
require('versionName = "0.21.0"' in build, "media final gate must track current app version 0.21.0")
require(re.search(r"version\s*=\s*18\b", database) is not None, "media final gate must track current Room schema 18")
require(media_gate.get("schema_version_current") in {18, None} or media_gate.get("schema_version_unchanged") == 18, "PROJECT_MANIFEST media gate must be current-schema aware")
require(media_gate.get("validation_reenabled") is True, "PROJECT_MANIFEST media gate must keep validation enabled")
for marker in [
    "MediaFinalValidationGatePlanner",
    "MediaSessionPrivacyAuditPlanner",
    "MediaCaptureQualityPlanner",
]:
    require(marker in (media_final + media_privacy + media_quality), f"missing media final validation marker: {marker}")
for validator in [
    "tools/validate-media-capture-quality.py",
    "tools/validate-media-session-privacy-audit.py",
    "tools/validate-media-final-validation-gate.py",
    "tools/validate-bug-hunt-phase11-validation-matrix.py",
]:
    require(validator in final_gate, f"final release gate missing current validator: {validator}")
require("FULL_GRADLE_GATE" in final_gate and "run-bug-hunt-phase11-validation-matrix.sh" in final_gate, "final release gate must document the full Gradle/device/release matrix")
require("llvm-strip" in termux_templates or "strip" in termux_templates, "Termux/chroot strip-protection marker is missing")

if ERRORS:
    print("Media final validation gate failed:")
    for error in ERRORS:
        print(f"- {error}")
    sys.exit(1)
print("Media final validation gate passed")
