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
for phase in range(18, 36):
    if phase not in project_phases:
        errors.append(f"project.implemented_phases missing {phase}")
for phase in range(26, 36):
    if phase not in root_phases:
        errors.append(f"root implemented_phases missing {phase}")

if manifest.get("next_phase") != "complete":
    errors.append("PROJECT_MANIFEST next_phase must remain complete for release-candidate polish")
if manifest.get("current_overlay") not in {"xdm_android_phase35_release_candidate_polish_overlay.zip", "xdm_android_phase36_external_download_handoff_overlay.zip"}:
    errors.append("current_overlay must point at Phase 35 or a later Phase 36 overlay")

phase34 = manifest.get("phase34_release_handoff", {})
if phase34.get("phase33_landed") is not True:
    errors.append("Phase 35 must carry forward the landed Phase 33 handoff")
validation = phase34.get("devtool_validation", {})
if validation.get("tests_passed") != 149 or validation.get("tests_failed") != 0 or validation.get("tests_skipped") != 0:
    errors.append("Phase 35 must preserve Phase 33 devtool test results")
if validation.get("diagnostic_warnings") != 0 or validation.get("diagnostic_errors") != 0:
    errors.append("Phase 35 must preserve zero Phase 33 diagnostics")

polish = manifest.get("phase35_release_candidate_polish", {})
expected_true = [
    "phase34_landed",
    "release_candidate_polish",
    "run_final_release_gate_included",
    "ci_static_contract_included",
    "phase33_regression_guards_locked",
    "phase34_handoff_locked",
]
for key in expected_true:
    if polish.get(key) is not True:
        errors.append(f"phase35_release_candidate_polish.{key} must be true")
for key in ["raw_shell_exposed", "root_required", "room_schema_migration", "top_level_route_added"]:
    if polish.get(key) is not False:
        errors.append(f"phase35_release_candidate_polish.{key} must be false")

if polish.get("version_name_unchanged") != "0.20.0-rc08" or polish.get("version_code_unchanged") != 21:
    errors.append("Phase 35 must not bump version metadata")
if polish.get("package_id_locked") != "com.mikeyphw.xdm.android":
    errors.append("Phase 35 must keep package identity locked")

required_docs = {"prepare-app-for-release", "core-app-quality", "user-initiated-data-transfer"}
if set(polish.get("android_release_docs_reviewed", [])) != required_docs:
    errors.append("Phase 35 must record reviewed Android release docs")

checklist = set(polish.get("release_readiness_checklist", []))
for item in [
    "package_identity_locked",
    "version_metadata_locked",
    "debug_and_beta_suffixes_isolated",
    "signed_release_required",
    "artifact_checksum_required",
    "aria2_payload_required_for_publishable_artifacts",
    "uidt_user_visible_transfer_required",
    "latest_android_phone_tablet_foldable_smoke_required",
    "zero_warning_static_gate_required",
    "privacy_redaction_gate_required",
]:
    if item not in checklist:
        errors.append(f"Phase 35 checklist missing {item}")

gate = polish.get("ship_no_ship_gate", {})
for key in [
    "ship_requires_static_validators",
    "ship_requires_gradle_build_lint_tests",
    "ship_requires_signed_artifacts",
    "ship_requires_checksums",
    "ship_requires_aria2_payload_verification",
    "no_ship_on_raw_secret_leak",
    "no_ship_on_new_top_level_route",
    "no_ship_on_room_schema_drift",
]:
    if gate.get(key) is not True:
        errors.append(f"ship_no_ship_gate.{key} must be true")

required_tokens = [
    ("docs/architecture/PHASE-35-RELEASE-CANDIDATE-POLISH.md", "Phase 35: Release Candidate Polish"),
    ("docs/architecture/PHASE-35-RELEASE-CANDIDATE-POLISH.md", "Ship/no-ship gate"),
    ("docs/architecture/PHASE-35-RELEASE-CANDIDATE-POLISH.md", "149 passed, 0 failed, 0 skipped"),
    ("docs/architecture/PHASE-35-RELEASE-CANDIDATE-POLISH.md", "No-ship is required"),
    ("tools/validate-phase-35-release-candidate-polish.py", "phase35_release_candidate_polish"),
    ("tools/run-final-release-gate.sh", "validate-phase-35-release-candidate-polish.py"),
    (".github/workflows/android.yml", "validate-phase-35-release-candidate-polish.py"),
    ("app/src/test/kotlin/com/mikeyphw/xdm/android/ArchitectureContractTest.kt", "phaseThirtyFiveReleaseCandidatePolishContractsArePresent"),
]
for rel, token in required_tokens:
    require(rel, token)

