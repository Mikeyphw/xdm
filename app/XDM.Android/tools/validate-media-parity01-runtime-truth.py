#!/usr/bin/env python3
from __future__ import annotations

import json
import re
import sys
from pathlib import Path


def root() -> Path:
    here = Path.cwd().resolve()
    for _ in range(8):
        if (here / "settings.gradle.kts").is_file() and (here / "app/src/main").is_dir():
            return here
        candidate = here / "app" / "XDM.Android"
        if (candidate / "settings.gradle.kts").is_file():
            return candidate
        if here.parent == here:
            break
        here = here.parent
    raise SystemExit("XDM Android root not found")


ROOT = root()
errors: list[str] = []


def read(relative: str) -> str:
    path = ROOT / relative
    if not path.is_file():
        errors.append(f"missing required file: {relative}")
        return ""
    return path.read_text(encoding="utf-8")


def require(condition: bool, message: str) -> None:
    if not condition:
        errors.append(message)


def require_all(text: str, needles: list[str], label: str) -> None:
    for needle in needles:
        require(needle in text, f"{label} missing marker: {needle}")


manifest = json.loads(read("PROJECT_MANIFEST.json") or "{}")
integrity = read("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DiagnosticExportIntegrity.kt")
debug_events = read("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DebugEventModels.kt")
automation = read("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/AutomationModels.kt")
release_security = read("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/ReleaseSecurityModels.kt")
debug_store = read("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/debug/DebugTestStore.kt")
debug_catalog = read("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/debug/DebugTestCatalog.kt")
debug_screen = read("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/debug/DebugCenterScreen.kt")
main_vm = read("app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt")
developer = read("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/developer/DeveloperToolsScreen.kt")
developer_workspace = read("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/developer/DeveloperToolsWorkspace.kt")
activity = read("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/OperationalActivity.kt")
build = read("app/build.gradle.kts")
final_seal = read("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/FinalAndroidDownloaderRcSeal.kt")
readme = read("README.md")
final_gate = read("tools/run-final-release-gate.sh")
release_gate = read("tools/run-bug-hunt-phase10-release-gate.sh")
core_test = read("core-model/src/test/kotlin/com/mikeyphw/xdm/android/model/DiagnosticExportIntegrityTest.kt")
contract_test = read("app/src/test/kotlin/com/mikeyphw/xdm/android/MediaParity01RuntimeTruthContractTest.kt")
shell_model = read("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DebugWorkbenchShellModels.kt")
debug_models = read("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/debug/DebugTestModels.kt")
aria2_test = read("transfer-aria2/src/test/kotlin/com/mikeyphw/xdm/android/transfer/aria2/Aria2ProcessManagerTest.kt")
aria2_manager = read("transfer-aria2/src/main/kotlin/com/mikeyphw/xdm/android/transfer/aria2/Aria2ProcessManager.kt")
aria2_backend = read("transfer-aria2/src/main/kotlin/com/mikeyphw/xdm/android/transfer/aria2/EmbeddedAria2Backend.kt")

expected_overlay = "xdm_media_parity01_runtime_truth_diagnostics_backend_reliability_v2.zip"
successor_overlay = "xdm_media_parity02_logical_media_capture_browser_convergence_v2.zip"
parity03_overlay = "xdm_media_parity03_native_hls_execution_admission_integrity_v2.zip"
phase = manifest.get("media_parity01_runtime_truth_diagnostics_backend_reliability", {})
require(manifest.get("current_overlay") in {expected_overlay, successor_overlay, parity03_overlay}, "PROJECT_MANIFEST current_overlay must point to Media Parity01 or an accepted successor")
require(phase.get("status") == "implemented", "Media Parity01 manifest phase must be implemented")
require(phase.get("room_schema") == 22, "Media Parity01 must report Room schema 22")
require(phase.get("final_zip_privacy_scan_required") is True, "final ZIP privacy scan must be required")
require(phase.get("legacy_firefox_crypto_release_blocker") is False, "legacy Firefox crypto must not be a release blocker")
require(phase.get("current_firefox_capture_contract") == "direct-keyless-v3", "current Firefox contract must be direct-keyless-v3")

require_all(integrity, [
    "object DiagnosticExportIntegrity",
    "writeVerifiedZip",
    "scanZip",
    "diagnostic-manifest.json",
    "isStructurallyValidJsonObject",
    "ambiguous duplicate-style export name",
    "DiagnosticBundleMetadata",
    "testSummary",
    "redactionScanner",
    "JsonStructureParser",
    '"md5"',
    '"sess"',
], "final diagnostic artifact boundary")
require(".take(16 * 1024)" not in debug_events, "debug export lines must not be character-truncated")
require("tailWholeJsonlRecords" in debug_events, "rolling timeline export must select whole JSONL records")
require("DiagnosticExportIntegrity.writeVerifiedZip" in debug_events, "recorder support ZIP must use exact final-artifact verifier")
require_all(automation, ['"md5"', '"sess"', '"hdnea"', '"hdnts"'], "signed-media query policy")
require_all(release_security, ['"md5"', '"sess"'], "privacy diagnostics redactor")

