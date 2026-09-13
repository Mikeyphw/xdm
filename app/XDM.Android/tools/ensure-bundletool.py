#!/usr/bin/env python3
"""Provision XDM's pinned bundletool jar with content-addressed verification."""
from __future__ import annotations

import argparse
import hashlib
import os
import shutil
import tempfile
import urllib.request
from pathlib import Path

VERSION = "1.18.3"
SHA256 = "a099cfa1543f55593bc2ed16a70a7c67fe54b1747bb7301f37fdfd6d91028e29"
URL = f"https://github.com/google/bundletool/releases/download/{VERSION}/bundletool-all-{VERSION}.jar"


def digest(path: Path) -> str:
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(chunk)
    return value.hexdigest()


def default_path() -> Path:
    configured = os.environ.get("XDM_BUNDLETOOL_CACHE") or os.environ.get("XDG_CACHE_HOME")
    base = Path(configured).expanduser() if configured else Path.home() / ".cache"
    if base.name != "bundletool":
        base = base / "xdm" / "bundletool"
    return base / f"bundletool-all-{VERSION}.jar"


def verify(path: Path) -> None:
    if not path.is_file():
        raise SystemExit(f"bundletool not found: {path}")
    actual = digest(path)
    if actual != SHA256:
        raise SystemExit(f"bundletool SHA-256 mismatch: expected {SHA256}, got {actual}")


def provision(path: Path, offline: bool) -> Path:
    path = path.expanduser().resolve()
    if path.exists():
        try:
            verify(path)
            return path
        except SystemExit:
            if offline:
                raise
            path.unlink(missing_ok=True)
    if offline:
        raise SystemExit(f"pinned bundletool {VERSION} is not present in the offline cache: {path}")
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, raw = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    os.close(fd)
    temp = Path(raw)
    try:
        request = urllib.request.Request(URL, headers={"User-Agent": "XDM-Android-bundletool-provisioner/1"})
        with urllib.request.urlopen(request, timeout=180) as response, temp.open("wb") as output:
            shutil.copyfileobj(response, output)
            output.flush()
            os.fsync(output.fileno())
        verify(temp)
        temp.replace(path)
    finally:
        temp.unlink(missing_ok=True)
    return path


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--path", type=Path, default=None, help="use/verify this jar path instead of the XDM cache")
    parser.add_argument("--offline", action="store_true", help="do not download if the pinned jar is absent")
    parser.add_argument("--print-path", action="store_true", help="print the verified jar path")
    parser.add_argument("--verify-only", action="store_true", help="verify --path without provisioning")
    args = parser.parse_args()
    path = args.path or default_path()
    if args.verify_only:
        verify(path.expanduser().resolve())
        resolved = path.expanduser().resolve()
    else:
        resolved = provision(path, args.offline)
    if args.print_path or not args.verify_only:
        print(resolved)


if __name__ == "__main__":
    main()
