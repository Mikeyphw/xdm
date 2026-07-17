#!/usr/bin/env python3
"""Install and attest the official ARM64 Android aria2 runtime."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import struct
import tempfile
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MANIFEST_PATH = ROOT / "transfer-aria2/runtime/aria2-runtime.json"
LOCK_PATH = ROOT / "transfer-aria2/runtime/aria2-runtime.lock.json"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def validate_elf(path: Path, manifest: dict) -> None:
    with path.open("rb") as stream:
        header = stream.read(64)
    if len(header) < 20 or header[:4] != b"\x7fELF":
        raise SystemExit("aria2c is not an ELF executable")
    if header[4] != manifest["elfClass"]:
        raise SystemExit("aria2c does not use the required ELF class")
    if header[5] != manifest["elfData"]:
        raise SystemExit("aria2c does not use the required ELF byte order")
    machine = struct.unpack("<H", header[18:20])[0]
    if machine != manifest["elfMachine"]:
        raise SystemExit(f"aria2c ELF machine {machine} does not match the required Android ABI")
    if path.stat().st_size < manifest["minimumBinaryBytes"]:
        raise SystemExit("aria2c payload is unexpectedly small")


def atomic_json_write(path: Path, value: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
            json.dump(value, stream, indent=2)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        temporary.replace(path)
    finally:
        temporary.unlink(missing_ok=True)


def main() -> None:
    parser = argparse.ArgumentParser()
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--archive", type=Path)
    source.add_argument("--download-official", action="store_true")
    parser.add_argument("--expected-archive-sha256")
    args = parser.parse_args()

    manifest = json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))
    download_directory: Path | None = None
    extracted: Path | None = None
    try:
        archive = args.archive
        if args.download_official:
            download_directory = Path(tempfile.mkdtemp(prefix="xdm-aria2-download-"))
            archive = download_directory / manifest["archiveName"]
            request = urllib.request.Request(
                manifest["officialUrl"],
                headers={"User-Agent": "XDM-Android-runtime-installer/1"},
            )
            with urllib.request.urlopen(request, timeout=120) as response, archive.open("wb") as output:
                shutil.copyfileobj(response, output)

        assert archive is not None
        archive = archive.resolve()
        if not archive.is_file():
            raise SystemExit(f"archive not found: {archive}")

        archive_hash = sha256(archive)
        expected_hash = args.expected_archive_sha256 or manifest.get("archiveSha256")
        if expected_hash and archive_hash.lower() != expected_hash.lower():
            raise SystemExit("official archive SHA-256 does not match the trusted value")

        target = ROOT / manifest["packagedPath"]
        target.parent.mkdir(parents=True, exist_ok=True)
        descriptor, extracted_name = tempfile.mkstemp(prefix=".libaria2c.", dir=target.parent)
        os.close(descriptor)
        extracted = Path(extracted_name)

        with zipfile.ZipFile(archive) as source_zip:
            member = manifest["archiveMember"]
            try:
                info = source_zip.getinfo(member)
            except KeyError as error:
                candidates = [name for name in source_zip.namelist() if name.rstrip("/").endswith("/aria2c")]
                raise SystemExit(f"expected archive member missing; candidates: {candidates}") from error
            if info.is_dir():
                raise SystemExit("expected aria2c archive member is a directory")
            if info.file_size < manifest["minimumBinaryBytes"]:
                raise SystemExit("aria2c archive member is unexpectedly small")
            with source_zip.open(info) as input_stream, extracted.open("wb") as output:
                shutil.copyfileobj(input_stream, output)
                output.flush()
                os.fsync(output.fileno())

        extracted.chmod(0o755)
        validate_elf(extracted, manifest)
        binary_hash = sha256(extracted)
        binary_size = extracted.stat().st_size
        extracted.replace(target)
        extracted = None

        lock = {
            "schemaVersion": 1,
            "component": manifest["component"],
            "version": manifest["version"],
            "releaseTag": manifest["releaseTag"],
            "abi": manifest["abi"],
            "archiveName": manifest["archiveName"],
            "archiveSha256": archive_hash,
            "binarySha256": binary_hash,
            "binarySize": binary_size,
            "sourceUrl": manifest["officialUrl"],
        }
        atomic_json_write(LOCK_PATH, lock)
        print(f"Installed {target.relative_to(ROOT)}")
        print(f"Archive SHA-256: {archive_hash}")
        print(f"Binary SHA-256: {binary_hash}")
    finally:
        if extracted is not None:
            extracted.unlink(missing_ok=True)
        if download_directory is not None:
            shutil.rmtree(download_directory, ignore_errors=True)


if __name__ == "__main__":
    main()
