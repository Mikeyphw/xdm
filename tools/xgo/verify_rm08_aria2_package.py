#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
from pathlib import Path
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[2]
ANDROID = ROOT / "app/XDM.Android"
LOCK = ANDROID / "transfer-aria2/runtime/aria2-runtime.lock.json"
VERIFY = ANDROID / "tools/verify-aria2-runtime.py"
MEMBER = "lib/arm64-v8a/libaria2c.so"
LOCK_MEMBER = "assets/aria2-runtime.lock.json"
MANIFEST_MEMBER = "assets/aria2-runtime.json"


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(message)


def main() -> int:
    parser = argparse.ArgumentParser(description="Attest the exact source-built aria2 bytes packaged in the Gate-12 Android APK")
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--test-apk", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    apk = args.apk if args.apk.is_absolute() else ROOT / args.apk
    test_apk = args.test_apk if args.test_apk.is_absolute() else ROOT / args.test_apk
    output = args.output if args.output.is_absolute() else ROOT / args.output
    require(apk.is_file() and apk.stat().st_size > 0, f"missing Gate-12 app APK: {apk}")
    require(test_apk.is_file() and test_apk.stat().st_size > 0, f"missing Gate-12 androidTest APK: {test_apk}")
    require(LOCK.is_file(), "source-built aria2 runtime lock is missing; Gate-12 must materialize the pinned runtime before packaging")

    subprocess.run(
        [
            "python3", str(VERIFY),
            "--require-payload", "--require-16kb-alignment", "--require-source-build",
            "--apk", str(apk),
        ],
        cwd=ANDROID,
        check=True,
    )

    lock = json.loads(LOCK.read_text(encoding="utf-8"))
    require(lock.get("schemaVersion") == 3, "aria2 lock schema is not RM07 source-build schema v3")
    require(lock.get("sourceKind") == "source-build", "Gate-12 APK must use XGO-owned source-built aria2 bytes")
    require(lock.get("buildProfile") == "android-arm64-source-v1", "unexpected aria2 source-build profile")
    require(lock.get("dynamicDependencyPolicy") == "android-unversioned-sonames-v1", "unexpected aria2 dependency policy")

    with ZipFile(apk) as archive:
        names = set(archive.namelist())
        for member in (MEMBER, LOCK_MEMBER, MANIFEST_MEMBER):
            require(member in names, f"Gate-12 APK is missing {member}")
        packaged_binary = archive.read(MEMBER)
        packaged_lock = json.loads(archive.read(LOCK_MEMBER).decode("utf-8"))
        packaged_manifest = json.loads(archive.read(MANIFEST_MEMBER).decode("utf-8"))

    require(packaged_lock == lock, "APK aria2 runtime lock differs from the source-tree attestation lock")
    packaged_hash = sha256_bytes(packaged_binary)
    require(packaged_hash == lock.get("binarySha256"), "APK aria2 bytes do not match the source-build lock")
    require(len(packaged_binary) == int(lock.get("binarySize") or 0), "APK aria2 byte size differs from source-build lock")
    require(packaged_manifest.get("schemaVersion") == 3, "APK aria2 manifest is not schema v3")
    require(packaged_manifest.get("buildProfile") == "android-arm64-source-v1", "APK aria2 manifest lost source-build profile")

    report = {
        "schema_version": 1,
        "status": "pass",
        "claim": "xgo-rm08-packaged-aria2",
        "app_apk": str(apk.relative_to(ROOT)),
        "app_apk_sha256": sha256_file(apk),
        "android_test_apk": str(test_apk.relative_to(ROOT)),
        "android_test_apk_sha256": sha256_file(test_apk),
        "packaged_member": MEMBER,
        "binary_sha256": packaged_hash,
        "binary_size": len(packaged_binary),
        "source_kind": lock.get("sourceKind"),
        "build_profile": lock.get("buildProfile"),
        "runtime_version": lock.get("version"),
        "ndk_version": lock.get("ndkVersion"),
        "dynamic_dependency_policy": lock.get("dynamicDependencyPolicy"),
        "needed_libraries": lock.get("neededLibraries") or [],
        "elf_security": lock.get("elfSecurity") or {},
        "binary_hash_matches_lock": True,
        "packaged_lock_matches_source": True,
        "source_build": True,
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(report, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
