#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
from pathlib import Path

def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("root", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    root = args.root.resolve()
    gofmt = shutil.which("gofmt")
    if not gofmt:
        raise SystemExit("gofmt not found in PATH")
    files = sorted(str(p) for p in root.rglob("*.go") if ".devtool" not in p.parts)
    proc = subprocess.run([gofmt, "-l", *files], text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if proc.returncode != 0:
        raise SystemExit(proc.stderr.strip() or f"gofmt failed with {proc.returncode}")
    dirty = [line.strip() for line in proc.stdout.splitlines() if line.strip()]
    report = {"schema_version": 1, "status": "pass" if not dirty else "fail", "checked_file_count": len(files), "unformatted": dirty}
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        tmp = args.output.with_suffix(args.output.suffix + ".tmp")
        tmp.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        os.replace(tmp, args.output)
    if dirty:
        print("XGO Go format audit FAILED:")
        for path in dirty:
            print(path)
        return 1
    print(json.dumps(report, sort_keys=True))
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
