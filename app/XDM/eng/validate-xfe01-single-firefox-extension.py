#!/usr/bin/env python3
from __future__ import annotations

import hashlib
import json
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
CANONICAL = ROOT / "app/XDM.Android/browser-extension/src/main/extension/xdm-firefox"
CANONICAL_ID = "xdm-android-media-bridge@mikeyphw"
EXPECTED_HASHES = {
    "bridge-selftest.js": "7c442bee5be87e6802e9f82f6fcca88daede13377fbbd70dd6e85216b0022eb6",
    "candidate-store.js": "fe269e3e27cfaebbd21c546b87c5bb7924a794701ee1ba73e07b28abbd44ebba",
    "detector-core.js": "91605a16aeea7f2d31eed4f017219e6b8bda513a09cad63f500c8ccec6677a0c",
    "extension.css": "d411b60ae0c2f14cd88e46122cb17b21eded9a9174314facbc063a8b550c9113",
    "fab.js": "22740b97786736b3829abf8a7e989f35e9b4c40b80a8a381b13d88a235ff24e5",
    "frame-bridge.js": "27cf9a8f04b252d5ef601febd8de3d5f32f77e823cb83668d7c2fae9c84ef8b0",
    "generated-config.template.js": "810cad667e0efff1179b532c2e405d8688fc7df181720c59e588be004c8e1450",
    "generated-theme.template.css": "b52a7938b8df6faf687e81f0a39899acf2fa89ff19a3d21b6a8508602acef735",
    "handoff.js": "708dac9bcf851c13d053d4b08d2630f36ae089d4fc1336f97a4d1e7ba56e3c5e",
    "icons/icon48.png": "1ffad2f3f3e18ce0558fb506deb38c600f1747bb0dc0a5b1ad8c7ea448d8373b",
    "icons/icon96.png": "0f442e4f1c9075c0d8bdbe20a71102fa13bb6e603770010e78e1a87c27a7aa2f",
    "manifest.template.json": "25b9eb81c76b30a506ca82cef2e7dc2f548842a3cd98b8ad4124479de39fbede",
    "network-observer.js": "124acbb91422289acf900c50fc4764a4e99414a83cd221d4298276a0556fdaa7",
    "page-sniffer.js": "165ca7e2c610b3358b588d6c956ceb773a39d8b81d535a6aaf5f89ec4f74e63a",
    "popup.html": "7f907af7f8386060fb68ba68d8ea558b4cbea641e9baa5197f47329b488d3b4d",
    "popup.js": "0af69942535e7c6a3e0c46e8ca7dd20bd80aa92664374a5c85ddb50a5876aff4",
}


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit("XFE01 validation failed: " + message)


