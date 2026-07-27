#!/usr/bin/env python3
"""Static contracts for Phase 38 repository-owned Firefox extension source."""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
EXT = ROOT / "browser-extension"
SRC = EXT / "src/main/extension/xdm-firefox"
ERRORS: list[str] = []


def require(relative: Path) -> str:
    path = ROOT / relative
    if not path.is_file():
        ERRORS.append(f"missing file: {relative}")
        return ""
    return path.read_text(encoding="utf-8", errors="replace")

required = [
    "manifest.template.json", "generated-config.template.js", "detector-core.js",
    "candidate-store.js", "network-observer.js", "page-sniffer.js", "frame-bridge.js",
    "handoff.js", "fab.js", "popup.html", "popup.js", "extension.css",
    "icons/icon48.png", "icons/icon96.png",
]
for rel in required:
    if not (SRC / rel).is_file(): ERRORS.append(f"missing extension source: {rel}")

settings = require(Path("settings.gradle.kts"))
if '":browser-extension"' not in settings: ERRORS.append("settings.gradle.kts does not include :browser-extension")
module_gradle = require(Path("browser-extension/build.gradle.kts"))
for token in ("alias(libs.plugins.kotlin.jvm)", "prepareFirefoxExtension", "validateFirefoxExtension", "jsTest"):
    if token not in module_gradle: ERRORS.append(f"browser-extension build missing {token}")
for forbidden in ("com.android.application", "com.android.library", "android.webkit", "WebView"):
    if forbidden in module_gradle: ERRORS.append(f"browser-extension must remain WebKit-free: {forbidden}")

manifest_text = require(Path("browser-extension/src/main/extension/xdm-firefox/manifest.template.json"))
try:
    manifest = json.loads(manifest_text)
except json.JSONDecodeError as exc:
    ERRORS.append(f"manifest template parse failed: {exc}")
    manifest = {}
if manifest.get("browser_specific_settings", {}).get("gecko", {}).get("id") != "xdm-android-media-bridge@mikeyphw":
    ERRORS.append("stable XDM-owned extension id missing")
if manifest.get("content_scripts", [{}])[0].get("all_frames") is not True:
    ERRORS.append("extension must collect from all frames")

handoff = require(Path("browser-extension/src/main/extension/xdm-firefox/handoff.js"))
for token in ('`${scheme}://capture?${params.toString()}`', 'params.set("v"', 'params.set("url"', 'idmdownload:'):
    if token not in handoff: ERRORS.append(f"handoff contract missing {token}")
for token in ("extra_cookies", "extra_authorization", "extra_headers", "proxy-authorization"):
    if token in handoff: ERRORS.append(f"XDM custom URI must not carry {token}")

popup = require(Path("browser-extension/src/main/extension/xdm-firefox/popup.html"))
if re.search(r"(?:xdmdownload|idmdownload|1dmdownload|intent):", popup, re.I):
    ERRORS.append("popup contains a custom protocol link")
popup_js = require(Path("browser-extension/src/main/extension/xdm-firefox/popup.js"))
if "browser.runtime.sendMessage" in popup_js or "browser.tabs.update" in popup_js:
    ERRORS.append("popup must not depend on runtime messaging or tab navigation for handoff")

for rel, tokens in {
    "detector-core.js": ("classifyResponse", "analyzeBody", "MAX_BODY_CHARS = 786_432"),
    "network-observer.js": ("browser.webRequest.onHeadersReceived", "XdmCandidateStoreV1", "xdmPageObservationV1"),
    "page-sniffer.js": ("window.fetch", "XMLHttpRequest.prototype.open", "MAX_BODY_BYTES = 786_432"),
    "frame-bridge.js": ("All-frame playback observations", "PerformanceObserver", "MutationObserver", "__xdmInPageBridgeV1"),
    "candidate-store.js": ("class CandidateStore", "rankCandidate", "removeTab"),
}.items():
    text = require(Path("browser-extension/src/main/extension/xdm-firefox") / rel)
    for token in tokens:
        if token not in text: ERRORS.append(f"{rel} missing detector token {token}")

for path in EXT.rglob("*.xpi"):
    try:
        path.relative_to(EXT / "build")
        continue
    except ValueError:
        pass
    ERRORS.append(f"generated XPI must not be committed in Phase 38 source: {path.relative_to(ROOT)}")

project_manifest_text = require(Path("PROJECT_MANIFEST.json"))
try:
    project_manifest = json.loads(project_manifest_text)
except json.JSONDecodeError as exc:
    ERRORS.append(f"PROJECT_MANIFEST.json parse failed: {exc}")
else:
    contract = project_manifest.get("browser_bridge_phase38_repo_owned_firefox_extension", {})
    expected = {
        "extension_id": "xdm-android-media-bridge@mikeyphw",
        "default_target": "xdm",
        "xpi_committed": False,
        "browser_runtime_added": False,
        "top_level_route_added": False,
    }
    for key, value in expected.items():
        if contract.get(key) != value: ERRORS.append(f"PROJECT_MANIFEST phase38 {key} must be {value!r}")

if ERRORS:
    print("Phase 38 repo-owned extension validation failed:", file=sys.stderr)
    for error in ERRORS: print(f"- {error}", file=sys.stderr)
    raise SystemExit(1)
print("Phase 38 repo-owned extension validation passed.")
