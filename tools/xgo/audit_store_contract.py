#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import subprocess
from pathlib import Path


def run(cmd: list[str], root: Path) -> str:
    p = subprocess.run(cmd, cwd=root, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    if p.returncode:
        raise SystemExit(f"command failed ({p.returncode}): {' '.join(cmd)}\n{p.stdout}")
    return p.stdout


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--output", type=Path, required=True)
    args = ap.parse_args()
    root = Path.cwd()
    report_dir = root / ".devtool/reports/xgo/store"
    report_dir.mkdir(parents=True, exist_ok=True)
    ledger_report = report_dir / "shared-ledger-audit.json"
    fixture_report = report_dir / "shared-fixture-audit.json"

    run([
        "python3", "tools/xgo/audit_foundation.py", "ledger",
        "--map", "engine/docs/donor-map.yaml",
        "--ledger", "engine/docs/capability-ledger.yaml",
        "--output", str(ledger_report),
    ], root)
    run([
        "python3", "tools/xgo/audit_fixtures.py", "lint",
        "--ledger", "engine/docs/capability-ledger.yaml",
        "--map", "engine/docs/donor-map.yaml",
        "--registry", "engine/testdata/fixture-registry.json",
        "--output", str(fixture_report),
    ], root)

    ledger = json.loads((root / "engine/docs/capability-ledger.yaml").read_text())
    registry = json.loads((root / "engine/testdata/fixture-registry.json").read_text())
    caps = {c["id"]: c for c in ledger["capabilities"]}
    fixtures = {f["fixture_id"]: f for f in registry["fixtures"]}
    expected = {
        "XGO-CAP-STORE-001": "XGO-12",
        "XGO-CAP-STORE-002": "XGO-13",
        "XGO-CAP-STORE-003": "XGO-11",
        "XGO-CAP-OWNERSHIP-001": "XGO-14",
        "XGO-CAP-OWNERSHIP-002": "XGO-15",
        "XGO-CAP-CHECKPOINT-001": "XGO-16",
        "XGO-CAP-VERIFY-001": "XGO-17",
        "XGO-CAP-PUBLICATION-001": "XGO-18",
        "XGO-CAP-RECOVERY-001": "XGO-19",
        "XGO-CAP-RECOVERY-002": "XGO-19",
    }
    for cap_id, overlay in expected.items():
        cap = caps.get(cap_id)
        if not cap:
            raise SystemExit(f"missing capability {cap_id}")
        if cap.get("status") != "IMPLEMENTED":
            raise SystemExit(f"{cap_id} status={cap.get('status')!r}, want IMPLEMENTED")
        if cap.get("target_overlay") != overlay:
            raise SystemExit(f"{cap_id} target_overlay={cap.get('target_overlay')!r}, want {overlay}")
        fixture_id = str(cap.get("test_id", "")).removeprefix("fixture:")
        fixture = fixtures.get(fixture_id)
        if not fixture or not fixture.get("detailed"):
            raise SystemExit(f"{cap_id} requires detailed fixture {fixture_id}")

    report = {
        "schema_version": 1,
        "status": "pass",
        "store_capabilities": sorted(expected),
        "capability_count": len(ledger["capabilities"]),
        "fixture_count": registry["fixture_count"],
        "shared_ledger_audit": json.loads(ledger_report.read_text()),
        "shared_fixture_audit": json.loads(fixture_report.read_text()),
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n")
    print(json.dumps(report, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