require_all(debug_store, [
    '"xdm-debug-${safeFileName(run.id)}.zip"',
    "DiagnosticExportIntegrity.writeVerifiedZip",
    "Room schema: 24",
    "Live Locator WebView",
    "Diagnostics version: v5 / Media Parity01",
    "DiagnosticBundleMetadata",
    '"bundle-readme.txt"',
    "testSummary = run.summaryLabel",
], "DebugTestStore")
require("(1)" not in debug_store, "DebugTestStore must not generate duplicate-style '(1)' names")
require_all(debug_screen, [
    "Diagnostics export blocked: final ZIP privacy/integrity verification failed.",
    "Diagnostics ZIP verified and ready to share.",
], "Debug Center sharing boundary")

for retired in [
    "FirefoxSecureHandoffDebugTest",
    "FirefoxEncryptedEnvelopeDecodeDebugTest",
    'id = "firefox-secure-handoff"',
    'id = "firefox-encrypted-envelope-decode"',
]:
    require(retired not in debug_catalog, f"retired secure-envelope blocker remains in required Debug Center catalog: {retired}")
require_all(debug_catalog, [
    "FirefoxDirectV3HandoffDebugTest",
    'id = "firefox-direct-v3-handoff"',
    "XdmBrowserDeepLinkParser.parse",
    "manager.smokeTest()",
    "Optional packaged aria2 runtime is unavailable",
    'status = DebugTestStatus.Warning',
    "Native downloads remain independently usable",
    "Final ZIP privacy & integrity",
    '"failureKind"',
    '"exitCode"',
    '"runtimeLogTail"',
], "current runtime diagnostics catalog")

require_all(build, [
    'validationEvidence("xdm.validation.staticPassed")',
    'validationEvidence("xdm.validation.fullPassed")',
    'validationEvidence("xdm.validation.realDeviceSmokePassed")',
    'validationEvidence("xdm.validation.aria2PayloadVerified")',
    'validationEvidence("xdm.validation.diagnosticExportPassed")',
    'validationEvidence("xdm.validation.releaseDocsPassed")',
    'validationEvidence("xdm.validation.routeTopologyPassed")',
    'validationEvidence("xdm.validation.lintPassed")',
    'validationEvidence("xdm.validation.nativeSymbolsPassed")',
    '"XDM_ARIA2_PAYLOAD_GATE_CONFIGURED", "true"',
], "independent BuildConfig validation evidence")
require_all(main_vm, [
    "DiagnosticExportIntegrity.contractSelfTest()",
    "diagnosticsRuntimePrivacyReady && diagnosticsExportValidated",
    "releaseDocsComplete = releaseDocsValidated",
    "noNewTopLevelRoutes = routeTopologyValidated",
    "aria2PayloadGateRetained = BuildConfig.XDM_ARIA2_PAYLOAD_GATE_CONFIGURED",
    "updateKeepsPackageIdentity = packageIdentityStable",
    "Validation evidence (independent facts)",
], "MainViewModel release truth")
require("releaseDocsComplete = staticValidationPassed" not in main_vm, "release docs must not proxy static validation")
require("noNewTopLevelRoutes = staticValidationPassed" not in main_vm, "route topology must not proxy static validation")
require("diagnosticsRedacted = staticValidationPassed" not in main_vm, "diagnostics privacy must not proxy static validation")

require("currentRoomSchemaVersion = 24" in developer, "Developer Center final media validation must use current Room schema 24")
require("currentRoomSchemaVersion = 21" not in developer, "stale Room schema 21 remains in Developer Center")
require_all(developer, [
    "XDM_ROUTE_TOPOLOGY_VALIDATED",
    "XDM_NATIVE_SYMBOLS_VALIDATED",
    "XDM_LINT_VALIDATION_PASSED",
], "Developer media final validation")
require_all(developer_workspace, [
    "Validation truth",
    "Room schema: 24",
    "XDM_DIAGNOSTIC_EXPORT_VALIDATED",
    "XDM_REAL_DEVICE_SMOKE_PASSED",
    "XDM_ARIA2_PAYLOAD_VERIFIED",
], "Developer Center authoritative validation surface")

require("Live Locator WebView capture enabled" in activity, "support topology must report the shipped Live Locator WebView")
require("Product: downloader-only; external browser handoff enabled; built-in browser absent" not in activity, "obsolete downloader-only support topology remains")
require("Browser and media-capture topology truthful" in final_seal, "active final RC seal must use truthful browser/media topology")
require("Browser-free downloader boundary" not in final_seal, "active final RC seal still uses browser-free blocker")
require_all(readme, [
    "Media Parity01 remains the runtime-truth/diagnostics/backend-reliability baseline",
    "Media Parity02 is the current logical-media capture and Firefox/WebView convergence authority",
    "keyless v3 `xdmdownload://capture` contract",
    "historical Phase-7 evidence",
], "README current truth")

