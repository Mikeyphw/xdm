#!/usr/bin/env python3
"""Verify XDM's embedded FFmpeg/FFprobe provenance, ELF ABI/alignment and optional APK payload."""
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
MANIFEST = json.loads((ROOT / "media-ffmpeg/runtime/ffmpeg-runtime.json").read_text(encoding="utf-8"))
LOCK_PATH = ROOT / "media-ffmpeg/runtime/ffmpeg-runtime.lock.json"
SHA256 = re.compile(r"^[0-9a-f]{64}$")
MINIMUM_LOCK_SCHEMA = {"schemaVersion": 2}["schemaVersion"]



def digest_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def digest(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def elf_metadata(data: bytes) -> tuple[int, int, int, str]:
    if len(data) < 64 or data[:4] != b"\x7fELF":
        raise SystemExit("runtime payload is not ELF")
    if data[4] != MANIFEST["elfClass"] or data[5] != MANIFEST["elfData"]:
        raise SystemExit("runtime payload has an unexpected ELF class or byte order")
    endian = "<" if data[5] == 1 else ">"
    elf_type = struct.unpack_from(endian + "H", data, 16)[0]
    machine = struct.unpack_from(endian + "H", data, 18)[0]
    if machine != MANIFEST["elfMachine"]:
        raise SystemExit(f"runtime payload targets ELF machine {machine}, expected {MANIFEST['elfMachine']}")
    if elf_type != MANIFEST["elfType"]:
        raise SystemExit(f"runtime payload ELF type {elf_type}, expected PIE/ET_DYN {MANIFEST['elfType']}")
    return data[4], data[5], machine, endian


def assert_alignment(data: bytes) -> None:
    _, _, _, endian = elf_metadata(data)
    required = int(MANIFEST["requiredLoadAlignment"])
    phoff = struct.unpack_from(endian + "Q", data, 32)[0]
    phentsize = struct.unpack_from(endian + "H", data, 54)[0]
    phnum = struct.unpack_from(endian + "H", data, 56)[0]
    bad: list[str] = []
    for index in range(phnum):
        start = phoff + index * phentsize
        if start + phentsize > len(data):
            raise SystemExit("truncated ELF program header table")
        p_type = struct.unpack_from(endian + "I", data, start)[0]
        if p_type != 1:
            continue
        offset = struct.unpack_from(endian + "Q", data, start + 8)[0]
        vaddr = struct.unpack_from(endian + "Q", data, start + 16)[0]
        align = struct.unpack_from(endian + "Q", data, start + 48)[0]
        if align < required or offset % required != vaddr % required:
            bad.append(f"PT_LOAD[{index}] align=0x{align:x} offset=0x{offset:x} vaddr=0x{vaddr:x}")
    if bad:
        raise SystemExit("runtime is not 16 KB compatible: " + "; ".join(bad))


def validate_payload(name: str, data: bytes, require_alignment: bool) -> dict:
    elf_metadata(data)
    try:
        runtime_metadata = validate_android_runtime_elf(data)
    except ElfPolicyError as error:
        raise SystemExit(f"{name} Android dependency policy failed: {error}") from error
    if len(data) < int(MANIFEST["minimumBinaryBytes"]):
        raise SystemExit(f"{name} is unexpectedly small")
    if require_alignment:
        assert_alignment(data)
    return runtime_metadata


def installed_paths() -> dict[str, Path]:
    return {
        "ffmpeg": ROOT / MANIFEST["ffmpegPackagedPath"],
        "ffprobe": ROOT / MANIFEST["ffprobePackagedPath"],
    }


def verify_installed(required: bool, require_alignment: bool) -> dict | None:
    paths = installed_paths()
    present = {name: path.is_file() for name, path in paths.items()}
    lock_present = LOCK_PATH.is_file()
    if len(set(present.values())) != 1 or next(iter(present.values())) != lock_present:
        raise SystemExit("FFmpeg, FFprobe and runtime lock must all exist together or all be absent")
    if not lock_present:
        if required:
            raise SystemExit("embedded FFmpeg runtime required; run tools/install-ffmpeg-runtime.py --build-pinned")
        print("FFmpeg runtime not installed; source checkout remains valid for native-only development")
        return None
    lock = json.loads(LOCK_PATH.read_text(encoding="utf-8"))
    if int(lock.get("schemaVersion", 0)) < MINIMUM_LOCK_SCHEMA:
        raise SystemExit("runtime lock schemaVersion is older than the v2 toolchain-provenance contract")
    expected_fields = {
        "component": MANIFEST["component"],
        "ffmpegVersion": MANIFEST["ffmpegVersion"],
        "opensslVersion": MANIFEST["opensslVersion"],
        "abi": MANIFEST["abi"],
        "androidApi": MANIFEST["androidApi"],
        "ndkVersion": MANIFEST["ndkVersion"],
        "ffmpegSourceSha256": MANIFEST["ffmpegSourceSha256"],
        "opensslSourceSha256": MANIFEST["opensslSourceSha256"],
        "requiredLoadAlignment": MANIFEST["requiredLoadAlignment"],
        "buildProfile": MANIFEST["buildProfile"],
        "configureFlags": MANIFEST["configureFlags"],
        "gplEnabled": False,
        "nonfreeEnabled": False,
        "dynamicDependencyPolicy": MANIFEST.get("dynamicDependencyPolicy", "android-unversioned-sonames-v1"),
    }
    manifest_ndk_revision = MANIFEST.get("ndkRevision")
    if manifest_ndk_revision is not None:
        expected_fields["ndkRevision"] = manifest_ndk_revision
    for key, value in expected_fields.items():
        if lock.get(key) != value:
            raise SystemExit(f"runtime lock field {key} differs from the pinned manifest")
    if not str(lock.get("ndkRevision", "")).strip():
        raise SystemExit("runtime lock does not record measured NDK revision")
    toolchain_backend = str(lock.get("toolchainBackend", ""))
    if not (toolchain_backend.startswith("ndk:") or toolchain_backend == "termux-native-llvm"):
        raise SystemExit(f"runtime lock has unsupported toolchain backend: {toolchain_backend or '<missing>'}")
    ndk_source_hash = str(lock.get("ndkSourcePropertiesSha256", ""))
    if not SHA256.fullmatch(ndk_source_hash):
        raise SystemExit("runtime lock does not attest the exact NDK source.properties")
    cache_identity_hash = str(lock.get("buildCacheIdentitySha256", ""))
    if not SHA256.fullmatch(cache_identity_hash):
        raise SystemExit("runtime lock does not attest the build-cache identity")
    toolchain_identity = lock.get("toolchainIdentity")
    if not isinstance(toolchain_identity, dict) or toolchain_identity.get("backend") != toolchain_backend:
        raise SystemExit("runtime lock toolchain identity is missing or disagrees with toolchainBackend")
    tools = toolchain_identity.get("tools")
    required_tools = {"clang", "clang++", "llvm-ar", "llvm-ranlib", "llvm-strip", "llvm-nm"}
    if not isinstance(tools, dict) or set(tools) != required_tools:
        raise SystemExit("runtime lock does not attest the complete compiler/binutils set")
    for name, evidence in tools.items():
        if not isinstance(evidence, dict) or not SHA256.fullmatch(str(evidence.get("sha256", ""))):
            raise SystemExit(f"runtime lock has invalid tool digest for {name}")
        if not str(evidence.get("version", "")).strip():
            raise SystemExit(f"runtime lock has no version evidence for {name}")
    license_paths = {
        "ffmpeg": ROOT / MANIFEST["ffmpegLicenseAsset"],
        "openssl": ROOT / MANIFEST["opensslLicenseAsset"],
    }
    for name, path in license_paths.items():
        if not path.is_file():
            raise SystemExit(f"{name} license asset is missing from the attested runtime")
        key = f"{name}LicenseSha256"
        if not SHA256.fullmatch(str(lock.get(key, ""))) or digest(path) != lock[key]:
            raise SystemExit(f"{name} license asset differs from runtime lock")
    for name, path in paths.items():
        data = path.read_bytes()
        runtime_metadata = validate_payload(name, data, require_alignment)
        needed_key = f"{name}NeededLibraries"
        if list(lock.get(needed_key, [])) != list(runtime_metadata["needed"]):
            raise SystemExit(f"{name} DT_NEEDED list differs from runtime lock")
        hash_key = f"{name}BinarySha256"
        bytes_key = f"{name}BinaryBytes"
        actual = digest_bytes(data)
        if not SHA256.fullmatch(str(lock.get(hash_key, ""))) or actual != lock[hash_key]:
            raise SystemExit(f"{name} payload digest differs from runtime lock")
        if len(data) != lock.get(bytes_key):
            raise SystemExit(f"{name} payload size differs from runtime lock")
        budget_key = "maxFfmpegBinaryBytes" if name == "ffmpeg" else "maxFfprobeBinaryBytes"
        if len(data) > int(MANIFEST[budget_key]):
            raise SystemExit(f"{name} payload exceeds {budget_key} budget")
    combined = sum(path.stat().st_size for path in paths.values())
    if combined > int(MANIFEST["maxCombinedBinaryBytes"]):
        raise SystemExit("combined FFmpeg/FFprobe payload exceeds runtime size budget")
    configured = set(lock.get("configureFlags") or [])
    missing_flags = set(MANIFEST["configureFlags"]) - configured
    forbidden_flags = set(MANIFEST["forbiddenConfigureFlags"]) & configured
    if missing_flags or forbidden_flags:
        raise SystemExit(f"runtime configure attestation mismatch; missing={sorted(missing_flags)} forbidden={sorted(forbidden_flags)}")
    print(f"FFmpeg {lock['ffmpegVersion']} + FFprobe runtime verified for {lock['abi']} ({combined} bytes)")
    return lock


def verify_apk(apk: Path, lock: dict, require_alignment: bool) -> None:
    if not apk.is_file():
        raise SystemExit(f"APK not found: {apk}")
    entries = {
        "ffmpeg": "lib/arm64-v8a/libxdm_ffmpeg.so",
        "ffprobe": "lib/arm64-v8a/libxdm_ffprobe.so",
    }
    with zipfile.ZipFile(apk) as zf:
        compressed_runtime_bytes = sum(zf.getinfo(member).compress_size for member in entries.values() if member in zf.namelist())
        if compressed_runtime_bytes > int(MANIFEST["maxCompressedApkRuntimeBytes"]):
            raise SystemExit(f"APK embedded runtime exceeds compressed size budget: {compressed_runtime_bytes} bytes")
        license_entries = {
            "ffmpeg": "assets/licenses/FFmpeg-LGPL-2.1.txt",
            "openssl": "assets/licenses/OpenSSL-Apache-2.0.txt",
        }
        for name, member in license_entries.items():
            try:
                data = zf.read(member)
            except KeyError as error:
                raise SystemExit(f"{member} missing from {apk}") from error
            if digest_bytes(data) != lock[f"{name}LicenseSha256"]:
                raise SystemExit(f"APK {name} license differs from attested runtime asset")
        for name, member in entries.items():
            try:
                data = zf.read(member)
            except KeyError as error:
                raise SystemExit(f"{member} missing from {apk}") from error
            runtime_metadata = validate_payload(name, data, require_alignment)
            if list(lock.get(f"{name}NeededLibraries", [])) != list(runtime_metadata["needed"]):
                raise SystemExit(f"APK {name} DT_NEEDED list differs from the attested runtime")
            if digest_bytes(data) != lock[f"{name}BinarySha256"]:
                raise SystemExit(f"APK {name} differs from attested runtime payload")
    print(f"APK FFmpeg/FFprobe payload verified: {apk} ({compressed_runtime_bytes} compressed bytes)")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--require-payload", action="store_true")
    parser.add_argument("--require-16kb-alignment", action="store_true")
    parser.add_argument("--apk", type=Path)
    args = parser.parse_args()
    try:
        lock = verify_installed(args.require_payload, args.require_16kb_alignment)
    except SystemExit as error:
        if args.require_payload or args.apk:
            raise
        print(f"FFmpeg runtime is installed but stale for source-only validation: {error}")
        lock = None
    if args.apk:
        if lock is None:
            raise SystemExit("APK verification requires an installed/attested runtime")
        verify_apk(args.apk, lock, args.require_16kb_alignment)

if __name__ == "__main__":
    main()
