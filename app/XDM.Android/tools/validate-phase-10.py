#!/usr/bin/env python3
from pathlib import Path
import runpy
ROOT = Path(__file__).resolve().parents[1]
runpy.run_path(str(ROOT / "tools/validate-bug-hunt-phase10-release-upgrade-packaging.py"), run_name="__main__")
