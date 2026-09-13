#!/usr/bin/env python3
"""Generate deterministic checksums and channel-aware XDM update metadata."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
from pathlib import Path
from urllib.parse import quote

RID_MARKERS = ("linux-x64", "linux-arm64", "win-x64", "win-arm64")
PACKAGE_SUFFIXES = (".zip", ".tar.gz", ".deb", ".rpm", ".AppImage", ".appimage")
SEMVER_PATTERN = re.compile(r"^[0-9]+\.[0-9]+(?:\.[0-9]+)?(?:-[0-9A-Za-z][0-9A-Za-z.-]*)?(?:\+[0-9A-Za-z][0-9A-Za-z.-]*)?$")
FULL_SHA_PATTERN = re.compile(r"^[0-9a-fA-F]{40}$")


def digest(path: Path, algorithm: str) -> str:
    value = hashlib.new(algorithm)
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(block)
    return value.hexdigest().upper()


def runtime_id(name: str) -> str | None:
    return next((rid for rid in RID_MARKERS if rid in name), None)


def require_semver(value: str, field: str) -> None:
    if not SEMVER_PATTERN.match(value):
        raise SystemExit(f"{field} must be a semantic version: {value}")


def require_commit_sha(value: str, field: str) -> None:
    if not FULL_SHA_PATTERN.match(value):
        raise SystemExit(f"{field} must be a full 40-character commit SHA: {value}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--packages", type=Path, required=True)
    parser.add_argument("--channel", choices=("stable", "nightly"), required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--release-notes-url", required=True)
    parser.add_argument("--published-at", required=True)
    parser.add_argument("--minimum-supported-version", default="")
    parser.add_argument("--commit-sha", default=os.environ.get("GITHUB_SHA", ""))
    parser.add_argument("--release-tag", default=os.environ.get("GITHUB_REF_NAME", ""))
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    require_semver(args.version, "--version")
    if args.minimum_supported_version:
        require_semver(args.minimum_supported_version, "--minimum-supported-version")
    if args.channel == "stable":
        require_commit_sha(args.commit_sha, "--commit-sha")
        expected_tag = f"v{args.version}"
        if args.release_tag != expected_tag:
            raise SystemExit(f"stable release metadata must be generated from tag {expected_tag}; got {args.release_tag!r}")
    elif args.commit_sha:
        require_commit_sha(args.commit_sha, "--commit-sha")

    excluded_metadata = {
        "SHA256SUMS",
        "SHA512SUMS",
        "release-metadata.json",
        f"xdm-update-{args.channel}.json",
    }
    artifacts = sorted(
        path for path in args.packages.iterdir()
        if path.is_file() and path.name not in excluded_metadata
    )
    if not artifacts:
        raise SystemExit("No release packages were found.")

    sha256_lines: list[str] = []
    sha512_lines: list[str] = []
    packages: list[dict[str, object]] = []
    for artifact in artifacts:
        rid = runtime_id(artifact.name)
        sha256 = digest(artifact, "sha256")
        sha512 = digest(artifact, "sha512")
        sha256_lines.append(f"{sha256}  {artifact.name}")
        sha512_lines.append(f"{sha512}  {artifact.name}")
        if (
            rid is None
            or not artifact.name.endswith(".zip")
            or not any(artifact.name.endswith(suffix) for suffix in PACKAGE_SUFFIXES)
        ):
            continue
        encoded = quote(artifact.name)
        packages.append({
            "runtimeIdentifier": rid,
            "url": f"{args.base_url.rstrip('/')}/{encoded}",
            "sha256": sha256,
            "sizeBytes": artifact.stat().st_size,
            "fileName": artifact.name,
            "sha512": sha512,
            "sbomUrl": f"{args.base_url.rstrip('/')}/{quote(artifact.name + '.spdx.json')}",
            "provenanceUrl": "https://github.com/Mikeyphw/xdm/attestations",
            "commitSha": args.commit_sha or None,
            "installModel": "portable-self-update",
            "officialWindowsRelease": args.channel == "stable" and rid.startswith("win-"),
        })

    if not packages:
        raise SystemExit("No portable ZIP packages with recognized runtime identifiers were found.")

    manifest = {
        "schemaVersion": 2,
        "version": args.version,
        "releaseNotesUrl": args.release_notes_url,
        "packages": packages,
        "channel": args.channel,
        "publishedAtUtc": args.published_at,
        "minimumSupportedVersion": args.minimum_supported_version or None,
        "releaseCommitSha": args.commit_sha or None,
        "releaseTag": args.release_tag or None,
    }
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / "SHA256SUMS").write_text("\n".join(sha256_lines) + "\n", encoding="utf-8")
    (args.output / "SHA512SUMS").write_text("\n".join(sha512_lines) + "\n", encoding="utf-8")
    manifest_name = f"xdm-update-{args.channel}.json"
    (args.output / manifest_name).write_text(
        json.dumps(manifest, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    release_metadata = {
        "channel": args.channel,
        "version": args.version,
        "publishedAtUtc": args.published_at,
        "artifactCount": len(artifacts),
        "portablePackageCount": len(packages),
        "updateManifest": manifest_name,
        "releaseCommitSha": args.commit_sha or None,
        "releaseTag": args.release_tag or None,
    }
    (args.output / "release-metadata.json").write_text(
        json.dumps(release_metadata, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
