#!/usr/bin/env python3
"""Verify that the Gate-12 target APK and androidTest APK share signing certificates."""
from __future__ import annotations

from argparse import ArgumentParser
from pathlib import Path
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
ANDROID = ROOT / "app/XDM.Android"
APP_APK = ANDROID / "app/build/outputs/apk/debug/app-debug.apk"
TEST_APK = ANDROID / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
LOCAL_PROPERTIES = ANDROID / "local.properties"
DEFAULT_OUTPUT = ROOT / ".devtool/reports/xgo/gates/final-continue/android-apk-signers.json"
DIGEST_RE = re.compile(r"certificate SHA-256 digest:\s*([0-9A-Fa-f:]+)")


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def sdk_roots() -> list[Path]:
    roots: list[Path] = []
    for key in ("ANDROID_SDK_ROOT", "ANDROID_HOME"):
        value = os.environ.get(key, "").strip()
        if value:
            roots.append(Path(value).expanduser())
    if LOCAL_PROPERTIES.is_file():
        for raw in LOCAL_PROPERTIES.read_text(encoding="utf-8", errors="replace").splitlines():
            if raw.startswith("sdk.dir="):
                value = raw.split("=", 1)[1].strip().replace(r"\:", ":").replace(r"\\", "\\")
                if value:
                    roots.append(Path(value).expanduser())
    roots.extend([
        Path.home() / ".local/share/devtool/android-sdk",
        Path.home() / "Android/Sdk",
    ])
    out: list[Path] = []
    seen: set[str] = set()
    for root in roots:
        key = str(root.resolve(strict=False))
        if key not in seen:
            seen.add(key)
            out.append(root)
    return out


def version_key(name: str) -> tuple[int, ...]:
    parts = [int(x) for x in re.findall(r"\d+", name)]
    return tuple(parts) if parts else (0,)


def find_apksigner(explicit: str = "") -> Path:
    candidates: list[Path] = []
    if explicit:
        candidates.append(Path(explicit).expanduser())
    env = os.environ.get("APKSIGNER", "").strip()
    if env:
        candidates.append(Path(env).expanduser())
    found = shutil.which("apksigner")
    if found:
        candidates.append(Path(found))
    for sdk in sdk_roots():
        build_tools = sdk / "build-tools"
        if not build_tools.is_dir():
            continue
        versions = sorted((p for p in build_tools.iterdir() if p.is_dir()), key=lambda p: version_key(p.name), reverse=True)
        candidates.extend(p / "apksigner" for p in versions)
    seen: set[str] = set()
    for candidate in candidates:
        path = candidate.resolve(strict=False)
        key = str(path)
        if key in seen:
            continue
        seen.add(key)
        if path.is_file() and os.access(path, os.X_OK):
            return path
    raise RuntimeError("apksigner is unavailable; cannot prove target/test APK signer compatibility")


def signer_digests(apksigner: Path, apk: Path) -> tuple[str, ...]:
    if not apk.is_file():
        raise RuntimeError(f"APK is missing: {apk}")
    proc = subprocess.run(
        [str(apksigner), "verify", "--print-certs", str(apk)],
        text=True,
        capture_output=True,
        check=False,
    )
    combined = "\n".join(part for part in (proc.stdout, proc.stderr) if part)
    if proc.returncode != 0:
        raise RuntimeError(f"apksigner verification failed for {apk}: {combined.strip()}")
    digests = sorted({m.group(1).replace(":", "").lower() for m in DIGEST_RE.finditer(combined)})
    if not digests:
        raise RuntimeError(f"apksigner did not report a SHA-256 signer digest for {apk}")
    return tuple(digests)


def main() -> int:
    parser = ArgumentParser()
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--apksigner", default="")
    args = parser.parse_args()
    output = args.output if args.output.is_absolute() else ROOT / args.output
    output.parent.mkdir(parents=True, exist_ok=True)
    payload: dict[str, object] = {
        "schema_version": 1,
        "claim": "xgo-gate12-compatible-apk-signers",
        "status": "failed",
    }
    try:
        apksigner = find_apksigner(args.apksigner)
        app_signers = signer_digests(apksigner, APP_APK)
        test_signers = signer_digests(apksigner, TEST_APK)
        app_hash = sha256(APP_APK)
        test_hash = sha256(TEST_APK)
        if app_signers != test_signers:
            raise RuntimeError(
                "Gate-12 APK signing mismatch: target and androidTest APKs do not share the same signing certificate; "
                f"app={list(app_signers)} test={list(test_signers)}"
            )
        payload.update({
            "status": "pass",
            "apksigner": str(apksigner),
            "signer_sha256": list(app_signers),
            "app_apk": {"path": str(APP_APK.relative_to(ROOT)), "sha256": app_hash},
            "android_test_apk": {"path": str(TEST_APK.relative_to(ROOT)), "sha256": test_hash},
        })
        output.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        print(
            "Gate-12 APK signer compatibility: PASS "
            f"({','.join(app_signers)})"
        )
        return 0
    except Exception as exc:
        payload["error"] = str(exc)
        output.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        print(f"Gate-12 APK signer compatibility: FAIL: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
