#!/usr/bin/env python3
from __future__ import annotations

import argparse
import base64
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
EXPECTED_SCHEME = "xdmdownload"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()



def capture_spki_der(value: str) -> bytes:
    encoded = value.strip()
    try:
        der = base64.urlsafe_b64decode(encoded + "=" * ((4 - len(encoded) % 4) % 4))
    except Exception as exc:
        raise SystemExit("release verification capture public key is not valid base64url SPKI data") from exc
    if not der:
        raise SystemExit("release verification capture public key is empty")
    return der


def require_capture_key_binding(key_id: str, spki_base64url: str) -> bytes:
    der = capture_spki_der(spki_base64url)
    derived = hashlib.sha256(der).hexdigest()[:24]
    if key_id.strip() != derived:
        raise SystemExit(f"release verification capture key id does not match SHA-256(SPKI DER).take(24); expected {derived}")
    return der

def inspect_xpi(
    path: Path,
    expected_theme: str,
    expected_version: str,
    expected_app_version: str,
    capture_key_id: str,
    capture_public_key_spki: str,
    capture_oaep_hash: str,
) -> dict[str, object]:
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
        if manifest.get("version") != expected_version:
            raise SystemExit(f"Unexpected extension version in {path.name}")
        permissions = set(manifest.get("permissions", []))
        expected_permissions = {"storage", "tabs", "activeTab", "webRequest", "<all_urls>"}
        if permissions != expected_permissions:
            raise SystemExit(f"Unexpected extension permissions in {path.name}: {sorted(permissions)}")
        config = archive.read("generated-config.js").decode("utf-8")
        theme = archive.read("generated-theme.css").decode("utf-8")
        required_config = (
            f'extensionVersion: "{expected_version}"',
            f'appVersion: "{expected_app_version}"',
            'applicationId: "com.mikeyphw.xdm.android"',
            f'xdmScheme: "{EXPECTED_SCHEME}"',
            f'themeMode: "{expected_theme}"',
            f'captureKeyId: "{capture_key_id}"',
            f'capturePublicKeySpki: "{capture_public_key_spki}"',
            f'captureOaepHash: "{capture_oaep_hash}"',
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
    parser.add_argument("--extension-version", required=True)
    parser.add_argument("--app-version", required=True)
    parser.add_argument("--capture-key-id", required=True)
    parser.add_argument("--capture-public-key-spki", required=True)
    parser.add_argument("--capture-oaep-hash", choices=("SHA-1", "SHA-256"), required=True)
    args = parser.parse_args()
    if not args.capture_key_id.strip() or not args.capture_public_key_spki.strip():
        raise SystemExit("release verification requires non-empty capture public-key configuration")
    capture_spki_der_bytes = require_capture_key_binding(args.capture_key_id, args.capture_public_key_spki)

    output_dir = args.output_dir.resolve()
    dark = output_dir / f"XDM-Android-Firefox-{args.extension_version}-release-dark.xpi"
    amoled = output_dir / f"XDM-Android-Firefox-{args.extension_version}-release-amoled.xpi"
    inspect_args = (args.extension_version, args.app_version, args.capture_key_id, args.capture_public_key_spki, args.capture_oaep_hash)
    artifacts = [inspect_xpi(dark, "dark", *inspect_args), inspect_xpi(amoled, "amoled", *inspect_args)]
    if artifacts[0]["sha256"] == artifacts[1]["sha256"]:
        raise SystemExit("Dark and AMOLED release XPIs must not be byte-identical.")

    metadata = {
        "schemaVersion": 1,
        "extensionId": EXPECTED_ID,
        "extensionVersion": args.extension_version,
        "appVersion": args.app_version,
        "captureKeyId": args.capture_key_id,
        "captureOaepHash": args.capture_oaep_hash,
        "capturePublicKeySha256": hashlib.sha256(capture_spki_der_bytes).hexdigest(),
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
