#!/usr/bin/env python3
"""Audit the XDM Desktop REM18 final remediation closure ledger.

This is intentionally dependency-free so Devtool, GitHub Actions, and local
release shells can all execute the same closure contract.
"""
from __future__ import annotations

import argparse
import json
import sys
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

EXPECTED_TOTAL = 258
EXPECTED_SEVERITIES = {"HIGH": 67, "MEDIUM": 153, "LOW": 38}
EXPECTED_REM18_IDS = {"S13-12", "S14-10", "S16-07"}
REQUIRED_STATUSES = {"Closed"}
REQUIRED_REGRESSION = {"Regression-covered", "Regression-covered-final-seal", "Non-applicable-with-evidence"}


def find_repo_root(start: Path) -> Path:
    current = start.resolve()
    for candidate in (current, *current.parents):
        if (candidate / ".devtool.toml").exists() and (candidate / "app/XDM/XDM.Modern.sln").exists():
            return candidate
    raise SystemExit("Unable to locate XDM repository root.")


def load_ledger(path: Path) -> list[dict[str, object]]:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError as exc:
        raise SystemExit(f"REM18 closure ledger is missing: {path}") from exc
    if not isinstance(data, dict) or not isinstance(data.get("findings"), list):
        raise SystemExit("REM18 closure ledger must be an object with a findings array.")
    findings = data["findings"]
    if not all(isinstance(item, dict) for item in findings):
        raise SystemExit("REM18 closure ledger findings must be JSON objects.")
    return findings


def audit(findings: list[dict[str, object]]) -> list[str]:
    issues: list[str] = []
    ids = [str(item.get("id", "")).strip() for item in findings]
    if len(findings) != EXPECTED_TOTAL:
        issues.append(f"expected {EXPECTED_TOTAL} findings, found {len(findings)}")
    duplicates = [finding_id for finding_id, count in Counter(ids).items() if count > 1]
    if duplicates:
        issues.append("duplicate finding ids: " + ", ".join(sorted(duplicates)))
    if any(not finding_id for finding_id in ids):
        issues.append("at least one finding id is empty")

    severities = Counter(str(item.get("severity", "")).upper() for item in findings)
    for severity, expected_count in EXPECTED_SEVERITIES.items():
        actual = severities.get(severity, 0)
        if actual != expected_count:
            issues.append(f"severity {severity}: expected {expected_count}, found {actual}")

    unknown_severities = sorted(set(severities) - set(EXPECTED_SEVERITIES))
    if unknown_severities:
        issues.append("unknown severity values: " + ", ".join(unknown_severities))

    not_closed = [str(item.get("id")) for item in findings if str(item.get("status", "")).strip() not in REQUIRED_STATUSES]
    if not_closed:
        issues.append("findings not Closed: " + ", ".join(not_closed[:20]) + (" ..." if len(not_closed) > 20 else ""))

    missing_regression = [
        str(item.get("id"))
        for item in findings
        if str(item.get("regressionStatus", "")).strip() not in REQUIRED_REGRESSION
    ]
    if missing_regression:
        issues.append("findings without regression coverage: " + ", ".join(missing_regression[:20]) + (" ..." if len(missing_regression) > 20 else ""))

    rem18_ids = {str(item.get("id")) for item in findings if str(item.get("overlay")) == "REM18"}
    if rem18_ids != EXPECTED_REM18_IDS:
        issues.append(f"REM18 finding ids mismatch: expected {sorted(EXPECTED_REM18_IDS)}, found {sorted(rem18_ids)}")

    missing_evidence = [str(item.get("id")) for item in findings if not item.get("evidence")]
    if missing_evidence:
        issues.append("findings without evidence: " + ", ".join(missing_evidence[:20]) + (" ..." if len(missing_evidence) > 20 else ""))

    return issues


def main() -> int:
    parser = argparse.ArgumentParser(description="Audit XDM Desktop REM18 final closure ledger.")
    parser.add_argument("--ledger", default="docs/remediation/XDM_DESKTOP_FINAL_REMEDIATION_LEDGER.json")
    parser.add_argument("--write-evidence", default="")
    args = parser.parse_args()

    repo = find_repo_root(Path.cwd())
    ledger_path = (repo / args.ledger).resolve()
    findings = load_ledger(ledger_path)
    issues = audit(findings)

    evidence = {
        "schemaVersion": 1,
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "ledger": str(ledger_path.relative_to(repo)),
        "expectedTotal": EXPECTED_TOTAL,
        "actualTotal": len(findings),
        "expectedSeverities": EXPECTED_SEVERITIES,
        "actualSeverities": dict(Counter(str(item.get("severity", "")).upper() for item in findings)),
        "rem18Ids": sorted(str(item.get("id")) for item in findings if str(item.get("overlay")) == "REM18"),
        "issues": issues,
        "passed": not issues,
    }
    if args.write_evidence:
        output = (repo / args.write_evidence).resolve()
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(evidence, indent=2, sort_keys=True) + "\n", encoding="utf-8")

    if issues:
        for issue in issues:
            print(f"REM18 ledger audit failed: {issue}", file=sys.stderr)
        return 1

    print(
        "REM18 ledger audit passed: "
        f"{len(findings)}/{EXPECTED_TOTAL} Closed, severities "
        + ", ".join(f"{key}={value}" for key, value in EXPECTED_SEVERITIES.items())
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
