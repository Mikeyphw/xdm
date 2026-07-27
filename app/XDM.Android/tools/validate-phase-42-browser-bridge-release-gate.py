#!/usr/bin/env python3
from __future__ import annotations

import json
import re
import tomllib
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REPOSITORY_ROOT = ROOT.parents[1]
ANDROID_NS = "{http://schemas.android.com/apk/res/android}"


def read(path: str) -> str:
    target = ROOT / path
    if not target.is_file():
        raise SystemExit(f"Phase 42 missing required file: {path}")
    return target.read_text(encoding="utf-8")


def read_repository(path: str) -> str:
    target = REPOSITORY_ROOT / path
    if not target.is_file():
        raise SystemExit(f"Phase 42 missing required repository file: {path}")
    return target.read_text(encoding="utf-8")


def require(source: str, needle: str, label: str) -> None:
    if needle not in source:
        raise SystemExit(f"Phase 42 contract missing {label}: {needle}")


def reject(source: str, needle: str, label: str) -> None:
    if needle in source:
        raise SystemExit(f"Phase 42 forbidden {label}: {needle}")


def iter_files(*roots: str, suffixes: tuple[str, ...]) -> list[Path]:
    files: list[Path] = []
    for raw in roots:
        root = ROOT / raw
        if root.is_file() and root.suffix in suffixes:
            files.append(root)
        elif root.is_dir():
            files.extend(path for path in root.rglob("*") if path.is_file() and path.suffix in suffixes)
    return sorted(set(files))


architecture = read("docs/architecture/PHASE-42-BROWSER-BRIDGE-RELEASE-GATE.md")
extension_readme = read("docs/browser-extension/README.md")
ironfox = read("docs/browser-extension/IRONFOX-INSTALLATION.md")
device_doc = read("docs/browser-extension/DEVICE-ACCEPTANCE.md")
device_script = read("tools/run-browser-bridge-device-acceptance.sh")
compile_recovery = read("tools/validate-phase-42-kotlin-compile-recovery.py")
source_compatibility = read("tools/validate-phase-42-kotlin-source-compatibility.py")
contract_test_modernization = read("tools/validate-phase-42-contract-test-modernization.py")
bridge_gate = read("tools/run-browser-bridge-release-gate.sh")
final_gate = read("tools/run-final-release-gate.sh")
build_gradle = read("browser-extension/build.gradle.kts")
verify_artifacts = read("browser-extension/tools/verify_release_artifacts.py")
release_js = read("browser-extension/tests/test_release_gate.js")
app_route = read("app/src/main/kotlin/com/mikeyphw/xdm/android/AppRoute.kt")
manifest_text = read("app/src/main/AndroidManifest.xml")
project_manifest = json.loads(read("PROJECT_MANIFEST.json"))
release_checklist = read_repository("docs/RELEASE-CHECKLIST.md")
root_workflow = read_repository(".github/workflows/android.yml")
local_workflow = read(".github/workflows/android.yml")

for phase in range(37, 42):
    read(f"tools/validate-phase-{phase}-" + {
        37: "browser-scheme-contract.py",
        38: "repo-owned-extension.py",
        39: "xpi-export.py",
        40: "theme-fab.py",
        41: "browser-bridge-integration.py",
    }[phase])

for needle in (
    "Devtool overlay requires restore, build, test, package, and lint validation",
    "verifyFirefoxExtensionReleaseArtifacts",
    "No Android WebView, GeckoView, built-in browser route",
    "device release qualification remains pending",
):
    require(architecture, needle, "architecture release-gate documentation")

for source, needles, label in (
    (extension_readme, ("release-artifacts.json", "credential-thin", "ExternalAddDownloadActivity"), "extension release documentation"),
    (ironfox, ("network.protocol-handler.expose.xdmdownload = true", "real anchor inside the current webpage"), "IronFox installation documentation"),
    (device_doc, ("Direct MP4", "Blob/MediaSource", "Cross-origin iframe", "IronFox 152 or newer"), "device matrix"),
    (device_script, ("cmd package resolve-activity", "ExternalAddDownloadActivity", "--scheme", "--package"), "device acceptance runner"),
):
    for needle in needles:
        require(source, needle, label)

