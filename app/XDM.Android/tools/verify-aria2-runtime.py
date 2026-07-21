#!/usr/bin/env python3
"""Verify source payload attestation and, optionally, its exact APK entry."""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import struct
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = json.loads((ROOT / "transfer-aria2/runtime/aria2-runtime.json").read_text(encoding="utf-8"))
LOCK_PATH = ROOT / "transfer-aria2/runtime/aria2-runtime.lock.json"
TARGET = ROOT / MANIFEST["packagedPath"]
SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")


def digest_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def digest(path: Path) -> str:
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(chunk)
    return value.hexdigest()


def validate_elf(data: bytes) -> None:
    if len(data) < 20 or data[:4] != b"\x7fELF":
        raise SystemExit("packaged aria2 runtime is not an ELF executable")
    if data[4] != MANIFEST["elfClass"] or data[5] != MANIFEST["elfData"]:
        raise SystemExit("packaged aria2 runtime has an unexpected ELF format")
    if struct.unpack("<H", data[18:20])[0] != MANIFEST["elfMachine"]:
        raise SystemExit("packaged aria2 runtime does not target the required Android ABI")
    if len(data) < MANIFEST["minimumBinaryBytes"]:
        raise SystemExit("packaged aria2 runtime is unexpectedly small")


def assert_16kb_alignment(data: bytes) -> None:
    if data[:4] != b"\x7fELF":
        raise SystemExit("packaged aria2 runtime is not an ELF executable")
    endian = "<" if data[5] == 1 else ">"
    if data[4] == 2:
        phoff = struct.unpack_from(endian + "Q", data, 32)[0]
        phentsize = struct.unpack_from(endian + "H", data, 54)[0]
        phnum = struct.unpack_from(endian + "H", data, 56)[0]
        align_offset = 48
        offset_offset = 8
        vaddr_offset = 16
    elif data[4] == 1:
        phoff = struct.unpack_from(endian + "I", data, 28)[0]
        phentsize = struct.unpack_from(endian + "H", data, 42)[0]
        phnum = struct.unpack_from(endian + "H", data, 44)[0]
        align_offset = 28
        offset_offset = 4
        vaddr_offset = 8
    else:
        raise SystemExit("packaged aria2 runtime has an unsupported ELF class")

    bad_segments: list[str] = []
    for index in range(phnum):
        start = phoff + index * phentsize
        if start + phentsize > len(data):
            raise SystemExit("packaged aria2 runtime has truncated ELF program headers")
        program_type = struct.unpack_from(endian + "I", data, start)[0]
        if program_type != 1:  # PT_LOAD
            continue
        if data[4] == 2:
            offset = struct.unpack_from(endian + "Q", data, start + offset_offset)[0]
            vaddr = struct.unpack_from(endian + "Q", data, start + vaddr_offset)[0]
            align = struct.unpack_from(endian + "Q", data, start + align_offset)[0]
        else:
            offset = struct.unpack_from(endian + "I", data, start + offset_offset)[0]
            vaddr = struct.unpack_from(endian + "I", data, start + vaddr_offset)[0]
            align = struct.unpack_from(endian + "I", data, start + align_offset)[0]
        if align < 16_384 or (offset % 16_384) != (vaddr % 16_384):
            bad_segments.append(f"PT_LOAD[{index}] align=0x{align:x} offset=0x{offset:x} vaddr=0x{vaddr:x}")
    if bad_segments:
        raise SystemExit("aria2 runtime is not 16 KB ELF-page aligned: " + "; ".join(bad_segments))


def verify_payload(required: bool, require_16kb_alignment: bool) -> dict | None:
    target_present = TARGET.is_file()
    lock_present = LOCK_PATH.is_file()
    if target_present != lock_present:
        raise SystemExit("aria2 runtime payload and attestation lock must either both exist or both be absent")
    if not target_present:
        if required:
            raise SystemExit("attested ARM64 aria2 runtime is required; run tools/install-aria2-runtime.py")
        print("aria2 runtime not installed; native-only source build remains valid")
        return None

    lock = json.loads(LOCK_PATH.read_text(encoding="utf-8"))
    data = TARGET.read_bytes()
    validate_elf(data)
    if require_16kb_alignment:
        assert_16kb_alignment(data)

    required_metadata = {
        "schemaVersion": 1,
        "component": MANIFEST["component"],
        "version": MANIFEST["version"],
        "releaseTag": MANIFEST["releaseTag"],
        "abi": MANIFEST["abi"],
        "archiveName": MANIFEST["archiveName"],
        "sourceUrl": MANIFEST["officialUrl"],
    }
    for key, expected in required_metadata.items():
        if lock.get(key) != expected:
            raise SystemExit(f"aria2 runtime lock field {key} does not match the manifest")

    archive_hash = str(lock.get("archiveSha256", "")).lower()
    binary_hash = str(lock.get("binarySha256", "")).lower()
    if not SHA256_PATTERN.fullmatch(archive_hash):
        raise SystemExit("aria2 runtime lock has no valid archive SHA-256 attestation")
    if not SHA256_PATTERN.fullmatch(binary_hash):
        raise SystemExit("aria2 runtime lock has no valid binary SHA-256 attestation")
    if lock.get("binarySize") != len(data) or binary_hash != digest(TARGET):
        raise SystemExit("aria2 runtime bytes do not match their source attestation")

    trusted_hash = MANIFEST.get("archiveSha256")
    if trusted_hash and archive_hash != trusted_hash.lower():
        raise SystemExit("aria2 archive SHA-256 differs from the trusted manifest value")

    print(f"aria2 {lock['version']} ARM64 runtime verified: {binary_hash}")
    return lock


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--require-payload", action="store_true")
    parser.add_argument("--require-16kb-alignment", action="store_true")
    parser.add_argument("--apk", type=Path)
    args = parser.parse_args()

    lock = verify_payload(args.require_payload, args.require_16kb_alignment)
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
        validate_elf(data)
        if args.require_16kb_alignment:
            assert_16kb_alignment(data)
        if len(data) != lock["binarySize"] or digest_bytes(data) != lock["binarySha256"]:
            raise SystemExit("APK aria2 payload differs from the attested source payload")
        print(f"APK payload verified: {args.apk}")


if __name__ == "__main__":
    main()
