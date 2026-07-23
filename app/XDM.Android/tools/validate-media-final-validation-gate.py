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
for phase in range(18, 34):
    if phase not in project_phases:
        errors.append(f"project.implemented_phases missing {phase}")
    if phase >= 26 and phase not in root_phases:
        errors.append(f"root implemented_phases missing {phase}")
if manifest.get("next_phase") != "complete":
    errors.append("PROJECT_MANIFEST next_phase must be complete for Phase 33")
if manifest.get("media_final_validation_gate", {}).get("validation_reenabled") is not True:
    errors.append("media_final_validation_gate.validation_reenabled must be true")
for key in ["warning_zero_gate", "known_kotlin_trap_scan", "termux_chroot_strip_protection", "no_cookie_header_token_persistence"]:
    if manifest.get("media_final_validation_gate", {}).get(key) is not True:
        errors.append(f"media_final_validation_gate.{key} must be true")
if manifest.get("media_final_validation_gate", {}).get("top_level_route_added") is not False:
    errors.append("Phase 33 must not add a top-level route")
if manifest.get("media_final_validation_gate", {}).get("room_schema_migration") is not False:
    errors.append("Phase 33 must not add a Room migration")
if manifest.get("database", {}).get("version") != 14:
    errors.append("Phase 33 must retain Room schema 14")

required_tokens = [
    ("docs/architecture/PHASE-33-MEDIA-FINAL-VALIDATION-GATE.md", "Media Final Validation Gate"),
    ("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaFinalValidationGate.kt", "MediaFinalValidationGatePlanner"),
    ("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaFinalValidationGate.kt", "DefaultGradleCommand"),
    ("app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt", "Media final validation gate"),
    ("app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt", "Phase 33 re-enables validation"),
    ("app/src/test/kotlin/com/mikeyphw/xdm/android/ArchitectureContractTest.kt", "mediaFinalValidationGateContractsArePresent"),
    ("media/src/test/kotlin/com/mikeyphw/xdm/android/media/MediaFinalValidationGateTest.kt", "finalGateDashboardBlocksLeaksAndRequiresGradleValidation"),
    ("tools/run-final-release-gate.sh", "validate-media-final-validation-gate.py"),
    (".github/workflows/android.yml", "validate-media-final-validation-gate.py"),
]
for rel, token in required_tokens:
    require(rel, token)

build = text("app/build.gradle.kts")
if 'warningsAsErrors = true' not in build:
    errors.append("lint warningsAsErrors must remain true")
if 'jniLibs.keepDebugSymbols += "**/*.so"' not in build:
    errors.append("Termux/chroot llvm-strip protection is missing")
version_code = re.search(r"versionCode\s*=\s*(\d+)", build)
if not version_code or int(version_code.group(1)) < 21:
    errors.append("versionCode must advance for Phase 33")
if 'versionName = "0.20.0-rc08"' not in build:
    errors.append("versionName must be 0.20.0-rc08 for Phase 33")

run_gate = text("tools/run-final-release-gate.sh")
for validator in [
    "validate-browser-media-downloader.py",
    "validate-browser-media-continuity.py",
    "validate-media-resolver-player.py",
    "validate-media-execution-library.py",
    "validate-media-download-engine-hardening.py",
    "validate-media-dispatch-control-tower.py",
    "validate-media-queue-telemetry.py",
    "validate-media-queue-actions.py",
    "validate-media-worker-bridge.py",
    "validate-media-termux-runtime-adapter.py",
    "validate-media-native-direct-download-engine.py",
    "validate-media-offline-library-v2.py",
    "validate-media-player-diagnostics.py",
    "validate-media-browser-capture-quality.py",
    "validate-media-session-privacy-audit.py",
    "validate-media-mobile-polish.py",
    "validate-media-final-validation-gate.py",
]:
    if validator not in run_gate:
        errors.append(f"run-final-release-gate.sh missing {validator}")
if "lintDebug" not in run_gate or ":media:test" not in run_gate or "assembleBeta" not in run_gate:
    errors.append("run-final-release-gate.sh must document the full Gradle gate")

screens = text("app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt")
for forbidden in ['label = "Validation"', 'label = "Final"', 'label = "Release"', 'label = "Media Gate"']:
    if forbidden in screens:
        errors.append(f"forbidden top-level route label: {forbidden}")

dispatcher = text("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaExecutionDispatcher.kt")
if "Direct native compatible" not in dispatcher or "MediaExecutionLane.Aria2Segmented.label" not in dispatcher:
    errors.append("dispatch dashboard summary must keep aria2/direct transfers discoverable as Direct native compatible")

capture_quality = text("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaBrowserCaptureQuality.kt")
duplicate_marker = "duplicateOf != null -> CaptureQualityDisposition.GroupWithExisting"
live_marker = "signals.contains(CaptureQualitySignal.Live) -> CaptureQualityDisposition.LiveReview"
if duplicate_marker not in capture_quality or live_marker not in capture_quality or capture_quality.index(duplicate_marker) > capture_quality.index(live_marker):
    errors.append("capture quality must group duplicate live captures before live-review disposition")