with (REPOSITORY_ROOT / ".devtool.toml").open("rb") as stream:
    devtool = tomllib.load(stream)
tasks = devtool.get("targets", {}).get("xdm_android", {}).get("tasks", {})
required_restore = {"help"}
required_build = {
    "assembleDebug",
    "assembleBeta",
    ":app:assembleDebugAndroidTest",
    ":browser-extension:packageFirefoxExtensionDark",
    ":browser-extension:packageFirefoxExtensionAmoled",
    ":browser-extension:verifyFirefoxExtensionReleaseArtifacts",
}
required_test = {
    ":browser-extension:test",
    ":browser-extension:jsTest",
    ":browser-extension:validateFirefoxExtension",
    ":app:checkBrowserIntegration",
    ":core-model:test",
    ":core-utils:test",
    ":transfer-api:test",
    ":browser-integration:testDebugUnitTest",
    ":storage:testDebugUnitTest",
    ":transfer-native:testDebugUnitTest",
    ":transfer-aria2:test",
    ":scheduler:testDebugUnitTest",
    ":media:test",
    ":persistence:testDebugUnitTest",
    ":app:testDebugUnitTest",
}
required_lint = {"lintDebug", "lintBeta"}
for key, required in (("restore", required_restore), ("build", required_build), ("test", required_test), ("lint", required_lint)):
    actual = set(tasks.get(key, []))
    missing = sorted(required - actual)
    if missing:
        raise SystemExit(f"Phase 42 Devtool {key} matrix missing: {missing}")
validation = devtool.get("validation", {})
for key, expected in (("android_safe", True), ("max_workers", 1), ("cpu_limit", 2), ("no_daemon", True), ("low_priority", True), ("parallel", False)):
    if validation.get(key) != expected:
        raise SystemExit(f"Phase 42 Devtool safety value mismatch: {key}={validation.get(key)!r}")

gradle_options = devtool.get("targets", {}).get("xdm_android", {}).get("gradle", {})
for key, expected in (("max_workers", 1), ("no_daemon", True), ("parallel", False), ("build_cache", False)):
    if gradle_options.get(key) != expected:
        raise SystemExit(f"Phase 42 Kotlin recovery Gradle value mismatch: {key}={gradle_options.get(key)!r}")
compile_tasks = validation.get("phases", {}).get("compile", [])
if compile_tasks != [":app:resetKotlinValidationState", ":app:compileDebugSources"]:
    raise SystemExit(f"Phase 42 Kotlin recovery compile phase mismatch: {compile_tasks!r}")
for needle in (
    "positionMs = playbackPositionMs",
    "ExperimentalMaterial3Api",
    "foundation.layout.weight",
    "ui.common",
    "val total = totalBytes",
):
    require(source_compatibility, needle, "Kotlin source compatibility validator")


for needle in (
    "private val repo = androidRoot()",
    "DeveloperWorkspacePolicy.shouldCompose",
    "XdmMinimumTouchTarget",
):
    require(contract_test_modernization, needle, "contract-test modernization validator")

for needle in (
    "-Pkotlin.incremental=false",
    "-Pkotlin.compiler.execution.strategy=in-process",
    "-Pxdm.cleanKotlinValidation=true",
):
    require(compile_recovery, needle, "Kotlin compile recovery validator")

for source, label in ((root_workflow, "root CI"), (local_workflow, "Android-local CI")):
    for needle in (
        "validate-phase-42-browser-bridge-release-gate.py",
        ":browser-extension:verifyFirefoxExtensionReleaseArtifacts",
        "run-browser-bridge-release-gate.sh --verify-artifacts",
        "release-artifacts.json",
    ):
        require(source, needle, label)

