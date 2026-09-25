#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import os
import subprocess
from pathlib import Path


def run(cmd: list[str], root: Path, env: dict[str, str] | None = None) -> str:
    merged = os.environ.copy()
    if env:
        merged.update(env)
    p = subprocess.run(cmd, cwd=root, env=merged, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    if p.returncode:
        raise SystemExit(f"command failed ({p.returncode}): {' '.join(cmd)}\n{p.stdout}")
    return p.stdout


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--output", type=Path, required=True)
    args = ap.parse_args()
    root = Path.cwd()
    env_lines = run(["go", "env", "GOOS", "GOARCH", "CGO_ENABLED"], root).strip().splitlines()
    if len(env_lines) != 3:
        raise SystemExit(f"unexpected go env output: {env_lines!r}")
    goos, goarch, cgo = env_lines
    if cgo != "1":
        raise SystemExit("xgo_store native SQLite validation requires CGO_ENABLED=1")

    native = run(["go", "test", "-count=3", "./engine/store/sqlite"], root)
    nocgo = run(["go", "test", "-count=1", "./engine/store/sqlite"], root, {"CGO_ENABLED": "0"})
    audit_path = root / ".devtool/reports/xgo/store/sqlite-platform-audit.json"
    audit = run([
        "go", "run", "./engine/cmd/xgo-store-audit", "--mode", "platform", "--output", str(audit_path)
    ], root)

    # Compile the no-cgo contract for future host OS/architectures. This does not
    # claim production SQLite linkage on those targets; it proves the store API
    # has an explicit non-cgo failure mode rather than accidental build breakage.
    cross = []
    for target_os, target_arch in [("android", "arm64"), ("linux", "arm64"), ("windows", "amd64"), ("darwin", "arm64")]:
        suffix = ".exe" if target_os == "windows" else ""
        out = root / ".devtool" / "tmp" / f"xgo-sqlite-{target_os}-{target_arch}.test{suffix}"
        out.parent.mkdir(parents=True, exist_ok=True)
        output = run([
            "go", "test", "-c", "-o", str(out), "./engine/store/sqlite"
        ], root, {"CGO_ENABLED": "0", "GOOS": target_os, "GOARCH": target_arch})
        cross.append({"goos": target_os, "goarch": target_arch, "status": "compiled", "output": output.strip()})
        out.unlink(missing_ok=True)

    report = {
        "schema_version": 1,
        "status": "pass",
        "native": {"goos": goos, "goarch": goarch, "cgo_enabled": cgo, "test_output": native.strip()},
        "no_cgo_test_output": nocgo.strip(),
        "cross_compile_no_cgo_contracts": cross,
        "runtime_audit": json.loads(audit),
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n")
    print(json.dumps(report, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