def text(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def verify_canonical_bytes() -> None:
    actual = {
        path.relative_to(CANONICAL).as_posix(): hashlib.sha256(path.read_bytes()).hexdigest()
        for path in sorted(CANONICAL.rglob("*"))
        if path.is_file()
    }
    require(actual == EXPECTED_HASHES, "canonical Android Firefox source changed; XFE01 must adapt desktop around it, not alter it")


def verify_single_source() -> None:
    retired = ROOT / "app/XDM/firefox-amo"
    require(retired.is_dir(), "legacy Firefox location must remain as an explicit non-executable tombstone for overlay compatibility")
    retired_manifest = json.loads((retired / "manifest.json").read_text(encoding="utf-8"))
    require(retired_manifest.get("retired") is True, "legacy Firefox manifest was not retired")
    require("manifest_version" not in retired_manifest, "legacy Firefox tree is still a loadable WebExtension")
    require(retired_manifest.get("canonical_extension_id") == CANONICAL_ID, "legacy Firefox tombstone does not point to the canonical ID")
    legacy_js = list(retired.rglob("*.js"))
    require(legacy_js and all("XFE01_RETIRED_FIREFOX_FORK" in item.read_text(encoding="utf-8") for item in legacy_js), "legacy Firefox executable logic still remains")
    csproj = text("app/XDM/src/XDM.BrowserMedia.Tests/XDM.BrowserMedia.Tests.csproj")
    require("XDM.Android/browser-extension/src/main/extension/xdm-firefox" in csproj, "desktop Firefox fixtures do not consume canonical source")
    require("firefox-amo" not in csproj, "desktop tests still reference the removed Firefox fork")
    parity = text("docs/parity/features.json")
    require("app/XDM/firefox-amo" not in parity, "active parity inventory still points at the removed Firefox fork")


def verify_desktop_adapter() -> None:
    parser = text("app/XDM/src/XDM.BrowserIntegration/FirefoxExtensionHandoffParser.cs")
    require(f'CanonicalExtensionId = "{CANONICAL_ID}"' in parser, "desktop parser canonical extension identity mismatch")
    require('ReleaseScheme = "xdmdownload"' in parser and 'DebugScheme = "xdmdownload-debug"' in parser, "desktop parser scheme mismatch")
    require('action == "add" && version != 1' in parser and 'action == "capture" && version != 3' in parser, "desktop parser does not pin canonical handoff versions")
    require("finalHeaders ?? proposedHeaders" not in parser, "proposed Firefox headers can become runtime authority")
    require("Authorization and Range are intentionally not promoted" in parser, "desktop least-privilege replay guard missing")

    installer = text("app/XDM/src/XDM.BrowserIntegration/BrowserHostInstaller.cs")
    require("FirefoxExtensionHandoffParser.CanonicalExtensionId" in installer, "browser repair does not bind to canonical Firefox ID")
    require("RegisterFirefoxProtocolAsync" in installer, "Firefox custom protocol registration missing")
    require("allowed_extensions" not in installer, "desktop still creates a Firefox native-messaging allow-list")
    require("RemoveLegacyFirefoxNativeMessagingManifest" in installer, "legacy Firefox native-messaging cleanup missing")

    program = text("app/XDM/src/XDM.App/Program.cs")
    coordinator = text("app/XDM/src/XDM.Platform/SingleInstanceCoordinator.cs")
    view_model = text("app/XDM/src/XDM.App/ViewModels/MainWindowViewModel.cs")
    require("FindHandoffArgument(args)" in program and "SignalPrimaryAsync(App.InitialFirefoxHandoffUri)" in program, "desktop process launch does not forward canonical handoff")
    require("MaximumActivationPayloadBytes = 64 * 1024" in coordinator and "ActivationRequestedEventArgs" in coordinator, "single-instance payload bridge missing or unbounded")
    require("HandleFirefoxExtensionHandoffAsync" in view_model and "FirefoxExtensionHandoffParser.TryParse" in view_model, "desktop UI path is not consuming canonical handoffs")


def verify_packaging() -> None:
    desktop = text("app/XDM/packaging/linux/xdm-modern.desktop")
    require("Exec=/opt/xdm/XDM %u" in desktop, "Linux desktop entry does not accept protocol URI")
    require("x-scheme-handler/xdmdownload;" in desktop and "x-scheme-handler/xdmdownload-debug;" in desktop, "Linux desktop entry missing Firefox handoff schemes")
    require(f"X-XDM-FirefoxExtensionId={CANONICAL_ID}" in desktop, "Linux desktop entry canonical ID marker missing")
    require("package-canonical-firefox-extension.py" in text("app/XDM/eng/publish-one.sh"), "Linux publish does not ship canonical Firefox XPI")
    require("package-canonical-firefox-extension.py" in text("app/XDM/eng/publish-one.ps1"), "Windows publish does not ship canonical Firefox XPI")

    with tempfile.TemporaryDirectory(prefix="xfe01-") as temporary:
        xpi = Path(temporary) / "XDM-Firefox.xpi"
        subprocess.run(
            [sys.executable, str(ROOT / "app/XDM/eng/package-canonical-firefox-extension.py"), "--output", str(xpi), "--theme", "dark", "--channel", "release"],
            cwd=ROOT,
            check=True,
            stdout=subprocess.DEVNULL,
        )
        require(xpi.is_file() and xpi.stat().st_size > 0, "canonical Firefox XPI was not produced")
        with zipfile.ZipFile(xpi) as archive:
            names = archive.namelist()
            require(len(names) == 16 and len(names) == len(set(names)), "canonical Firefox XPI inventory is unexpected")
            require(all(".." not in Path(name).parts and not name.startswith("/") for name in names), "canonical Firefox XPI contains unsafe path")
            manifest = json.loads(archive.read("manifest.json"))
            require(manifest["browser_specific_settings"]["gecko"]["id"] == CANONICAL_ID, "packaged Firefox ID diverged")
            require("nativeMessaging" not in manifest.get("permissions", []), "canonical Firefox XPI unexpectedly gained nativeMessaging")


def main() -> int:
    verify_canonical_bytes()
    verify_single_source()
    verify_desktop_adapter()
    verify_packaging()
    print("XFE01 single Firefox extension convergence: PASS")
    print("canonical Firefox source files: 16/16 match current Android-owned baseline")
    print("desktop executable Firefox forks remaining: 0")
    print("desktop handoff contract: add v1 / capture v3")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