for needle in (
    "tools/validate-phase-42-browser-bridge-release-gate.py",
    ":browser-extension:verifyFirefoxExtensionReleaseArtifacts",
    "Device acceptance remains",
):
    require(final_gate, needle, "final public release gate")

for needle in (
    "validate-phase-42-browser-bridge-release-gate.py",
    "validate-phase-42-kotlin-compile-recovery.py",
    "validate-phase-42-kotlin-source-compatibility.py",
    "validate-phase-42-contract-test-modernization.py",
    "test_release_gate.js",
    "verifyFirefoxExtensionReleaseArtifacts",
    "run-browser-bridge-device-acceptance.sh --print",
):
    require(bridge_gate, needle, "browser bridge release gate runner")

for needle in (
    "val verifyFirefoxExtensionReleaseArtifacts",
    "verify_release_artifacts.py",
    "release-artifacts.json",
    "test_release_gate.js",
):
    require(build_gradle, needle, "Gradle release artifact gate")
for needle in (
    "FIXED_ZIP_DATE",
    "EXPECTED_ID",
    "EXPECTED_VERSION",
    "Unexpected extension permissions",
    "Dark and AMOLED release XPIs must not be byte-identical",
):
    require(verify_artifacts, needle, "XPI release verification")
for needle in (
    "signature=signed-value",
    "must-not-be-copied",
    "javascript:alert(1)",
    "application/dash+xml",
):
    require(release_js, needle, "release JavaScript regression")

expected_routes = {"Downloads", "Add", "Media", "Library", "Activity", "Settings"}
route_match = re.search(r"enum class AppRoute.*?\{(.*?)\n\s*;", app_route, re.S)
if not route_match:
    raise SystemExit("Phase 42 could not inspect AppRoute topology")
actual_routes = set(re.findall(r"^\s{4}([A-Z][A-Za-z0-9_]*)\(", route_match.group(1), re.M))
if actual_routes != expected_routes:
    raise SystemExit(f"Phase 42 top-level route topology changed: {sorted(actual_routes)}")
reject(app_route, 'Browser("', "built-in browser route")

manifest_root = ET.fromstring(manifest_text)
custom_scheme_owners: list[str] = []
for activity in manifest_root.findall(".//activity"):
    activity_name = activity.attrib.get(ANDROID_NS + "name", "")
    for data in activity.findall(".//data"):
        mime = data.attrib.get(ANDROID_NS + "mimeType")
        if mime is not None and mime != mime.lower():
            raise SystemExit(f"Phase 42 manifest MIME must be lowercase: {mime}")
        scheme = data.attrib.get(ANDROID_NS + "scheme", "")
        if scheme == "${xdmBrowserScheme}" or scheme in {"xdmdownload", "xdmdownload-beta", "xdmdownload-debug"}:
            custom_scheme_owners.append(activity_name)
if not custom_scheme_owners or set(custom_scheme_owners) != {".ExternalAddDownloadActivity"}:
    raise SystemExit(f"Phase 42 custom scheme ownership mismatch: {custom_scheme_owners}")

active_runtime_files = iter_files(
    "app/src/main/kotlin",
    "browser-integration/src/main/kotlin",
    "browser-extension/src/main/kotlin",
    suffixes=(".kt", ".java"),
)
active_runtime = "\n".join(path.read_text(encoding="utf-8", errors="ignore") for path in active_runtime_files)
for forbidden in (
    "android.webkit.WebView",
    "android.webkit.WebViewClient",
    "org.mozilla.geckoview.GeckoView",
    "setJavaScriptEnabled",
    "javaScriptEnabled = true",
):
    reject(active_runtime, forbidden, "browser-runtime regression")

