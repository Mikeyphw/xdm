#!/usr/bin/env python3
from __future__ import annotations

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REPOSITORY_ROOT = ROOT.parents[1]
EXTENSION = ROOT / "browser-extension" / "src" / "main" / "extension" / "xdm-firefox"


def read(path: str, *, repository: bool = False) -> str:
    base = REPOSITORY_ROOT if repository else ROOT
    target = base / path
    if not target.is_file():
        raise SystemExit(f"Phase 43A missing required file: {path}")
    return target.read_text(encoding="utf-8")


def require(source: str, needle: str, label: str) -> None:
    if needle not in source:
        raise SystemExit(f"Phase 43A contract missing {label}: {needle}")


def reject(source: str, needle: str, label: str) -> None:
    if needle in source:
        raise SystemExit(f"Phase 43A forbidden {label}: {needle}")


def require_regex(source: str, pattern: str, label: str) -> None:
    if not re.search(pattern, source, re.S):
        raise SystemExit(f"Phase 43A contract missing {label}: /{pattern}/")


manifest = json.loads(read("browser-extension/src/main/extension/xdm-firefox/manifest.template.json"))
content_scripts = manifest.get("content_scripts") or []
if not content_scripts:
    raise SystemExit("Phase 43A manifest has no content_scripts")
content_js = content_scripts[0].get("js") or []
expected_content_order = [
    "bridge-selftest.js",
    "generated-config.js",
    "handoff.js",
    "fab.js",
    "frame-bridge.js",
]
expected_background_order = [
    "generated-config.js",
    "detector-core.js",
    "candidate-store.js",
    "network-observer.js",
]
background_js = (manifest.get("background") or {}).get("scripts") or []
if content_js != expected_content_order:
    raise SystemExit(f"Phase 43A content script order mismatch: {content_js!r}")
if background_js != expected_background_order:
    raise SystemExit(f"Phase 43A background script order mismatch: {background_js!r}")
if content_scripts[0].get("all_frames") is not True:
    raise SystemExit("Phase 43A layered detector must retain all_frames=true")

source_entries = sorted({
    "bridge-selftest.js",
    "generated-config.template.js",
    "generated-theme.template.css",
    "handoff.js",
    "fab.js",
    "detector-core.js",
    "candidate-store.js",
    "network-observer.js",
    "frame-bridge.js",
    "page-sniffer.js",
    "popup.js",
    "popup.html",
    "extension.css",
})
for entry in source_entries:
    if not (EXTENSION / entry).is_file():
        raise SystemExit(f"Phase 43A extension source missing: {entry}")

selftest = read("browser-extension/src/main/extension/xdm-firefox/bridge-selftest.js")
for needle in (
    "__xdmBridgeSelfTestV1",
    "attachShadow({ mode: \"open\" })",
    "hostMounted && shadowMounted",
    "removeHost();",
):
    require(selftest, needle, "bridge self-test")

popup = read("browser-extension/src/main/extension/xdm-firefox/popup.js")
for needle in (
    'const BRIDGE_FILES = ["bridge-selftest.js", "generated-config.js", "handoff.js", "fab.js", "frame-bridge.js"]',
    "async function executeTopFrame",
    "async function executeAllFramesBestEffort",
    "The top frame is the only frame that can mount the visible FAB",
    "runBridgeSelfTest",
    "showManualWithDiagnostics",
    "describeHealth",
    "readBridgeHealth",
):
    require(popup, needle, "popup bridge parity")
require_regex(
    popup,
    r"for \(const file of BRIDGE_FILES\) \{\s*await executeTopFrame\(tabId, \{ file \}\);\s*\}",
    "required top-frame injection loop",
)
require_regex(
    popup,
    r"for \(const file of BRIDGE_FILES\) \{\s*await executeAllFramesBestEffort\(tabId, \{ file \}\);\s*\}",
    "best-effort all-frame detector loop",
)

popup_html = read("browser-extension/src/main/extension/xdm-firefox/popup.html")
for element_id in (
    "selfTestState",
    "bridgeState",
    "handoffState",
    "fabState",
    "hostState",
    "snifferState",
    "offerState",
):
    require(popup_html, f'id="{element_id}"', "popup bridge health UI")

