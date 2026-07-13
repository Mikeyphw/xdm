#!/usr/bin/env python3
"""Create deterministic portable ZIP and tar.gz archives from a publish directory."""
from __future__ import annotations

import argparse
import gzip
import os
import tarfile
import zipfile
from pathlib import Path

NORMALIZED_TIME = (2020, 1, 1, 0, 0, 0)
NORMALIZED_EPOCH = 1577836800


def files(root: Path):
    return sorted(path for path in root.rglob("*") if path.is_file())


def make_zip(source: Path, destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(destination, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for path in files(source):
            relative = path.relative_to(source).as_posix()
            info = zipfile.ZipInfo(relative, NORMALIZED_TIME)
            mode = path.stat().st_mode
            info.external_attr = ((mode & 0o777) or 0o644) << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, path.read_bytes(), compresslevel=9)


def make_tar_gz(source: Path, destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    with destination.open("wb") as raw:
        with gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=NORMALIZED_EPOCH, compresslevel=9) as zipped:
            with tarfile.open(fileobj=zipped, mode="w") as archive:
                for path in files(source):
                    relative = path.relative_to(source).as_posix()
                    info = archive.gettarinfo(str(path), arcname=relative)
                    info.uid = info.gid = 0
                    info.uname = info.gname = "root"
                    info.mtime = NORMALIZED_EPOCH
                    with path.open("rb") as stream:
                        archive.addfile(info, stream)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--name", required=True)
    parser.add_argument("--tar-gz", action="store_true")
    args = parser.parse_args()
    if not args.source.is_dir():
        raise SystemExit(f"Publish directory does not exist: {args.source}")
    make_zip(args.source, args.output / f"{args.name}.zip")
    if args.tar_gz:
        make_tar_gz(args.source, args.output / f"{args.name}.tar.gz")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
