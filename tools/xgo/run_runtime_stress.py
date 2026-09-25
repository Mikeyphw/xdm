#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import subprocess
from pathlib import Path


def run(cmd: list[str], root: Path) -> str:
    p = subprocess.run(
        cmd,
        cwd=root,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
    )
    if p.returncode:
        raise SystemExit(
            f"command failed ({p.returncode}): {' '.join(cmd)}\n{p.stdout}"
        )
    return p.stdout


def go_env(root: Path) -> tuple[str, str]:
    out = run(["go", "env", "GOOS", "GOARCH"], root).strip().splitlines()
    if len(out) != 2:
        raise SystemExit(f"unexpected go env output: {out!r}")
    return out[0].strip(), out[1].strip()


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--output", type=Path, required=True)
    ap.add_argument("--iterations", type=int, default=20)
    ap.add_argument(
        "--race",
        action="store_true",
        help="run the Go race detector; only use on a supported host",
    )
    args = ap.parse_args()
    if args.iterations < 1:
        raise SystemExit("--iterations must be >= 1")

    root = Path.cwd()
    goos, goarch = go_env(root)

    stress_cmd = [
        "go",
        "test",
        f"-count={args.iterations}",
        "./engine/runtime/...",
        "./engine/api/v1/...",
    ]
    stress_output = run(stress_cmd, root)

    race = {
        "requested": bool(args.race),
        "status": "not-requested",
        "command": None,
        "output": None,
    }
    if args.race:
        if goos == "android":
            raise SystemExit(
                f"race detector requested on unsupported target {goos}/{goarch}; "
                "run --race on a supported Linux host instead"
            )
        race_cmd = [
            "go",
            "test",
            "-race",
            "-count=1",
            "./engine/runtime/...",
            "./engine/api/v1/...",
        ]
        race_output = run(race_cmd, root)
        race = {
            "requested": True,
            "status": "passed",
            "command": " ".join(race_cmd),
            "output": race_output.strip(),
        }

    audit_path = root / ".devtool/reports/xgo/foundation/runtime-stress-audit.json"
    audit_output = run(
        [
            "go",
            "run",
            "./engine/cmd/xgo-runtime-audit",
            "--mode",
            "runtime",
            "--output",
            str(audit_path),
        ],
        root,
    )

    report = {
        "schema_version": 2,
        "status": "pass",
        "platform": {"goos": goos, "goarch": goarch},
        "stress": {
            "iterations": args.iterations,
            "command": " ".join(stress_cmd),
            "output": stress_output.strip(),
        },
        "race": race,
        "audit_output": audit_output.strip(),
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n")
    print(json.dumps(report, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