bridge = read("browser-extension/src/main/extension/xdm-firefox/frame-bridge.js")
for needle in (
    "PAGE_SNIFFER_STATUS_MARKER",
    "dependencyHealth",
    "topFrameOffersAttempted",
    "fabShowSuccesses",
    "fabShowFailures",
    "showManualWithDiagnostics",
    "version: \"1.2.0\"",
):
    require(bridge, needle, "frame bridge diagnostics")
require_regex(
    bridge,
    r"const playback = evaluateAllVideos\(\);.*?if \(!playback\.offered && input\.displayFallback",
    "network fallback must depend on offered launcher, not just playing video",
)
require(bridge, "return Object.freeze({ seenPlaying, offered });", "playback offer result")

sniffer = read("browser-extension/src/main/extension/xdm-firefox/page-sniffer.js")
for needle in (
    "STATUS_MARKER",
    "fetchWrapperActive",
    "xhrWrapperActive",
    "mediaPlayWrapperActive",
    "resourceObserverActive",
    "publishStatus();",
):
    require(sniffer, needle, "page sniffer status")

network = read("browser-extension/src/main/extension/xdm-firefox/network-observer.js")
require(network, '["bridge-selftest.js", "generated-config.js", "handoff.js", "fab.js", "frame-bridge.js"]', "background reinjection stack")

gradle = read("browser-extension/build.gradle.kts")
require(gradle, "test_phase43a_bridge.js", "Gradle JS test lane")



release_verifier = read("browser-extension/tools/verify_release_artifacts.py")
require(release_verifier, '"bridge-selftest.js"', "release XPI verifier inventory")
require_regex(
    release_verifier,
    r"REQUIRED_FILES = \{\s*\"bridge-selftest\.js\"",
    "release verifier must list bridge-selftest before generated XPI inventory comparison",
)

devtool_toml = read(".devtool.toml", repository=True)
release_verify_task = '":browser-extension:verifyFirefoxExtensionReleaseArtifacts"'
if devtool_toml.count(release_verify_task) < 2:
    raise SystemExit("Phase 43A Devtool validation must run browser-extension release artifact verification in both build and package lanes")

source_contract = read("browser-extension/src/main/kotlin/com/mikeyphw/xdm/android/browserextension/BrowserExtensionSourceContract.kt")
contract_test = read("browser-extension/src/test/kotlin/com/mikeyphw/xdm/android/browserextension/BrowserExtensionSourceContractTest.kt")
for source, label in ((source_contract, "source contract"), (contract_test, "source contract test")):
    require(source, "bridge-selftest.js", label)

phase_test = read("browser-extension/tests/test_phase43a_bridge.js")
for needle in (
    "probe FAB must render on a plain HTTPS page",
    "high-confidence HLS network candidate must show without visible video",
    "blocked blob playback must not suppress the network fallback FAB",
    "missing handoff must fail visibly",
    "testSetTimeout",
):
    require(phase_test, needle, "Phase 43A JS regression")

architecture = read("docs/architecture/PHASE-43A-BROWSER-EXTENSION-PARITY.md")
for needle in (
    "manual launcher must render",
    "top-frame FAB path is now required",
    "high-confidence HLS/DASH network fallback",
    "Phase 42 full release matrix remains the release train gate",
):
    require(architecture, needle, "Phase 43A architecture doc")

extension_readme = read("docs/browser-extension/README.md")
for needle in (
    "Phase 43A launcher parity",
    "Child-frame detector injection is best-effort",
):
    require(extension_readme, needle, "extension README")

changelog = read("CHANGELOG.md", repository=True)
require(changelog, "XDM Android Phase 43A - Browser extension FAB/detector parity repair", "repository changelog")

project_manifest = json.loads(read("PROJECT_MANIFEST.json"))
phase = project_manifest.get("browser_bridge_phase43a_extension_parity") or {}
if phase.get("program") != "android_browser_bridge" or phase.get("manual_fab_plain_https_exit_gate") is not True:
    raise SystemExit("Phase 43A project manifest entry is missing or incomplete")
if project_manifest.get("next_phase") != "Phase 43B Add Download media recommendation demotion":
    raise SystemExit(f"Phase 43A next phase mismatch: {project_manifest.get('next_phase')!r}")

# Guard against scope creep: Phase 43A is extension-only plus docs/contracts.
for forbidden in (
    "DownloadRow.kt",
    "Completed notification opens file",
    "Media batch input",
    "shared media sniffing engine",
):
    reject(architecture, forbidden, "out-of-scope future roadmap work")

print("Phase 43A browser extension parity static validation passed.")
