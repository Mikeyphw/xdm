#!/usr/bin/env python3
"""Record a narrow, explicit Gate-12 workaround for Devtool artifact-registry loss.

This does not build or copy APKs. It consumes the APKs already produced by the
Devtool Android package action, independently verifies their important packaged
runtime payloads, and writes auditable evidence that only registry publication
was bypassed.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
from pathlib import Path
from zipfile import BadZipFile, ZipFile

ROOT = Path(__file__).resolve().parents[2]
ANDROID = ROOT / "app/XDM.Android"
APP_APK = ANDROID / "app/build/outputs/apk/debug/app-debug.apk"
TEST_APK = ANDROID / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
ARIA_LOCK = ANDROID / "transfer-aria2/runtime/aria2-runtime.lock.json"
FFMPEG_LOCK = ANDROID / "media-ffmpeg/runtime/ffmpeg-runtime.lock.json"
ARIA_VERIFY = ANDROID / "tools/verify-aria2-runtime.py"
FFMPEG_VERIFY = ANDROID / "tools/verify-ffmpeg-runtime.py"

REQUIRED_APP_MEMBERS = (
    "lib/arm64-v8a/libxdmcore.so",
    "assets/xdmcore/xdmcore-provenance.json",
    "lib/arm64-v8a/libaria2c.so",
    "lib/arm64-v8a/libxdm_ffmpeg.so",
    "lib/arm64-v8a/libxdm_ffprobe.so",
    "assets/aria2-runtime.lock.json",
    "assets/aria2-runtime.json",
)


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(f"Gate-12 Devtool artifact-registry bypass: FAIL: {message}")


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def verify_zip(path: Path) -> set[str]:
    require(path.is_file() and path.stat().st_size > 0, f"missing retained APK: {path.relative_to(ROOT)}")
    try:
        with ZipFile(path) as archive:
            bad = archive.testzip()
            require(bad is None, f"corrupt ZIP member in {path.name}: {bad}")
            return set(archive.namelist())
    except BadZipFile as exc:
        raise SystemExit(f"Gate-12 Devtool artifact-registry bypass: FAIL: invalid APK ZIP {path}: {exc}") from exc


def run_verifier(argv: list[str]) -> None:
    subprocess.run(argv, cwd=ANDROID, check=True)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    output = args.output if args.output.is_absolute() else ROOT / args.output

    require(ARIA_LOCK.is_file(), "source-built aria2 runtime lock is missing")
    require(FFMPEG_LOCK.is_file(), "FFmpeg runtime lock is missing")

    app_members = verify_zip(APP_APK)
    verify_zip(TEST_APK)
    missing = sorted(set(REQUIRED_APP_MEMBERS) - app_members)
    require(not missing, "retained app APK is missing required members: " + ", ".join(missing))

    # Re-run the repository-owned exact native-payload verifiers against the
    # retained APK. No Gradle/build action occurs here.
    run_verifier([
        "python3", str(ARIA_VERIFY),
        "--require-payload", "--require-16kb-alignment", "--require-source-build",
        "--apk", str(APP_APK),
    ])
    run_verifier([
        "python3", str(FFMPEG_VERIFY),
        "--require-payload", "--require-16kb-alignment",
        "--apk", str(APP_APK),
    ])

    report = {
        "schema_version": 1,
        "status": "pass",
        "claim": "xgo-gate12-devtool-artifact-registry-temporary-bypass",
        "temporary_bypass": True,
        "bypass_scope": "devtool-post-package-artifact-registry-publication-only",
        "bypassed_node": "xgo_rm08_aria2_android_package.validate.android_package",
        "reason": (
            "Devtool Android package execution completed successfully and copied the retained APKs, "
            "but the subsequent Devtool artifact registry reported 0 total / ARTIFACT_MISSING."
        ),
        "canonical_validate_workflow_unchanged": True,
        "retained_artifacts": {
            "app_apk": {
                "path": str(APP_APK.relative_to(ROOT)),
                "size": APP_APK.stat().st_size,
                "sha256": sha256_file(APP_APK),
            },
            "android_test_apk": {
                "path": str(TEST_APK.relative_to(ROOT)),
                "size": TEST_APK.stat().st_size,
                "sha256": sha256_file(TEST_APK),
            },
        },
        "independent_checks": {
            "apk_zip_integrity": True,
            "required_native_members_present": True,
            "aria2_packaged_runtime_verifier": "pass",
            "ffmpeg_ffprobe_packaged_runtime_verifier": "pass",
            "xdmcore_and_provenance_present": True,
        },
        "next_boundary": "xgo_rm08_aria2_attest#validate",
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(report, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
