#!/usr/bin/env python3
"""Static contract validation for Phase 37 XDM browser custom-scheme intake."""

from __future__ import annotations

import json
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ANDROID_NS = "{http://schemas.android.com/apk/res/android}"
ERRORS: list[str] = []


def require_file(relative: str) -> str:
    path = ROOT / relative
    if not path.is_file():
        ERRORS.append(f"missing file: {relative}")
        return ""
    return path.read_text(encoding="utf-8")


def require_contains(relative: str, *tokens: str) -> str:
    text = require_file(relative)
    for token in tokens:
        if token not in text:
            ERRORS.append(f"{relative} missing token: {token}")
    return text


def validate_manifest() -> None:
    manifest_path = ROOT / "app/src/main/AndroidManifest.xml"
    try:
        tree = ET.parse(manifest_path)
    except (ET.ParseError, OSError) as exc:
        ERRORS.append(f"manifest parse failed: {exc}")
        return

    owners: list[str] = []
    capture = add = default = browsable = view = False
    for activity in tree.findall(".//activity"):
        activity_name = activity.attrib.get(f"{ANDROID_NS}name", "")
        for intent_filter in activity.findall("intent-filter"):
            data = intent_filter.findall("data")
            if not any(item.attrib.get(f"{ANDROID_NS}scheme") == "${xdmBrowserScheme}" for item in data):
                continue
            owners.append(activity_name)
            hosts = {item.attrib.get(f"{ANDROID_NS}host") for item in data}
            actions = {item.attrib.get(f"{ANDROID_NS}name") for item in intent_filter.findall("action")}
            categories = {item.attrib.get(f"{ANDROID_NS}name") for item in intent_filter.findall("category")}
            capture |= "capture" in hosts
            add |= "add" in hosts
            view |= "android.intent.action.VIEW" in actions
            default |= "android.intent.category.DEFAULT" in categories
            browsable |= "android.intent.category.BROWSABLE" in categories

    if sorted(set(owners)) != [".ExternalAddDownloadActivity"]:
        ERRORS.append(f"custom scheme owners must be only ExternalAddDownloadActivity, got {sorted(set(owners))}")
    for present, label in (
        (capture, "capture host"),
        (add, "add host"),
        (view, "VIEW action"),
        (default, "DEFAULT category"),
        (browsable, "BROWSABLE category"),
    ):
        if not present:
            ERRORS.append(f"manifest custom-scheme filter missing {label}")


def validate_build_variants() -> None:
    gradle = require_contains(
        "app/build.gradle.kts",
        'manifestPlaceholders["xdmBrowserScheme"]',
        'buildConfigField("String", "XDM_BROWSER_SCHEME"',
    )
    for scheme in ("xdmdownload", "xdmdownload-debug"):
        if f'"{scheme}"' not in gradle:
            ERRORS.append(f"app/build.gradle.kts missing build-variant scheme {scheme}")


def validate_parser_and_routing() -> None:
    require_contains(
        "browser-integration/src/main/kotlin/com/mikeyphw/xdm/android/browser/XdmBrowserDeepLinkContract.kt",
        "MaxDeepLinkBytes = 64 * 1024",
        "MaxMediaUrlBytes = 32 * 1024",
        "MaxPageUrlBytes = 8 * 1024",
        'ReleaseScheme = "xdmdownload"',
        'DebugScheme = "xdmdownload-debug"',
    )
    parser = require_contains(
        "browser-integration/src/main/kotlin/com/mikeyphw/xdm/android/browser/XdmBrowserDeepLinkParser.kt",
        "ExternalUrlPolicy.normalizedUrl",
        "AutomationCommandAction.CaptureMedia",
        "AutomationCommandAction.PromptAddDownload",
        "rawUserInfo",
        "MaxDeepLinkBytes",
    )
    for forbidden_payload in (
        'CookieParameter',
        'AuthorizationParameter',
        'ProxyAuthorizationParameter',
        'rawHeaders =',
        'getQueryParameter("cookie")',
        'getQueryParameter("authorization")',
    ):
        if forbidden_payload in parser:
            ERRORS.append(f"deep-link parser must not add sensitive payload field: {forbidden_payload}")

    payload = require_contains(
        "browser-integration/src/main/kotlin/com/mikeyphw/xdm/android/browser/XdmBrowserDeepLinkPayload.kt",
        "AutomationCommandSource.BrowserExtension",
        "toAutomationCommandDraft",
    )
    if "rawHeaders" in payload:
        ERRORS.append("deep-link payload must not carry rawHeaders")

    main = require_contains(
        "app/src/main/kotlin/com/mikeyphw/xdm/android/MainActivity.kt",
        "XdmBrowserDeepLinkParser.parse",
        "BuildConfig.XDM_BROWSER_SCHEME",
        "toAutomationCommandDraft",
    )
    parser_index = main.find("XdmBrowserDeepLinkParser.parse")
    generic_index = main.find("shouldOpenExternalAddPrompt")
    if parser_index < 0 or generic_index < 0 or parser_index >= generic_index:
        ERRORS.append("MainActivity must parse the custom scheme before generic external receiver routing")


def validate_contract_files() -> None:
    for relative in (
        "browser-integration/src/test/kotlin/com/mikeyphw/xdm/android/browser/XdmBrowserDeepLinkParserTest.kt",
        "app/src/test/kotlin/com/mikeyphw/xdm/android/BrowserSchemePhase37ContractTest.kt",
        "docs/architecture/PHASE-37-BROWSER-SCHEME-CONTRACT.md",
    ):
        require_file(relative)

    project_manifest_text = require_file("PROJECT_MANIFEST.json")
    try:
        project_manifest = json.loads(project_manifest_text)
    except json.JSONDecodeError as exc:
        ERRORS.append(f"PROJECT_MANIFEST.json parse failed: {exc}")
    else:
        contract = project_manifest.get("browser_scheme_contract", {})
        expected = {
            "release_scheme": "xdmdownload",
            "debug_scheme": "xdmdownload-debug",
            "receiver": "ExternalAddDownloadActivity",
            "top_level_route_added": False,
        }
        for key, value in expected.items():
            if contract.get(key) != value:
                ERRORS.append(f"PROJECT_MANIFEST browser_scheme_contract.{key} must be {value!r}")


def validate_browser_free_boundary() -> None:
    production_paths = [
        ROOT / "browser-integration/src/main",
        ROOT / "app/src/main/kotlin/com/mikeyphw/xdm/android/MainActivity.kt",
    ]
    text = "\n".join(
        path.read_text(encoding="utf-8") if path.is_file() else
        "\n".join(item.read_text(encoding="utf-8") for item in path.rglob("*") if item.is_file())
        for path in production_paths
    )
    for token in ("android.webkit", "WebView", "WebViewClient", "WebChromeClient"):
        if token in text:
            ERRORS.append(f"browser runtime token introduced: {token}")


validate_manifest()
validate_build_variants()
validate_parser_and_routing()
validate_contract_files()
validate_browser_free_boundary()

if ERRORS:
    print("Phase 37 browser scheme contract validation failed:", file=sys.stderr)
    for error in ERRORS:
        print(f"- {error}", file=sys.stderr)
    raise SystemExit(1)

print("Phase 37 browser scheme contract validation passed.")
