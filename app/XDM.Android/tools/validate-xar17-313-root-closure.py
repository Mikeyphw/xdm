#!/usr/bin/env python3
"""Validate the canonical XAR17 root inventory without claiming behavioral closure."""

from pathlib import Path
import csv
import sys

ROOT = Path(__file__).resolve().parents[1]
INVENTORY = ROOT / "docs" / "remediation" / "XAR17-313-CANONICAL-ROOTS.tsv"
REQUIRED = {"overlay", "section", "section_name", "canonical_id", "origin", "severity", "title", "source_report"}


def main() -> int:
    if not INVENTORY.is_file():
        print(f"FAIL: missing {INVENTORY}")
        return 1
    with INVENTORY.open(encoding="utf-8", newline="") as handle:
        rows = list(csv.DictReader(handle, delimiter="\t"))
    if not rows:
        print("FAIL: XAR17 inventory has no data rows")
        return 1
    fields = set(rows[0])
    missing = REQUIRED - fields
    if missing:
        print(f"FAIL: inventory missing columns: {', '.join(sorted(missing))}")
        return 1
    ids = [row["canonical_id"].strip() for row in rows]
    if len(rows) != 313:
        print(f"FAIL: expected 313 canonical roots, found {len(rows)}")
        return 1
    if len(set(ids)) != 313 or any(not value for value in ids):
        print("FAIL: canonical IDs must be non-empty and unique")
        return 1
    invalid = [row["overlay"] for row in rows if row["overlay"] not in {f"XAR{i:02d}" for i in range(1, 17)}]
    if invalid:
        print(f"FAIL: invalid overlay labels: {sorted(set(invalid))}")
        return 1
    print("passed: XAR17 canonical-root inventory contains 313 unique roots")
    print("note: this is bookkeeping coverage; behavioral closure evidence remains separate")
    return 0


if __name__ == "__main__":
    sys.exit(main())