bridge_files = [
    path for path in active_runtime_files
    if "Browser" in path.name or "browser-extension" in path.as_posix() or "browser-integration" in path.as_posix()
]
static_reference = re.compile(
    r"(?:companion\s+object|object\s+[A-Za-z0-9_]+)\s*\{(?:(?!\n\}).){0,5000}?"
    r"\b(?:lateinit\s+)?(?:val|var)\s+[A-Za-z0-9_]+\s*:\s*(?:Context|Activity|WebView)\b",
    re.S,
)
for path in bridge_files:
    source = path.read_text(encoding="utf-8", errors="ignore")
    if static_reference.search(source):
        raise SystemExit(f"Phase 42 static Android reference leak candidate: {path.relative_to(ROOT)}")

secret_scan_files = iter_files(
    "browser-extension/src",
    "browser-extension/tests",
    "browser-integration/src",
    "app/src/main/kotlin/com/mikeyphw/xdm/android/BrowserBridgeIntegrationModels.kt",
    "app/src/main/kotlin/com/mikeyphw/xdm/android/BrowserExtensionExportManager.kt",
    "app/src/test/kotlin/com/mikeyphw/xdm/android/BrowserBridgeDiagnosticsRedactorTest.kt",
    "docs/browser-extension",
    "docs/architecture/PHASE-37-BROWSER-SCHEME-CONTRACT.md",
    "docs/architecture/PHASE-38-REPO-OWNED-FIREFOX-EXTENSION.md",
    "docs/architecture/PHASE-39-XPI-GENERATION-SAF-EXPORT.md",
    "docs/architecture/PHASE-40-SHARED-THEME-FAB.md",
    "docs/architecture/PHASE-41-BROWSER-BRIDGE-INTEGRATION.md",
    "docs/architecture/PHASE-42-BROWSER-BRIDGE-RELEASE-GATE.md",
    suffixes=(".kt", ".java", ".js", ".py", ".md", ".html", ".css", ".json"),
)
secret_patterns = {
    "private key": re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
    "AWS access key": re.compile(r"\bAKIA[0-9A-Z]{16}\b"),
    "GitHub token": re.compile(r"\bgh[pousr]_[A-Za-z0-9]{30,}\b"),
    "OpenAI-style key": re.compile(r"\bsk-[A-Za-z0-9_-]{20,}\b"),
    "JWT": re.compile(r"\beyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\b"),
    "unredacted bearer literal": re.compile(r"Authorization:\s*Bearer\s+(?!<redacted>|\$)[A-Za-z0-9._~-]{8,}", re.I),
}
for path in secret_scan_files:
    source = path.read_text(encoding="utf-8", errors="ignore")
    for label, pattern in secret_patterns.items():
        if pattern.search(source):
            raise SystemExit(f"Phase 42 raw secret scan found {label}: {path.relative_to(ROOT)}")

phase = project_manifest.get("browser_bridge_phase42_release_gate")
if not isinstance(phase, dict):
    raise SystemExit("Phase 42 project-manifest entry missing")
for key in (
    "full_devtool_matrix",
    "phase37_41_replayed",
    "deterministic_xpi_release_metadata",
    "browser_runtime_absence_scanned",
    "manifest_mime_case_scanned",
    "static_context_leak_scanned",
    "secret_surface_scanned",
    "device_acceptance_runner",
    "manual_ironfox_signoff_required",
):
    if phase.get(key) is not True:
        raise SystemExit(f"Phase 42 project-manifest contract missing: {key}")
if phase.get("top_level_route_added") is not False or phase.get("browser_runtime_added") is not False:
    raise SystemExit("Phase 42 must preserve route topology and browser-free Android runtime")
implemented = set(project_manifest.get("project", {}).get("implemented_phases", []))
if not {37, 38, 39, 40, 41, 42}.issubset(implemented):
    raise SystemExit("Phase 42 implemented phase ledger is incomplete")

for needle in (
    "## XDM Android browser bridge Phase 42 release gate",
    "run-browser-bridge-release-gate.sh --full",
    "run-browser-bridge-device-acceptance.sh --adb",
    "manual IronFox matrix",
):
    require(release_checklist, needle, "release checklist finalization")

print("Phase 42 browser bridge release-gate validation passed.")