mobile_polish = text("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaMobilePolish.kt")
if 'Regex("authorization", RegexOption.IGNORE_CASE)' in mobile_polish:
    errors.append("mobile polish must not treat the safe label 'authorization headers' as a secret leak")
if r"Authorization\s*[:=](?!\s*<redacted" not in mobile_polish or "secret-(?!(?:safe|bearing|free)" not in mobile_polish:
    errors.append("mobile polish must use status-label-safe redaction patterns")

if "bearer tokens" in mobile_polish.lower():
    errors.append("mobile polish prose must not contain 'bearer tokens'; broad Bearer scanners treat it like a credential")
if "Bearer\\s+(?!<redacted>)[A-Za-z0-9._~+/=-]+" in mobile_polish or "Bearer\\s+(?!<redacted>)[A-Za-z0-9._~+/=-]+" in mobile_polish:
    errors.append("mobile polish must not use a prose-hostile broad Bearer scanner")
if "Bearer\\s+(?!<redacted(?:-[A-Za-z]+)?>)(?:secret-[A-Za-z0-9._-]+|[A-Za-z0-9._~+/=-]{16,})" not in mobile_polish:
    errors.append("mobile polish must keep a prose-safe Bearer scanner that still blocks secret-token and long bearer values")


architecture_contract = text("app/src/test/kotlin/com/mikeyphw/xdm/android/ArchitectureContractTest.kt")
if "(?:alpha01|rc01)" in architecture_contract:
    errors.append("ArchitectureContractTest versionName regex must accept rc\\d+ builds such as 0.20.0-rc08")
if 'it.label == "Player" || it.label == "Diagnostics" || it.label == "Playback"' in architecture_contract:
    errors.append("ArchitectureContractTest must not treat the existing Diagnostics route as a Phase 29 top-level route addition")

# Catch known overlay-breaker patterns only in new media final gate code and key media planners.
scan_files = [
    "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaFinalValidationGate.kt",
    "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaMobilePolish.kt",
    "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaNativeDirectDownloadEngine.kt",
    "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaTermuxRuntimeAdapter.kt",
]
for rel in scan_files:
    body = text(rel)
    for bad in ["toBooleanStrictOrNull", "!!", "addJavascriptInterface", "raw shell exposed"]:
        if bad in body:
            errors.append(f"{rel} contains forbidden trap token {bad!r}")
    if re.search(r"count\([A-Za-z0-9_]+::", body):
        errors.append(f"{rel} contains callable-reference count helper")


redaction_files = [
    "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaExecutionLibrary.kt",
    "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaExecutionDispatcher.kt",
    "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaMobilePolish.kt",
    "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaQueueTelemetry.kt",
    "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaQueueActions.kt",
    "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaWorkerBridge.kt",
    "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaTermuxRuntimeAdapter.kt",
    "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaNativeDirectDownloadEngine.kt",
    "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaOfflineLibraryV2.kt",
    "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaPlayerDiagnostics.kt",
    "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaBrowserCaptureQuality.kt",
    "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaSessionPrivacyAudit.kt",
]
for rel in redaction_files:
    body = text(rel)
    if 'Regex("secret-[A-Za-z0-9._-]+", RegexOption.IGNORE_CASE)' in body:
        errors.append(f"{rel} uses broad secret literal regex that blocks secret-safe status labels")
    if 'secret-(?!(?:safe|bearing|free)' not in body and 'MediaExecutionDispatcher.kt' not in rel:
        errors.append(f"{rel} missing status-label-safe secret literal regex")

    if 'Regex("Cookie\\s*:", RegexOption.IGNORE_CASE)' in body:
        errors.append(f"{rel} uses cookie-label-only regex that flags Cookie: <redacted-cookie>")
    if 'Cookie\\s*[:=]\\s*[^\\n;]+' in body or 'Cookie\\s*[:=]\\s*[^\n;]+' in body:
        errors.append(f"{rel} uses cookie regex without redacted-value lookahead")
    if 'save-session' in body and '(?<![-A-Za-z])' not in body and 'MediaExecutionDispatcher.kt' not in rel:
        errors.append(f"{rel} can flag aria2 save-session as an unredacted session token")

secret_surfaces = [
    text("docs/architecture/PHASE-33-MEDIA-FINAL-VALIDATION-GATE.md"),
    text("tools/run-final-release-gate.sh"),
]
combined = "\n".join(secret_surfaces).lower()
for token in ["bearer abc", "secret=plain", "token=plain"]:
    if token in combined:
        errors.append(f"secret-like literal leaked in final gate docs/runbook: {token}")

if errors:
    raise SystemExit("Phase 33 media final validation gate failed:\n" + "\n".join(errors))
print("Phase 33 media final validation gate validation passed")
