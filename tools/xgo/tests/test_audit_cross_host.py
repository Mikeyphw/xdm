from __future__ import annotations

import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
SCRIPT = ROOT / "tools/xgo/audit_cross_host.py"

class CrossHostAuditTests(unittest.TestCase):
    def test_repository_contract_passes(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            output = Path(td) / "report.json"
            completed = subprocess.run(
                [sys.executable, str(SCRIPT), "--output", str(output)],
                cwd=ROOT,
                text=True,
                capture_output=True,
                check=False,
            )
            self.assertEqual(0, completed.returncode, completed.stdout + completed.stderr)
            report = json.loads(output.read_text())
            self.assertEqual("pass", report["status"])
            self.assertEqual(report["canonical"], report["android"])
            self.assertEqual(report["canonical"], report["desktop"])
            self.assertEqual("0.1.0-dev", report["canonical"]["engine_version"])
            self.assertEqual(1, report["canonical"]["protocol_major"])
            self.assertEqual(0, report["canonical"]["schema_version"])

if __name__ == "__main__":
    unittest.main()
