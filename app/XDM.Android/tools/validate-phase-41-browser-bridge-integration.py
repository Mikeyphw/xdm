#!/usr/bin/env python3
from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REPOSITORY_ROOT = ROOT.parents[1]


def read(path: str) -> str:
    target = ROOT / path
    if not target.is_file():
        raise SystemExit(f"Phase 41 missing required file: {path}")
    return target.read_text(encoding="utf-8")


def read_repository(path: str) -> str:
    target = REPOSITORY_ROOT / path
    if not target.is_file():
        raise SystemExit(f"Phase 41 missing required repository file: {path}")
    return target.read_text(encoding="utf-8")


def require(source: str, needle: str, label: str) -> None:
    if needle not in source:
        raise SystemExit(f"Phase 41 contract missing {label}: {needle}")


def reject(source: str, needle: str, label: str) -> None:
    if needle in source:
        raise SystemExit(f"Phase 41 forbidden {label}: {needle}")


parser = read("browser-integration/src/main/kotlin/com/mikeyphw/xdm/android/browser/XdmBrowserDeepLinkParser.kt")
result = read("browser-integration/src/main/kotlin/com/mikeyphw/xdm/android/browser/XdmBrowserDeepLinkParseResult.kt")
activity = read("app/src/main/kotlin/com/mikeyphw/xdm/android/MainActivity.kt")
manager = read("app/src/main/kotlin/com/mikeyphw/xdm/android/BrowserExtensionExportManager.kt")
models = read("app/src/main/kotlin/com/mikeyphw/xdm/android/BrowserBridgeIntegrationModels.kt")
export_models = read("app/src/main/kotlin/com/mikeyphw/xdm/android/BrowserExtensionExportModels.kt")
preferences = read("app/src/main/kotlin/com/mikeyphw/xdm/android/UserPreferencesStore.kt")
view_model = read("app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt")
settings = read("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/settings/BrowserExtensionSettingsScreen.kt")
settings_panel = read("app/src/main/kotlin/com/mikeyphw/xdm/android/SettingsPanel.kt")
doc = read("docs/architecture/PHASE-41-BROWSER-BRIDGE-INTEGRATION.md")
workflow = read(".github/workflows/android.yml")
extension_build = read("browser-extension/build.gradle.kts")
project_manifest = json.loads(read("PROJECT_MANIFEST.json"))
release_checklist = read_repository("docs/RELEASE-CHECKLIST.md")

for needle in (
    "parseDetailed",
    "XdmBrowserDeepLinkParseResult.NotApplicable",
    "XdmBrowserDeepLinkParseResult.Accepted",
    "XdmBrowserDeepLinkParseResult.Rejected",
    "XdmBrowserDeepLinkRejection.VariantMismatch",
    "XdmBrowserDeepLinkRejection.UnsupportedContract",
):
    require(parser + result, needle, "result-bearing parser")

require(activity, "viewModel.recordBrowserDeepLinkResult(browserDeepLinkResult)", "diagnostic recording")
require(activity, "is XdmBrowserDeepLinkParseResult.Rejected -> return", "rejected-link short circuit")
if activity.index("parseDetailed") > activity.index("sharedText(incoming)"):
    raise SystemExit("Phase 41 custom-scheme parsing must precede generic external intake")

for needle in (
    "resolveActivity",
    "BrowserBridgeSchemeState.Ready",
    "BrowserBridgeSafState.PermissionRevoked",
    "BrowserBridgeSafState.ExportMissing",
    "BrowserBridgeSafState.ChecksumMismatch",
    "BrowserExtensionHash::digest",
    "openExportedFile",
    "releaseDirectoryPermission",
):
    require(manager, needle, "integration health/recovery")

for needle in (
    "BrowserBridgeDiagnosticsRedactor",
    "safeEndpoint",
    "Bearer <redacted>",
    "browserBridgeIronFoxInstructions",
    "network.protocol-handler.expose.$scheme = true",
):
    require(models, needle, "redaction/setup contract")