require_all(core_test, [
    "exactFinalZipRejectsPurposefullyPlantedSecretMarker",
    "exactFinalZipRejectsMalformedOrTruncatedJsonl",
    "timelineTailNeverStartsOrEndsWithPartialJsonRecord",
    "ambiguousDuplicateStyleFilenameIsRejectedByFinalScanner",
    "manifestCarriesBuildSchemaRunSummaryAndScannerAttestation",
    "strictJsonlGrammarRejectsBalancedButInvalidJson",
], "core diagnostic regression tests")
require_all(contract_test, [
    "legacyFirefoxCryptoTestsAreNotRequiredBlockers",
    "validationFactsAreIndependentAndSchemaTruthIs23",
    "aria2DiagnosticUsesRealLifecycleSmokeAndOptionalUnavailableIsNonFatal",
], "Parity01 app contract tests")
require_all(shell_model, [
    "Verified diagnostics bundle",
    "manifest-hashed",
    "final privacy/integrity attestation",
], "Diagnostics & support authoritative surface")
require_all(debug_models, [
    "exact diagnostics ZIP is redacted, structurally verified, manifest-hashed, and rescanned before sharing",
    "No automatic upload is performed",
], "Debug report privacy wording")
require_all(aria2_test, [
    "processExitBeforeAuthenticatedRpcIsReportedAsRealFailureInsteadOfReady",
    "Aria2StartupFailureKind.ProcessExited",
    "failed.diagnostic?.exitCode",
], "aria2 Xdm.zip ProcessExited regression")
require_all(aria2_manager, [
    "fun effectiveCapability(): Aria2CapabilityReport",
    "managed runtime is unhealthy",
    "Use Repair aria2",
], "aria2 runtime-aware capability")
require(aria2_backend.count("processManager.effectiveCapability()") >= 3, "aria2 backend must use runtime-aware capability for advertise/prepare/reconcile")
require_all(aria2_test, [
    "manager.effectiveCapability()",
    "Aria2Availability.ProbeFailed",
    "effective.summary.contains(\"Repair aria2\")",
], "aria2 failed-runtime capability regression")

validator_literal = "tools/validate-media-parity01-runtime-truth.py"
require(validator_literal in final_gate, "canonical final static gate must carry Media Parity01 validator")
for prop in [
    "xdm.validation.diagnosticExportPassed=true",
    "xdm.validation.releaseDocsPassed=true",
    "xdm.validation.routeTopologyPassed=true",
    "xdm.validation.lintPassed=true",
    "xdm.validation.nativeSymbolsPassed=true",
]:
    require(prop in release_gate, f"signed release gate must attest earned independent evidence: {prop}")

# Current source truth: successor source diagnostics say schema 24 while Parity01 historical metadata remains schema 22.
for relative in [
    "core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/ReleaseSecurityModels.kt",
    "core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/ReleaseReadinessModels.kt",
    "core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/FinalReleaseGateModels.kt",
]:
    source = read(relative)
    require("schema v21" not in source.lower(), f"stale schema-v21 release wording remains in {relative}")

if errors:
    print("Media Parity01 runtime-truth validation failed:")
    for error in errors:
        print(f"- {error}")
    sys.exit(1)


# Active carry-forward seals must recognize Parity01 as a legitimate later overlay.
for relative in [
    "tools/validate-ux13-end-to-end-ui-ux-release-seal.py",
    "tools/validate-post-ux13-roadmap-completion-hotfix.py",
    "tools/validate-phase61-final-gate-validator-harmony.py",
    "tools/validate-phase62-real-device-operational-smoke-seal.py",
    "tools/validate-phase60-runtime-recovery-flow-seal.py",
    "tools/validate-phase59-runtime-recovery-action-transparency.py",
    "tools/validate-phase58-runtime-recovery-execution-guard.py",
    "tools/validate-phase57-runtime-failure-recovery-ux.py",
    "tools/validate-phase56-stale-copy-architecture-noise-sweep.py",
    "tools/validate-phase55-final-release-warning-explainer.py",
    "tools/validate-phase63-release-readiness-support-bundle-seal.py",
    "tools/validate-phase64-final-android-downloader-rc-seal.py",
    "tools/validate-remediation-phase13-final-gate.py",
]:
    source = read(relative)
    require(expected_overlay in source, f"active carry-forward validator must accept Media Parity01: {relative}")
    require(successor_overlay in source, f"active carry-forward validator must accept Media Parity02 successor: {relative}")

require(manifest.get("final_public_release_gate", {}).get("room_schema_locked") == 24, "final public release gate must report current Room schema 24")
require(manifest.get("final_public_release_gate", {}).get("schema_version_unchanged") == 23, "final public release gate schema truth must be 23")

print("Media Parity01 runtime-truth validation passed: final diagnostics artifacts are fail-closed, topology/schema truth is current, legacy Firefox crypto blockers are retired, and aria2 health is lifecycle-backed")
