from __future__ import annotations

import hashlib
import importlib.util
import json
import subprocess
import tempfile
import unittest
from pathlib import Path

MODULE_PATH = Path(__file__).resolve().parents[1] / "audit_foundation.py"
SPEC = importlib.util.spec_from_file_location("audit_foundation", MODULE_PATH)
assert SPEC and SPEC.loader
AUDIT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(AUDIT)


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


class FoundationAuditTests(unittest.TestCase):
    def _git_repo(self, root: Path) -> None:
        subprocess.run(["git", "init", "-q", str(root)], check=True)
        subprocess.run(["git", "-C", str(root), "config", "user.email", "xgo-test@example.invalid"], check=True)
        subprocess.run(["git", "-C", str(root), "config", "user.name", "XGO Test"], check=True)
        subprocess.run(["git", "-C", str(root), "add", "."], check=True)
        subprocess.run(["git", "-C", str(root), "commit", "-qm", "fixture"], check=True)

    def test_donor_audit_accepts_clean_pinned_git_checkout(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            donor = root / "donor"
            donor.mkdir()
            payload = b"hello\n"
            (donor / "a.txt").write_bytes(payload)
            self._git_repo(donor)
            entry = {"id": "a", "path": "a.txt", "sha256": sha(payload), "size": len(payload), "categories": ["test"]}
            aggregate = AUDIT._aggregate([entry])
            mapping = root / "map.yaml"
            mapping.write_text(json.dumps({"schema_version": 1, "aggregate_sha256": aggregate, "entries": [entry]}))
            report = AUDIT.audit_donor(donor, mapping, root / "out.json")
            self.assertEqual(report["status"], "pass")
            self.assertEqual(report["entry_count"], 1)

    def test_donor_audit_rejects_hash_drift(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            donor = root / "donor"
            donor.mkdir()
            payload = b"hello\n"
            (donor / "a.txt").write_bytes(payload)
            self._git_repo(donor)
            entry = {"id": "a", "path": "a.txt", "sha256": "0" * 64, "size": len(payload), "categories": ["test"]}
            mapping = root / "map.yaml"
            mapping.write_text(json.dumps({"schema_version": 1, "aggregate_sha256": AUDIT._aggregate([entry]), "entries": [entry]}))
            with self.assertRaisesRegex(AUDIT.AuditError, "hash mismatch"):
                AUDIT.audit_donor(donor, mapping, root / "out.json")

    def test_donor_audit_rejects_tracked_dirty_checkout(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            donor = root / "donor"
            donor.mkdir()
            payload = b"hello\n"
            path = donor / "a.txt"
            path.write_bytes(payload)
            self._git_repo(donor)
            path.write_bytes(b"changed\n")
            entry = {"id": "a", "path": "a.txt", "sha256": sha(payload), "size": len(payload), "categories": ["test"]}
            mapping = root / "map.yaml"
            mapping.write_text(json.dumps({"schema_version": 1, "aggregate_sha256": AUDIT._aggregate([entry]), "entries": [entry]}))
            with self.assertRaisesRegex(AUDIT.AuditError, "tracked modifications"):
                AUDIT.audit_donor(donor, mapping, root / "out.json")

    def test_ledger_rejects_duplicate_capability_ids(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            mapping = root / "map.yaml"
            mapping.write_text(json.dumps({"schema_version": 1, "entries": []}))
            cap = {
                "id": "XGO-CAP-DOMAIN-001",
                "area": "domain",
                "behavior": "b",
                "donors": [],
                "canonical_behavior": "c",
                "donor_strategy": "new_design",
                "target_overlay": "XGO-06",
                "test_id": "fixture:x",
                "status": "PLANNED",
            }
            ledger = root / "ledger.yaml"
            ledger.write_text(json.dumps({"schema_version": 1, "minimum_capability_count": 1, "capabilities": [cap, cap]}))
            with self.assertRaisesRegex(AUDIT.AuditError, "duplicate capability id"):
                AUDIT.audit_ledger(mapping, ledger, root / "out.json")

    def test_ledger_rejects_unknown_donor_reference(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            mapping = root / "map.yaml"
            mapping.write_text(json.dumps({"schema_version": 1, "entries": [{"id": "known"}]}))
            ledger = root / "ledger.yaml"
            ledger.write_text(json.dumps({
                "schema_version": 1,
                "minimum_capability_count": 1,
                "capabilities": [{
                    "id": "XGO-CAP-DOMAIN-001",
                    "area": "domain",
                    "behavior": "b",
                    "donors": ["missing"],
                    "canonical_behavior": "c",
                    "donor_strategy": "merged",
                    "target_overlay": "XGO-06",
                    "test_id": "fixture:x",
                    "status": "PLANNED",
                }],
            }))
            with self.assertRaisesRegex(AUDIT.AuditError, "unknown donors"):
                AUDIT.audit_ledger(mapping, ledger, root / "out.json")


if __name__ == "__main__":
    unittest.main()