for forbidden in (
    "browser_bridge_raw_deep_link",
    "browser_bridge_cookie",
    "browser_bridge_authorization",
    "BrowserBridgeRawUrl",
):
    reject(preferences + models, forbidden, "sensitive persisted diagnostic")

for needle in (
    "BrowserBridgeLastAcceptedSummary",
    "BrowserBridgeLastRejectedSummary",
    "BrowserBridgeLastGenerationPhase",
    "BrowserExtensionLastExportDocumentUri",
    "BrowserExtensionLastExportContractVersion",
    "recordBrowserBridgeAccepted",
    "recordBrowserBridgeRejected",
    "recordBrowserBridgeGeneration",
):
    require(preferences, needle, "persisted bounded diagnostics")

for needle in (
    "lastExportTarget",
    "lastExportApplicationId",
    "lastExportScheme",
    "lastExportContractVersion",
    "staleReasons",
):
    require(export_models, needle, "compatibility metadata")

for needle in (
    "recordBrowserDeepLinkResult",
    "refreshBrowserExtensionStatus",
    "openBrowserExtensionXpi",
    "clearBrowserExtensionExportFolder",
    'phase = "exporting"',
    'phase = "succeeded"',
    'phase = "failed"',
):
    require(view_model, needle, "ViewModel integration")

for needle in (
    "Bridge status",
    "Compatibility and recovery",
    "Open exported XPI",
    "Copy setup instructions",
    "Redacted diagnostics",
    "Refresh status",
    "Regenerate XPI",
):
    require(settings, needle, "truthful settings surface")

require(settings_panel, 'BrowserExtension("Browser extension")', "secondary Settings panel")
reject(settings_panel, "WebView", "browser runtime")

for path in (
    "browser-integration/src/test/kotlin/com/mikeyphw/xdm/android/browser/XdmBrowserDeepLinkDiagnosticsTest.kt",
    "app/src/test/kotlin/com/mikeyphw/xdm/android/BrowserBridgeDiagnosticsRedactorTest.kt",
    "app/src/test/kotlin/com/mikeyphw/xdm/android/BrowserExtensionPhase41ContractTest.kt",
):
    read(path)

for needle in (
    "Settings truth contract",
    "Result-bearing deep-link parser",
    "Compatibility and recovery",
    "Privacy contract",
):
    require(doc, needle, "architecture documentation")

require(workflow, "python3 tools/validate-phase-41-browser-bridge-integration.py", "CI validator wiring")
require(extension_build, "XDM-Android-Firefox-1.1.0-release-$theme.xpi", "XPI filename/version consistency")
reject(extension_build, "XDM-Android-Firefox-1.0.0-release-$theme.xpi", "stale XPI filename")
for needle in (
    "## XDM Android browser bridge",
    "Accepted and rejected deep-link diagnostics remain bounded",
    "Phase 37 through Phase 41 validators pass",
):
    require(release_checklist, needle, "release checklist")
phase = project_manifest.get("browser_bridge_phase41_browser_bridge_integration")
if not isinstance(phase, dict):
    raise SystemExit("Phase 41 project-manifest entry missing")
for key in (
    "result_bearing_deep_link_parser",
    "diagnostics_redacted",
    "scheme_registration_probe",
    "saf_permission_probe",
    "export_sha256_verify",
    "interrupted_generation_recovery",
):
    if phase.get(key) is not True:
        raise SystemExit(f"Phase 41 project-manifest contract missing: {key}")
if phase.get("top_level_route_added") is not False or phase.get("browser_runtime_added") is not False:
    raise SystemExit("Phase 41 must preserve route topology and browser-free runtime")

changed_runtime = "\n".join((manager, models, export_models, view_model, settings))
for forbidden in ("android.webkit.WebView", "setJavaScriptEnabled", "GeckoView", "Room.databaseBuilder"):
    reject(changed_runtime, forbidden, "runtime architecture regression")

print("Phase 41 browser bridge integration validation passed.")
