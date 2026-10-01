#!/usr/bin/env python3
"""Verify aria2 source-build provenance and, optionally, the exact APK entry."""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import struct
import zipfile
from pathlib import Path

from android_elf_runtime import ElfPolicyError, validate_android_runtime_elf

ROOT = Path(__file__).resolve().parents[1]
MANIFEST_PATH = ROOT / "transfer-aria2/runtime/aria2-runtime.json"
MANIFEST = json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))
LOCK_PATH = ROOT / "transfer-aria2/runtime/aria2-runtime.lock.json"
TARGET = ROOT / MANIFEST["packagedPath"]
BUILD_SCRIPT = ROOT / "tools/build-aria2-runtime.py"
TOOLCHAIN_AUTHORITY = ROOT / "tools/install-ffmpeg-runtime.py"
SHARED_OPENSSL_AUTHORITY = ROOT / "tools/android_native_openssl.py"
SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")


def digest_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def digest(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def elf_policy() -> dict:
    return {
        "required_load_alignment": int(MANIFEST["requiredLoadAlignment"]),
        "require_pie": True,
        "require_gnu_relro": bool(MANIFEST["requireGnuRelro"]),
        "require_bind_now": bool(MANIFEST["requireBindNow"]),
        "require_non_executable_stack": bool(MANIFEST["requireNonExecutableStack"]),
        "expected_interpreter": MANIFEST["expectedInterpreter"],
    }


def validate_elf(data: bytes, *, strict: bool) -> dict:
    if len(data) < 20 or data[:4] != b"\x7fELF":
        raise SystemExit("packaged aria2 runtime is not an ELF executable")
    if data[4] != MANIFEST["elfClass"] or data[5] != MANIFEST["elfData"]:
        raise SystemExit("packaged aria2 runtime has an unexpected ELF format")
    if struct.unpack("<H", data[18:20])[0] != MANIFEST["elfMachine"]:
        raise SystemExit("packaged aria2 runtime does not target the required Android ABI")
    if len(data) < MANIFEST["minimumBinaryBytes"]:
        raise SystemExit("packaged aria2 runtime is unexpectedly small")
    try:
        metadata = validate_android_runtime_elf(data, **(elf_policy() if strict else {}))
    except ElfPolicyError as error:
        raise SystemExit(f"aria2 Android ELF policy failed: {error}") from error
    if strict:
        require_dynamic_bionic_linkage(metadata, label="packaged aria2 runtime")
    return metadata


def require_dynamic_bionic_linkage(metadata: dict, *, label: str) -> None:
    needed = list(metadata.get("needed") or [])
    if "libc.so" not in needed:
        raise SystemExit(
            f"{label} is missing required Android/Bionic libc.so DT_NEEDED linkage: {needed!r}"
        )
    if "libc++_shared.so" in needed:
        raise SystemExit(
            f"{label} unexpectedly depends on libc++_shared.so; the RM08 C++ runtime must be embedded statically"
        )


def require_sha(value: object, name: str) -> str:
    text = str(value or "").lower()
    if not SHA256_PATTERN.fullmatch(text):
        raise SystemExit(f"aria2 runtime lock has no valid {name} SHA-256")
    return text


def verify_source_lock(lock: dict, metadata: dict) -> None:
    if lock.get("schemaVersion") != 3:
        raise SystemExit("release-qualified aria2 lock must use schemaVersion 3")
    expected = {
        "component": MANIFEST["component"], "version": MANIFEST["version"], "releaseTag": MANIFEST["releaseTag"],
        "abi": MANIFEST["abi"], "androidApi": MANIFEST["androidApi"], "ndkVersion": MANIFEST["ndkVersion"],
        "buildProfile": MANIFEST["buildProfile"], "sourceKind": "source-build",
        "dynamicDependencyPolicy": MANIFEST["dynamicDependencyPolicy"],
        "sources": MANIFEST["sources"], "configureFlags": MANIFEST["configureFlags"],
    }
    for key, value in expected.items():
        if lock.get(key) != value:
            raise SystemExit(f"aria2 source-build lock field {key} does not match the manifest")
    if require_sha(lock.get("buildRecipeSha256"), "build recipe") != digest(BUILD_SCRIPT):
        raise SystemExit("aria2 build recipe changed after the runtime was built")
    if require_sha(lock.get("toolchainAuthoritySha256"), "toolchain authority") != digest(TOOLCHAIN_AUTHORITY):
        raise SystemExit("aria2 native toolchain authority changed after the runtime was built")
    if require_sha(lock.get("sharedOpenSslAuthoritySha256"), "shared OpenSSL authority") != digest(SHARED_OPENSSL_AUTHORITY):
        raise SystemExit("aria2 shared OpenSSL authority changed after the runtime was built")
    shared = lock.get("sharedOpenSsl") or {}
    if shared.get("component") != "xdm-android-shared-openssl":
        raise SystemExit("aria2 source-build lock is missing shared OpenSSL provenance")
    identity = shared.get("dependencyIdentity") or {}
    openssl = MANIFEST["sources"]["openssl"]
    expected_shared = {
        "version": openssl["version"],
        "sourceSha256": openssl["sha256"],
        "androidApi": MANIFEST["androidApi"],
        "ndkVersion": MANIFEST["ndkVersion"],
        "toolchainIdentity": lock.get("toolchainIdentity"),
    }
    for key, value in expected_shared.items():
        if identity.get(key) != value:
            raise SystemExit(f"aria2 shared OpenSSL identity mismatch for {key}")
    shared_identity_sha = hashlib.sha256(json.dumps(identity, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
    if require_sha(shared.get("dependencyIdentitySha256"), "shared OpenSSL identity") != shared_identity_sha:
        raise SystemExit("aria2 shared OpenSSL identity digest is stale")
    require_sha(shared.get("libsslSha256"), "shared libssl")
    require_sha(shared.get("libcryptoSha256"), "shared libcrypto")
    require_sha(lock.get("buildCacheIdentitySha256"), "build cache identity")
    require_sha(lock.get("ndkSourcePropertiesSha256"), "NDK source.properties")
    if lock.get("ndkRevision") != MANIFEST["ndkVersion"]:
        raise SystemExit("aria2 source-build lock measured NDK revision differs from the pinned NDK")
    tools = (lock.get("toolchainIdentity") or {}).get("tools") or {}
    if not tools:
        raise SystemExit("aria2 source-build lock has no measured native toolchain")
    for name, info in tools.items():
        require_sha((info or {}).get("sha256"), f"toolchain {name}")
        if not (info or {}).get("version"):
            raise SystemExit(f"aria2 toolchain fingerprint has no version for {name}")
    compiler = lock.get("compilerIdentity") or {}
    if not compiler.get("backend"):
        raise SystemExit("aria2 source-build lock has no measured compiler backend")
    cc = compiler.get("cc") or {}
    require_sha(cc.get("sha256"), "C compiler")
    if not cc.get("version"):
        raise SystemExit("aria2 source-build lock C compiler fingerprint has no version")
    cxx = compiler.get("cxx")
    if isinstance(cxx, dict):
        require_sha(cxx.get("sha256"), "C++ compiler")
        if not cxx.get("version"):
            raise SystemExit("aria2 source-build lock C++ compiler fingerprint has no version")
    security = lock.get("elfSecurity") or {}
    expected_security = {
        "elfType": MANIFEST["elfType"],
        "requiredLoadAlignment": MANIFEST["requiredLoadAlignment"],
        "gnuRelro": True,
        "bindNow": True,
        "nonExecutableStack": True,
    }
    if security != expected_security:
        raise SystemExit("aria2 source-build lock ELF security metadata is incomplete/stale")
    if list(lock.get("neededLibraries", [])) != list(metadata["needed"]):
        raise SystemExit("aria2 runtime lock DT_NEEDED list differs from the packaged ELF")
    if lock.get("elfInterpreter") != metadata["interpreter"]:
        raise SystemExit("aria2 runtime lock ELF interpreter differs from the packaged ELF")


def verify_payload(required: bool, strict: bool, require_source_build: bool) -> dict | None:
    target_present, lock_present = TARGET.is_file(), LOCK_PATH.is_file()
    if target_present != lock_present:
        raise SystemExit("aria2 runtime payload and attestation lock must either both exist or both be absent")
    if not target_present:
        if required:
            raise SystemExit("attested ARM64 aria2 runtime is required; run tools/build-aria2-runtime.py --build-pinned")
        print("aria2 runtime not installed; source-only validation remains valid")
        return None

    lock = json.loads(LOCK_PATH.read_text(encoding="utf-8"))
    data = TARGET.read_bytes()
    metadata = validate_elf(data, strict=strict)
    binary_hash = require_sha(lock.get("binarySha256"), "binary")
    if lock.get("binarySize") != len(data) or binary_hash != digest(TARGET):
        raise SystemExit("aria2 runtime bytes do not match their attestation lock")

    if require_source_build:
        verify_source_lock(lock, metadata)
    else:
        # Compatibility installs remain useful for local development, but must never be
        # accepted by a release-qualified verifier.
        if lock.get("sourceKind") == "source-build":
            verify_source_lock(lock, metadata)
        elif lock.get("sourceKind") not in {"raw-elf", "zip", "prebuilt-compatibility"}:
            raise SystemExit("aria2 runtime lock has an unknown sourceKind")

    notice_paths = {"license": ROOT / MANIFEST["licenseAsset"], "sourceNotice": ROOT / MANIFEST["sourceNoticeAsset"]}
    for name, path in notice_paths.items():
        if not path.is_file():
            raise SystemExit(f"aria2 {name} asset is missing: {path}")
        if require_sha(lock.get(f"{name}Sha256"), name) != digest(path):
            raise SystemExit(f"aria2 {name} asset differs from runtime lock")

    print(f"aria2 {lock['version']} ARM64 runtime verified: {binary_hash} ({lock.get('sourceKind')})")
    return lock


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--require-payload", action="store_true")
    parser.add_argument("--require-16kb-alignment", action="store_true")
    parser.add_argument("--require-source-build", action="store_true")
    parser.add_argument("--apk", type=Path)
    parser.add_argument("--success-marker", type=Path)
    # Retained as a compatibility parser surface for historical scripts. It does not make
    # a third-party prebuilt release-eligible under schema v3.
    parser.add_argument("--expected-archive-sha256", default=None)
    parser.add_argument("--require-trusted-archive-digest", action="store_true")
    args = parser.parse_args()
    if args.require_trusted_archive_digest:
        raise SystemExit("third-party archive-digest qualification is obsolete; use --require-source-build")

    strict = args.require_16kb_alignment or args.require_source_build
    try:
        lock = verify_payload(args.require_payload, strict, args.require_source_build)
    except SystemExit as error:
        if args.require_payload or args.apk or args.require_source_build:
            raise
        print(f"aria2 runtime is installed but stale for source-only validation: {error}")
        lock = None

    if args.apk:
        if lock is None:
            raise SystemExit("cannot inspect APK without an installed runtime")
        if not args.apk.is_file():
            raise SystemExit(f"APK not found: {args.apk}")
        with zipfile.ZipFile(args.apk) as apk:
            member = "lib/arm64-v8a/libaria2c.so"
            try:
                data = apk.read(member)
            except KeyError as error:
                raise SystemExit(f"{member} is missing from {args.apk}") from error
            notices = {"license": "assets/licenses/GPL-2.0.txt", "sourceNotice": "assets/licenses/SOURCE-NOTICE.txt"}
            for name, member_name in notices.items():
                try:
                    notice_data = apk.read(member_name)
                except KeyError as error:
                    raise SystemExit(f"{member_name} is missing from {args.apk}") from error
                if digest_bytes(notice_data) != lock[f"{name}Sha256"]:
                    raise SystemExit(f"APK aria2 {name} differs from the attested notice asset")
        metadata = validate_elf(data, strict=strict)
        if list(lock.get("neededLibraries", [])) != list(metadata["needed"]):
            raise SystemExit("APK aria2 DT_NEEDED list differs from the attested runtime")
        if len(data) != lock["binarySize"] or digest_bytes(data) != lock["binarySha256"]:
            raise SystemExit("APK aria2 payload differs from the attested source-built payload")
        print(f"APK source-built aria2 payload verified: {args.apk}")

    if args.success_marker:
        args.success_marker.parent.mkdir(parents=True, exist_ok=True)
        args.success_marker.write_text("ok\n", encoding="utf-8")


if __name__ == "__main__":
    main()
