#!/usr/bin/env python3
from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REPO = ROOT.parent.parent


def text(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(f"Phase 13 final-gate validation failed: {message}")


build = text("app/build.gradle.kts")
view_model = text("app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt")
developer = text("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/developer/DeveloperToolsScreen.kt")
gate = text("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaFinalValidationGate.kt")
privacy = text("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaSessionPrivacyAudit.kt")
quality = text("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaCaptureQuality.kt")
planner = text("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaDownloadPlanner.kt")
player = text("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaPlayerDiagnostics.kt")
execution = text("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaExecutionLibrary.kt")
handoff = text("browser-extension/src/main/extension/xdm-firefox/handoff.js")
bridge = text("browser-extension/src/main/extension/xdm-firefox/frame-bridge.js")
bridge_test = text("browser-extension/tests/test_phase43a_bridge.js")
final_script = text("tools/run-final-release-gate.sh")
publication_script = text("tools/run-bug-hunt-phase10-release-gate.sh")
common_validation_script = text("tools/run-final-common-validation.sh")
devtool = (REPO / ".devtool.toml").read_text(encoding="utf-8")
workflow = (REPO / ".github/workflows/android.yml").read_text(encoding="utf-8")
manifest = json.loads(text("PROJECT_MANIFEST.json"))

# M-036: readiness must consume explicit, fail-closed validation evidence.
for marker in (
    'validationEvidence("xdm.validation.staticPassed")',
    'validationEvidence("xdm.validation.fullPassed")',
    'buildConfigField("Boolean", "XDM_STATIC_VALIDATION_PASSED"',
    'buildConfigField("Boolean", "XDM_FULL_VALIDATION_PASSED"',
):
    require(marker in build, f"missing fail-closed BuildConfig evidence marker: {marker}")
require(".orElse(false)" in build, "validation evidence must default false")
for marker in ("BuildConfig.XDM_STATIC_VALIDATION_PASSED", "BuildConfig.XDM_FULL_VALIDATION_PASSED"):
    require(marker in view_model, f"runtime readiness does not consume {marker}")
for forbidden in (
    "staticValidatorsComplete = true",
    "fullValidationPassed = true",
    "releaseSafetyReady = true",
    "installUpdateReady = true",
):
    require(forbidden not in view_model, f"runtime still self-certifies readiness via {forbidden}")
for forbidden in (
    "aria2PayloadGateRetained = true",
    "updateKeepsPackageIdentity = true",
):
    require(forbidden not in view_model, f"install/update readiness still self-certifies via {forbidden}")
require("keepDebugSymbolsProtected = BuildConfig.XDM_STATIC_VALIDATION_PASSED" in developer, "Developer Tools still self-certifies debug-symbol protection")
require("warningsAsErrors = BuildConfig.XDM_STATIC_VALIDATION_PASSED" in developer, "Developer Tools still self-certifies warning policy")
require("staticValidationPassed: Boolean = false" in gate, "media static evidence must default false")
require("fullValidationPassed: Boolean = false" in gate, "media full evidence must default false")
require("val releaseReady: Boolean" in gate and "releaseReady = ready" in gate, "media gate must expose release readiness only after evidence")
require('FinalOverlayArtifact: String = "xdm_android_privacy_quality_final_gate_overlay_v2.zip"' in gate, "final overlay identity is stale")
require("CurrentRoomSchema: Int = 20" in gate, "final media gate does not use Room schema 20")

# M-046: inspect bounded real filesystem surfaces and report measurable coverage.
for marker in (
    "scannedFilesystemRootCount",
    "scannedFilesystemFileCount",
    "filesystemCoverageIssueCount",
    "filesystemCoverageComplete",
    "coverageComplete = coverageIssues == 0",
    "MAX_FILESYSTEM_FILE_SIZE = 256L * 1024L",
    "findings.map { it.surface }.distinct().size",
    "MAX_FILESYSTEM_FILES = 256",
    "MAX_FILESYSTEM_DEPTH = 5",
    "MAX_FILESYSTEM_BYTES = 128 * 1024",
):
    require(marker in privacy, f"real-filesystem privacy evidence missing: {marker}")
for root_name in (
    "secure-request-envelopes-v1",
    "browser-capture-import-journal",
    "browser-capture-session-index",
    "queue-scheduling-recovery",
):
    require(root_name in developer, f"Developer Tools does not audit filesystem root {root_name}")
require("RequiredFilesystemRoots: Int = 4" in gate, "final gate does not enforce all four private roots")
require("privacyAudit.filesystemCoverageComplete" in gate, "final gate does not consume filesystem coverage")
require("privacyAudit.filesystemCoverageIssueCount == 0" in gate, "final gate does not fail closed on filesystem coverage issues")

# M-055: exact request identity quality grouping and structured failure classification.
require('MessageDigest.getInstance("SHA-256")' in quality, "capture grouping is not SHA-256 exact-request based")
require("capture.sourceUrl.toByteArray" in quality and '"request=$requestFingerprint"' in quality, "capture grouping does not bind exact source URL")
require("substringBeforeLast('/')" not in quality, "quality grouping still collapses requests by parent path")
require("ExternalUrlPolicy.hasCredentialBearingQuery(capture.sourceUrl)" in planner, "media planner does not use central credential-query policy")
require("structuredProtectionMarker" in planner, "media planner does not use structured protection markers")
require('resolverReport = null' in planner, "display/resolver free text still participates in protection authority")
require('displayLabel.contains("protected"' not in planner, "display labels still classify protected media")
require('it.contains("widevine"' not in planner, "arbitrary widevine substring matching remains in protection classifier")
for marker in ("error.errorCodeName", "error.causeClassName", "code in NETWORK_ERROR_CODES", "code in DRM_ERROR_CODES"):
    require(marker in player, f"player diagnostics lack structured marker {marker}")
require("download?.state == DownloadState.Failed && download.backend == BackendType.Aria2" in execution, "execution failure classification is not backend/state based")
require("plan.strategy == MediaDownloadStrategy.YtDlp" in execution, "execution failure classification is not strategy based")
require("errorMessage?.contains(" not in execution, "execution classification still parses error-message substrings")

# Final browser-handoff harmony: direct Add compatibility is distinct from encrypted media capture.
require("function buildXdmAdd" in handoff and '`${scheme}://add?${params.toString()}`' in handoff, "browser direct-add compatibility route is missing")
require("function buildXdmCapture(_input = {})" in handoff, "plaintext capture compatibility stub is missing")
require("allowDirectAdd = Boolean(input.manualAdd || isProbe)" in bridge, "manual/page/probe direct-add boundary is missing")
require('input.prebuiltXdmLink || (allowDirectAdd ? builtLinks.xdm : "")' in bridge, "detected media is not separated from direct-add handoff")
require("Secure XDM capture handoff is unavailable; plaintext fallback is disabled." in bridge, "detected media no longer fails closed without encrypted capture")
require("automatic media offer without encrypted XDM handoff must fail closed" in bridge_test, "browser bridge regression test does not lock encrypted-media fail-closed behavior")
require("prebuiltXdmLink: secureCaptureLink" in bridge_test, "browser bridge regression test does not exercise encrypted-v2 media rendering")

# M-048/M-001: final gate is executable and part of non-deferred Devtool validation.
require('tasks.register<Exec>("finalRemediationStaticGate")' in build, "Gradle final static-gate task missing")
require('commandLine("bash", "tools/run-final-release-gate.sh", "--ci")' in build, "Gradle task does not execute final static gate")
for validator in (
    "validate-remediation-phase13-final-gate.py",
    "validate-debug-workbench-d7-final-debug-seal.py",
    "validate-media-final-validation-gate.py",
    "validate-uix-r6-accessibility-performance-release-seal.py",
):
    require(validator in final_script, f"final static gate omits {validator}")
for task in (
    '":app:finalRemediationStaticGate"',
    '":browser-extension:test"',
    '":browser-extension:jsTest"',
    '":browser-extension:validateFirefoxExtension"',
    '"lintDebug"',
):
    require(task in devtool, f"Devtool final validation configuration omits {task}")

for task in (
    '":core-model:test"',
    '":media:test"',
    '":persistence:testDebugUnitTest"',
    '"assembleDebug"',
    '":app:assembleDebugAndroidTest"',
):
    require(task in devtool, f"Devtool final common/build validation omits {task}")
require("bash tools/run-final-release-gate.sh --ci" in publication_script, "signed publication path bypasses Overlay-13 final static gate")
require("bash tools/run-final-common-validation.sh" in publication_script, "signed publication path bypasses Overlay-13 common validation")
require("bash tools/run-final-common-validation.sh" in final_script, "final runbook does not use Overlay-13 common validation")
for marker in (":app:compileDebugKotlin", ":core-model:test", ":media:test", ":app:testDebugUnitTest", ":app:lintDebug", "assembleDebug", ":app:assembleDebugAndroidTest", ":browser-extension:validateFirefoxExtension"):
    require(marker in common_validation_script, f"Overlay-13 common validation omits {marker}")
require('XDM_ARIA2_ARCHIVE_SHA256: ${{ secrets.XDM_ARIA2_ARCHIVE_SHA256 }}' in workflow, "signed CI does not expose the pinned aria2 digest")
require('Install pinned official ARM64 aria2 runtime' in workflow and '--require-trusted-digest' in workflow, "signed CI installs aria2 before pin verification")
require('-Pxdm.validation.aria2PayloadVerified=true' in publication_script, "signed release build does not carry earned aria2 verification evidence")
require('verify-aria2-runtime.py --require-payload --require-16kb-alignment --require-trusted-archive-digest' in publication_script, "signed release does not verify pinned aria2 before compile")
require(':browser-extension:packageFirefoxExtensionDark' in publication_script and ':browser-extension:packageFirefoxExtensionAmoled' in publication_script and ':browser-extension:verifyFirefoxExtensionReleaseArtifacts' in publication_script, "signed publication does not enforce key-bound Firefox release artifacts")
for marker in (
    'XDM_CAPTURE_KEY_ID: ${{ secrets.XDM_CAPTURE_KEY_ID }}',
    'XDM_CAPTURE_PUBLIC_KEY_SPKI: ${{ secrets.XDM_CAPTURE_PUBLIC_KEY_SPKI }}',
    'XDM_CAPTURE_OAEP_HASH: ${{ secrets.XDM_CAPTURE_OAEP_HASH }}',
):
    require(marker in workflow, f"signed CI does not expose browser capture release input {marker.split(':', 1)[0]}")
require('Require browser capture release key inputs' in workflow, "signed CI does not fail closed when browser capture release keys are absent")
for forbidden in (':browser-extension:packageFirefoxExtensionDark', ':browser-extension:packageFirefoxExtensionAmoled', ':browser-extension:verifyFirefoxExtensionReleaseArtifacts'):
    require(forbidden not in devtool, f"ordinary Devtool validation incorrectly requires key-bound browser release task {forbidden}")
    require(forbidden not in common_validation_script, f"keyless common validation incorrectly requires key-bound browser release task {forbidden}")

require(manifest.get("current_overlay") == "xdm_android_privacy_quality_final_gate_overlay_v2.zip", "PROJECT_MANIFEST current_overlay is not Overlay 13")
database = manifest.get("database", {})
require(database.get("version") == 20, "PROJECT_MANIFEST authoritative database version is not 20")
require("18_to_19" in database.get("migrations", []) and "19_to_20" in database.get("migrations", []), "PROJECT_MANIFEST migration chain does not reach schema 20")
public_gate = manifest.get("final_public_release_gate", {})
require(public_gate.get("room_schema_locked") == 20, "PROJECT_MANIFEST final public gate still claims an old Room schema")
require(public_gate.get("version_code") == 22 and public_gate.get("version_name") == "0.21.0", "PROJECT_MANIFEST final public gate version metadata is stale")
require(public_gate.get("readiness_evidence_fail_closed") is True, "PROJECT_MANIFEST final public gate does not record fail-closed evidence")
require(public_gate.get("install_update_ready") is False, "PROJECT_MANIFEST must not pre-certify install/update readiness")
overlay13 = manifest.get("bug_hunt_master_remediation_overlay_13_privacy_quality_final_gate", {})
required_overlay_tasks = set(overlay13.get("required_final_validation_tasks", []))
for task in (":core-model:test", ":media:test", ":app:testDebugUnitTest", ":app:lintDebug", "assembleDebug", ":app:assembleDebugAndroidTest", ":browser-extension:validateFirefoxExtension"):
    require(task in required_overlay_tasks, f"Overlay-13 manifest omits final required task {task}")
media_gate = manifest.get("media_final_validation_gate", {})
for key in (
    "static_validation_evidence_required",
    "full_validation_evidence_required",
    "validation_evidence_defaults_false",
    "real_filesystem_privacy_coverage_required",
    "exact_request_identity_quality_grouping",
    "structured_failure_classification_required",
):
    require(media_gate.get(key) is True, f"PROJECT_MANIFEST final-gate promise missing: {key}")

require("Overlay 13 is the final remediation gate" in developer, "Developer Tools still describes the obsolete Phase-33 gate")
require("ready for full validation" not in developer, "UI still labels already-validated release evidence as merely ready for validation")

print("Phase 13 privacy/quality/final-gate contract passed")
