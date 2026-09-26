#!/usr/bin/env python3
"""Plan/build libxdmcore.so for Android.

The default mode is a deterministic dry run used by XGO validation. Passing
--execute runs the emitted go build commands and requires an Android NDK clang
matching each configured ABI.
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
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


def plan(go_version: str, source_revision: str) -> dict:
    artifacts = []
    for abi in ABIS:
        output = f"app/XDM.Android/app/src/main/jniLibs/{abi['name']}/libxdmcore.so"
        header = "app/XDM.Android/app/src/main/cpp/xdm_core.h"
        command = [
            "go",
            "build",
            "-trimpath",
            "-buildmode=c-shared",
            "-o",
            output,
            "./engine/bridge/cabi",
        ]
        artifacts.append({**abi, "output": output, "header": header, "command": command})
    return {
        "schema_version": 1,
        "library_name": "libxdmcore.so",
        "abi_version": "xgo.android.abi.v1",
        "wire_api_version": "xgo.api.v1",
        "go_version": go_version,
        "source_revision": source_revision,
        "required_symbols": SYMBOLS,
        "artifacts": artifacts,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--execute", action="store_true")
    parser.add_argument("--go-version", default="go1.23.0")
    parser.add_argument("--source-revision", default="local")
    parser.add_argument("--output", default=".devtool/reports/xgo/android/android-engine-build-plan.json")
    args = parser.parse_args()
    data = plan(args.go_version, args.source_revision)
    output = ROOT / args.output
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(data, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    if args.execute:
        for artifact in data["artifacts"]:
            env = os.environ.copy()
            env.update({"GOOS": artifact["goos"], "GOARCH": artifact["goarch"], "CGO_ENABLED": "1", "CC": artifact["cc"]})
            (ROOT / artifact["output"]).parent.mkdir(parents=True, exist_ok=True)
            subprocess.run(artifact["command"], cwd=ROOT, env=env, check=True)
    print(json.dumps(data, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