build = text("app/build.gradle.kts")
if 'applicationId = "com.mikeyphw.xdm.android"' not in build:
    errors.append("applicationId must remain stable")
if 'applicationIdSuffix = ".debug"' not in build or 'applicationIdSuffix = ".beta"' not in build:
    errors.append("debug and beta application suffixes must stay isolated")
if 'versionName = "0.20.0-rc08"' not in build:
    errors.append("Phase 35 must not bump versionName away from the Phase 33/34 rc")
if not re.search(r"versionCode\s*=\s*21\b", build):
    errors.append("Phase 35 must not bump versionCode away from 21")
if 'warningsAsErrors = true' not in build:
    errors.append("lint warningsAsErrors must remain true")
if 'jniLibs.keepDebugSymbols += "**/*.so"' not in build:
    errors.append("Termux/chroot llvm-strip protection must remain in packaging options")
if 'signingConfigs.getByName("release")' not in build or "hasReleaseSigning" not in build:
    errors.append("release signing gate must remain configured")

release_helper = text("tools/build-release-artifacts.sh")
if "sha256sum" not in release_helper or "assembleRelease" not in release_helper or "assembleBeta" not in release_helper:
    errors.append("release helper must build beta/release artifacts and write sha256 checksums")

workflow = text(".github/workflows/android.yml")
for token in ["validate-phase-35-release-candidate-polish.py", "install-aria2-runtime.py --download-official", "verify-aria2-runtime.py --require-payload --require-16kb-alignment", "bash -n tools/build-release-artifacts.sh"]:
    if token not in workflow:
        errors.append(f"Android CI missing {token}")

run_gate = text("tools/run-final-release-gate.sh")
if "validate-phase-35-release-candidate-polish.py" not in run_gate:
    errors.append("final release gate must include Phase 35 validator")
if "xdm_android_phase35_release_candidate_polish_overlay.zip" not in run_gate and "xdm_android_phase36_external_download_handoff_overlay.zip" not in run_gate:
    errors.append("final release gate must point at Phase 35 or later Phase 36 overlay")

screens = text("app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt")
for forbidden in ['label = "Release Candidate"', 'label = "Ship"', 'label = "No Ship"', 'label = "Checklist"']:
    if forbidden in screens:
        errors.append(f"forbidden Phase 35 top-level route label: {forbidden}")

architecture_contract = text("app/src/test/kotlin/com/mikeyphw/xdm/android/ArchitectureContractTest.kt")
if "phaseThirtyFourReleaseHandoffContractsArePresent" not in architecture_contract:
    errors.append("Phase 34 architecture contract must stay present")
if "xdm_android_phase35_release_candidate_polish_overlay.zip" not in architecture_contract or "xdm_android_phase36_external_download_handoff_overlay.zip" not in architecture_contract:
    errors.append("ArchitectureContractTest must allow Phase 35 and later Phase 36 current_overlay literals")
for bad_literal in ['contains(""current_overlay"', 'contains(""phase35_release_candidate_polish"']:
    if bad_literal in architecture_contract:
        errors.append(f"ArchitectureContractTest contains unescaped JSON assertion literal: {bad_literal}")

if 'buildGradle.contains("versionName = "0.20.0-rc08"")' in architecture_contract:
    errors.append("ArchitectureContractTest contains unescaped versionName assertion literal")
if 'buildGradle.contains("versionName = \\\"0.20.0-rc08\\\"")' not in architecture_contract:
    errors.append("ArchitectureContractTest must keep escaped Phase 35 versionName literal")

# Preserve the Phase 33/34 regression locks while polishing the release candidate.
for rel, token in [
    ("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaMobilePolish.kt", "secret-(?!(?:safe|bearing|free)"),
    ("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaBrowserCaptureQuality.kt", "duplicateOf != null -> CaptureQualityDisposition.GroupWithExisting"),
    ("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaExecutionDispatcher.kt", "Direct native compatible"),
    ("docs/architecture/PHASE-34-STABILIZATION-RELEASE-HANDOFF.md", "Phase 33 is landed and must not be treated as pending"),
]:
    require(rel, token)

if errors:
    raise SystemExit("Phase 35 release-candidate polish validation failed:\n" + "\n".join(errors))
print("Phase 35 release-candidate polish validation passed")
