from __future__ import annotations

import subprocess
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "check_go_format.py"

class GoFormatAuditTests(unittest.TestCase):
    def test_accepts_formatted_source_and_rejects_unformatted_source(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            source = root / "x.go"
            source.write_text("package x\n\nfunc F() {}\n", encoding="utf-8")
            ok = subprocess.run(["python3", str(SCRIPT), str(root)], text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            self.assertEqual(ok.returncode, 0, ok.stderr)
            source.write_text("package x\nfunc F(){ }\n", encoding="utf-8")
            bad = subprocess.run(["python3", str(SCRIPT), str(root)], text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            self.assertNotEqual(bad.returncode, 0)

if __name__ == "__main__":
    unittest.main()
