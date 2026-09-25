from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

import sys
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from audit_fixtures import FixtureAuditError, lint, secret_scan


class FixtureAuditTests(unittest.TestCase):
    def _repo(self) -> Path:
        tmp = Path(tempfile.mkdtemp(prefix="xgo-fixtures-"))
        (tmp / "engine/testdata/http").mkdir(parents=True)
        ledger = {
            "schema_version": 1,
            "capabilities": [{
                "id": "XGO-CAP-HTTP-001",
                "area": "http",
                "behavior": "probe",
                "canonical_behavior": "probe safely",
                "donor_strategy": "android_led",
                "donors": ["d1"],
                "target_overlay": "XGO-26",
                "test_id": "fixture:xgo-cap-http-001",
                "status": "PLANNED",
            }],
        }
        donor = {"schema_version": 1, "entries": [{"id": "d1"}]}
        fixture = {
            "schema_version": 1,
            "fixture_id": "xgo-cap-http-001",
            "category": "http",
            "capability_ids": ["XGO-CAP-HTTP-001"],
            "donor_ids": ["d1"],
            "input": {"scenario": "probe"},
            "expected": {"invariants": ["probe safely"]},
        }
        registry = {
            "schema_version": 1,
            "fixture_count": 1,
            "fixtures": [{
                "fixture_id": "xgo-cap-http-001",
                "category": "http",
                "capability_ids": ["XGO-CAP-HTTP-001"],
                "donor_ids": ["d1"],
                "path": "engine/testdata/http/xgo-cap-http-001.json",
                "detailed": True,
            }],
        }
        (tmp/"ledger.json").write_text(json.dumps(ledger))
        (tmp/"donor.json").write_text(json.dumps(donor))
        (tmp/"registry.json").write_text(json.dumps(registry))
        (tmp/"engine/testdata/http/xgo-cap-http-001.json").write_text(json.dumps(fixture))
        return tmp

    def test_secret_scan_allows_placeholders(self) -> None:
        root = self._repo()
        document = json.loads((root/"engine/testdata/http/xgo-cap-http-001.json").read_text())
        document["input"]["Authorization"] = "<secret-ref>"
        (root/"engine/testdata/http/xgo-cap-http-001.json").write_text(json.dumps(document))
        old = Path.cwd()
        try:
            import os
            os.chdir(root)
            report = secret_scan(root/"registry.json", None)
        finally:
            os.chdir(old)
        self.assertEqual(report["finding_count"], 0)

    def test_secret_scan_rejects_literal_bearer(self) -> None:
        root = self._repo()
        document = json.loads((root/"engine/testdata/http/xgo-cap-http-001.json").read_text())
        document["input"]["Authorization"] = "Bearer abcdefghijklmnop"
        (root/"engine/testdata/http/xgo-cap-http-001.json").write_text(json.dumps(document))
        old = Path.cwd()
        try:
            import os
            os.chdir(root)
            with self.assertRaises(FixtureAuditError):
                secret_scan(root/"registry.json", None)
        finally:
            os.chdir(old)

    def test_lint_rejects_missing_ledger_fixture(self) -> None:
        root = self._repo()
        registry = json.loads((root/"registry.json").read_text())
        registry["fixtures"] = []
        registry["fixture_count"] = 0
        (root/"registry.json").write_text(json.dumps(registry))
        old = Path.cwd()
        try:
            import os
            os.chdir(root)
            with self.assertRaises(FixtureAuditError):
                lint(root/"ledger.json", root/"donor.json", root/"registry.json", None)
        finally:
            os.chdir(old)


if __name__ == "__main__":
    unittest.main()
