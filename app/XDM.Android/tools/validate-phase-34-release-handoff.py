#!/usr/bin/env python3
from pathlib import Path
import json
import re

ROOT = Path(__file__).resolve().parents[1]
errors = []


def text(rel: str) -> str:
    path = ROOT / rel
    if not path.is_file():
        errors.append(f"missing file: {rel}")
        return ""
    return path.read_text(encoding="utf-8")


def require(rel: str, token: str) -> None:
    body = text(rel)
    if token not in body:
        errors.append(f"{rel} missing {token!r}")


manifest = json.loads(text("PROJECT_MANIFEST.json") or "{}")
project_phases = manifest.get("project", {}).get("implemented_phases", [])
root_phases = manifest.get("implemented_phases", [])
for phase in range(18, 35):
    if phase not in project_phases:
        errors.append(f"project.implemented_phases missing {phase}")
for phase in range(26, 35):
    if phase not in root_phases:
        errors.append(f"root implemented_phases missing {phase}")

if manifest.get("next_phase") != "complete":
    errors.append("PROJECT_MANIFEST next_phase must remain complete after the release handoff")
if manifest.get("current_overlay") not in {"xdm_android_phase34_stabilization_release_handoff_overlay.zip", "xdm_android_phase35_release_candidate_polish_overlay.zip", "xdm_android_phase36_external_download_handoff_overlay.zip", "xdm_android_phase37a_browser_downloader_roadmap_overlay.zip", "xdm_android_phase37b_dual_launcher_navigation_split_overlay.zip"}:
    errors.append("current_overlay must point at the Phase 34 handoff overlay or a later approved Phase 35/36 overlay")

handoff = manifest.get("phase34_release_handoff", {})
expected_booleans = [
    "phase33_landed",
    "run_final_release_gate_included",
    "ci_static_contract_included",
]
for key in expected_booleans:
    if handoff.get(key) is not True:
        errors.append(f"phase34_release_handoff.{key} must be true")
for key in ["raw_shell_exposed", "root_required", "room_schema_migration", "top_level_route_added"]:
    if handoff.get(key) is not False:
        errors.append(f"phase34_release_handoff.{key} must be false")

validation = handoff.get("devtool_validation", {})
if validation.get("tests_passed") != 149 or validation.get("tests_failed") != 0:
    errors.append("Phase 34 handoff must record the passing Phase 33 devtool test result")
if validation.get("diagnostic_warnings") != 0 or validation.get("diagnostic_errors") != 0:
    errors.append("Phase 34 handoff must record zero Phase 33 diagnostics")

required_tokens = [
    ("docs/architecture/PHASE-33-MEDIA-FINAL-VALIDATION-GATE.md", "Media Final Validation Gate"),
    ("docs/architecture/PHASE-34-STABILIZATION-RELEASE-HANDOFF.md", "Phase 34: Stabilization Release Handoff"),
    ("docs/architecture/PHASE-34-STABILIZATION-RELEASE-HANDOFF.md", "Phase 33 is landed and must not be treated as pending"),
    ("tools/validate-phase-34-release-handoff.py", "phase34_release_handoff"),
    ("tools/run-final-release-gate.sh", "validate-phase-34-release-handoff.py"),
    (".github/workflows/android.yml", "validate-phase-34-release-handoff.py"),
    ("app/src/test/kotlin/com/mikeyphw/xdm/android/ArchitectureContractTest.kt", "phaseThirtyFourReleaseHandoffContractsArePresent"),
]
for rel, token in required_tokens:
    require(rel, token)

build = text("app/build.gradle.kts")
if 'versionName = "0.20.0-rc08"' not in build:
    errors.append("Phase 34 must not bump versionName away from the landed Phase 33 rc")
if not re.search(r"versionCode\s*=\s*21\b", build):
    errors.append("Phase 34 must not bump versionCode away from 21")
if 'warningsAsErrors = true' not in build:
    errors.append("lint warningsAsErrors must remain true")
if 'jniLibs.keepDebugSymbols += "**/*.so"' not in build:
    errors.append("Termux/chroot llvm-strip protection must remain in packaging options")

screens = text("app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt")
for forbidden in ['label = "Handoff"', 'label = "Release Handoff"', 'label = "Stabilization"']:
    if forbidden in screens:
        errors.append(f"forbidden Phase 34 top-level route label: {forbidden}")

architecture_contract = text("app/src/test/kotlin/com/mikeyphw/xdm/android/ArchitectureContractTest.kt")
if "(?:alpha01|rc01)" in architecture_contract:
    errors.append("ArchitectureContractTest versionName regex must keep rc\\d+ support")
if 'it.label == "Player" || it.label == "Diagnostics" || it.label == "Playback"' in architecture_contract:
    errors.append("ArchitectureContractTest must not ban the existing Diagnostics route")
if '\\"next_phase\\": \\"complete\\"' not in architecture_contract:
    errors.append("ArchitectureContractTest must keep escaped next_phase contract literal")
for bad_literal in ['contains(""next_phase"', 'contains(""tests_passed"', 'contains(""diagnostic_errors"']:
    if bad_literal in architecture_contract:
        errors.append(f"ArchitectureContractTest contains unescaped JSON assertion literal: {bad_literal}")

capture_quality = text("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaBrowserCaptureQuality.kt")
duplicate_marker = "duplicateOf != null -> CaptureQualityDisposition.GroupWithExisting"
live_marker = "signals.contains(CaptureQualitySignal.Live) -> CaptureQualityDisposition.LiveReview"
if duplicate_marker not in capture_quality or live_marker not in capture_quality or capture_quality.index(duplicate_marker) > capture_quality.index(live_marker):
    errors.append("duplicate live captures must group before live-review disposition")

dispatcher = text("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaExecutionDispatcher.kt")
if "Direct native compatible" not in dispatcher or "MediaExecutionLane.Aria2Segmented.label" not in dispatcher:
    errors.append("direct/aria2 lane summaries must remain direct native compatible")

mobile_polish = text("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaMobilePolish.kt")
if 'Regex("authorization", RegexOption.IGNORE_CASE)' in mobile_polish:
    errors.append("mobile polish must not flag safe authorization prose")
if "bearer tokens" in mobile_polish.lower():
    errors.append("mobile polish prose must not contain bearer-token-shaped wording")
if "secret-(?!(?:safe|bearing|free)" not in mobile_polish:
    errors.append("mobile polish must keep status-label-safe secret scanning")
if "Bearer\\s+(?!<redacted(?:-[A-Za-z]+)?>)(?:secret-[A-Za-z0-9._-]+|[A-Za-z0-9._~+/=-]{16,})" not in mobile_polish:
    errors.append("mobile polish must keep a prose-safe Bearer scanner")

# Do not allow the handoff document itself to reintroduce raw-looking secrets.
handoff_doc = text("docs/architecture/PHASE-34-STABILIZATION-RELEASE-HANDOFF.md").lower()
for token in ["bearer abc", "authorization: abc", "cookie: sid=", "token=plain"]:
    if token in handoff_doc:
        errors.append(f"handoff doc contains raw-looking secret fixture: {token}")

if errors:
    raise SystemExit("Phase 34 release handoff validation failed:\n" + "\n".join(errors))
print("Phase 34 release handoff validation passed")
