#!/usr/bin/env python3
"""Plan/build libxdmcore.so for Android with release provenance.

The default mode is a deterministic dry run used by XGO validation. Passing
--execute runs the emitted Go build command, writes the canonical provenance
asset, and requires an Android NDK clang matching the configured ABI.
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PROVENANCE_REL = "app/XDM.Android/app/src/main/assets/xdmcore/xdmcore-provenance.json"
ABIS = [
    {
        "name": "arm64-v8a",
        "goos": "android",
        "goarch": "arm64",
        "min_api": 26,
        "cc": "aarch64-linux-android26-clang",
    }
]
SYMBOLS = [
    "xdm_buffer_free",
    "xdm_engine_command",
    "xdm_engine_create",
    "xdm_engine_metadata",
    "xdm_engine_next_frame",
    "xdm_engine_platform_reply",
    "xdm_engine_shutdown",
]


def git_revision() -> str:
    return subprocess.check_output(["git", "-C", str(ROOT), "rev-parse", "HEAD"], text=True).strip()


def git_modified() -> bool:
    output = subprocess.check_output(
        ["git", "-C", str(ROOT), "status", "--porcelain", "--untracked-files=no"], text=True
    )
    return bool(output.strip())


def current_go_version() -> str:
    return subprocess.check_output(["go", "env", "GOVERSION"], text=True).strip()


def plan(go_version: str, source_revision: str, source_modified: bool = False) -> dict:
    artifacts = []
    modified = "true" if source_modified else "false"
    for abi in ABIS:
        output = f"app/XDM.Android/app/src/main/jniLibs/{abi['name']}/libxdmcore.so"
        header = "app/XDM.Android/app/src/main/cpp/xdm_core.h"
        ldflags = (
            f"-X github.com/subhra74/xdm/engine/api/v1.BuildRevision={source_revision} "
            f"-X github.com/subhra74/xdm/engine/api/v1.BuildModified={modified} "
            "-X github.com/subhra74/xdm/engine/api/v1.BuildMode=c-shared"
        )
        command = [
            "go",
            "build",
            "-trimpath",
            "-buildmode=c-shared",
            "-ldflags",
            ldflags,
            "-o",
            output,
            "./engine/bridge/cabi",
        ]
        artifacts.append(
            {
                **abi,
                "output": output,
                "header": header,
                "provenance_output": PROVENANCE_REL,
                "build_mode": "c-shared",
                "command": command,
            }
        )
    return {
        "schema_version": 2,
        "library_name": "libxdmcore.so",
        "provenance_name": "xdmcore-provenance.json",
        "abi_version": "xgo.android.abi.v1",
        "wire_api_version": "xgo.api.v1",
        "engine_version": "0.1.0-dev",
        "protocol": {"major": 1, "minor": 0},
        "engine_schema_version": 1,
        "build_mode": "c-shared",
        "go_version": go_version,
        "source_revision": source_revision,
        "source_modified": source_modified,
        "required_symbols": SYMBOLS,
        "artifacts": artifacts,
    }


def write_provenance(data: dict) -> None:
    artifact = data["artifacts"][0]
    command = [
        "go",
        "run",
        "./engine/cmd/xgo-provenance",
        "--revision",
        data["source_revision"],
        f"--modified={'true' if data['source_modified'] else 'false'}",
        "--go-version",
        data["go_version"],
        "--goos",
        artifact["goos"],
        "--goarch",
        artifact["goarch"],
        "--build-mode",
        data["build_mode"],
        "--output",
        artifact["provenance_output"],
    ]
    subprocess.run(command, cwd=ROOT, check=True)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--execute", action="store_true")
    parser.add_argument("--go-version")
    parser.add_argument("--source-revision")
    parser.add_argument("--source-modified", choices=("true", "false"))
    parser.add_argument("--output", default=".devtool/reports/xgo/android/android-engine-build-plan.json")
    args = parser.parse_args()

    resolved_go = args.go_version or current_go_version()
    resolved_revision = args.source_revision or git_revision()
    resolved_modified = git_modified() if args.source_modified is None else args.source_modified == "true"
    data = plan(resolved_go, resolved_revision, resolved_modified)
    output = ROOT / args.output
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(data, indent=2, sort_keys=True) + "\n", encoding="utf-8")

    if args.execute:
        for artifact in data["artifacts"]:
            env = os.environ.copy()
            env.update(
                {
                    "GOOS": artifact["goos"],
                    "GOARCH": artifact["goarch"],
                    "CGO_ENABLED": "1",
                    "CC": artifact["cc"],
                }
            )
            (ROOT / artifact["output"]).parent.mkdir(parents=True, exist_ok=True)
            subprocess.run(artifact["command"], cwd=ROOT, env=env, check=True)
        write_provenance(data)

    print(json.dumps(data, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
