#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
import tempfile
import zipfile
from pathlib import Path

REQUIRED_FILES = {
    "bridge-selftest.js",
    "candidate-store.js",
    "detector-core.js",
    "extension.css",
    "fab.js",
    "frame-bridge.js",
    "generated-config.js",
    "generated-theme.css",
    "handoff.js",
    "icons/icon48.png",
    "icons/icon96.png",
    "manifest.json",
    "network-observer.js",
    "page-sniffer.js",
    "popup.html",
    "popup.js",
}
FIXED_ZIP_DATE = (1980, 1, 1, 0, 0, 0)
EXPECTED_ID = "xdm-android-media-bridge@mikeyphw"
EXPECTED_VERSION = "1.1.0"
EXPECTED_SCHEME = "xdmdownload"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def inspect_xpi(path: Path, expected_theme: str) -> dict[str, object]:
    if not path.is_file() or path.stat().st_size <= 0:
        raise SystemExit(f"Missing release XPI: {path}")
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        if names != sorted(names):
            raise SystemExit(f"XPI inventory is not sorted: {path.name}")
        if set(names) != REQUIRED_FILES:
            missing = sorted(REQUIRED_FILES - set(names))
            extra = sorted(set(names) - REQUIRED_FILES)
            raise SystemExit(f"Unexpected XPI inventory for {path.name}: missing={missing}, extra={extra}")
        for info in archive.infolist():
            if info.date_time != FIXED_ZIP_DATE:
                raise SystemExit(f"Non-deterministic ZIP timestamp in {path.name}: {info.filename} {info.date_time}")
            if info.filename.startswith("/") or ".." in Path(info.filename).parts:
                raise SystemExit(f"Unsafe XPI entry: {info.filename}")
        manifest = json.loads(archive.read("manifest.json"))
        gecko = manifest.get("browser_specific_settings", {}).get("gecko", {})
        if gecko.get("id") != EXPECTED_ID:
            raise SystemExit(f"Unexpected extension id in {path.name}")
        if manifest.get("version") != EXPECTED_VERSION:
            raise SystemExit(f"Unexpected extension version in {path.name}")
        permissions = set(manifest.get("permissions", []))
        expected_permissions = {"storage", "tabs", "activeTab", "webRequest", "<all_urls>"}
        if permissions != expected_permissions:
            raise SystemExit(f"Unexpected extension permissions in {path.name}: {sorted(permissions)}")
        config = archive.read("generated-config.js").decode("utf-8")
        theme = archive.read("generated-theme.css").decode("utf-8")
        required_config = (
            f'extensionVersion: "{EXPECTED_VERSION}"',
            'applicationId: "com.mikeyphw.xdm.android"',
            f'xdmScheme: "{EXPECTED_SCHEME}"',
            f'themeMode: "{expected_theme}"',
        )
        for needle in required_config:
            if needle not in config:
                raise SystemExit(f"Generated config mismatch in {path.name}: {needle}")
        if f"--xdm-theme-mode: {expected_theme};" not in theme:
            raise SystemExit(f"Generated theme mismatch in {path.name}")
        if expected_theme == "amoled" and "--xdm-bg: #000000;" not in theme:
            raise SystemExit(f"AMOLED package does not use a black background: {path.name}")
        if expected_theme == "dark" and "--xdm-bg: #090B0F;" not in theme:
            raise SystemExit(f"Dark package does not use the XDM dark background: {path.name}")
    return {
        "file": path.name,
        "theme": expected_theme,
        "bytes": path.stat().st_size,
        "sha256": sha256(path),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Verify deterministic XDM Firefox release XPIs.")
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--metadata", type=Path, required=True)
    args = parser.parse_args()

    output_dir = args.output_dir.resolve()
    dark = output_dir / "XDM-Android-Firefox-1.1.0-release-dark.xpi"
    amoled = output_dir / "XDM-Android-Firefox-1.1.0-release-amoled.xpi"
    artifacts = [inspect_xpi(dark, "dark"), inspect_xpi(amoled, "amoled")]
    if artifacts[0]["sha256"] == artifacts[1]["sha256"]:
        raise SystemExit("Dark and AMOLED release XPIs must not be byte-identical.")

    metadata = {
        "schemaVersion": 1,
        "extensionId": EXPECTED_ID,
        "extensionVersion": EXPECTED_VERSION,
        "appVersion": "0.20.0-rc08",
        "applicationId": "com.mikeyphw.xdm.android",
        "scheme": EXPECTED_SCHEME,
        "fixedZipTimestamp": "1980-01-01T00:00:00Z",
        "artifacts": artifacts,
    }
    args.metadata.parent.mkdir(parents=True, exist_ok=True)
    args.metadata.write_text(json.dumps(metadata, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(metadata, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
