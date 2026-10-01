#!/usr/bin/env python3
from __future__ import annotations

import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CANON = ROOT / "app/XDM.Android/tools/validate-rm08-aria2-packaged-execution.py"
TEST = ROOT / "app/XDM.Android/app/src/androidTest/kotlin/com/mikeyphw/xdm/android/Aria2Rm08PackagedRuntimeInstrumentedTest.kt"
FFMPEG = ROOT / "app/XDM.Android/tools/install-ffmpeg-runtime.py"


def require(cond: bool, msg: str) -> None:
    if not cond:
        raise SystemExit(msg)


def main() -> int:
    canon = CANON.read_text(encoding="utf-8")
    test = TEST.read_text(encoding="utf-8")
    ffmpeg = FFMPEG.read_text(encoding="utf-8")

    require('tellStatus(recoveryGid)' not in canon,
            'RM08 v20 canonical validator still pins the obsolete recoveryGid implementation detail')
    for token in (
        'awaitSessionRecord(',
        'assertSessionRecord(store.sessionFile, gid, sourceUrl)',
        'recoveredRpc::tellStatus',
        'label = "recovered owned session"',
        'awaitSessionRecordAbsent(',
    ):
        require(token in canon, f'RM08 v20 canonical validator lost recovery semantic token: {token}')
        require(token in test, f'RM08 v20 instrumentation test lost recovery semantic token: {token}')

    require('"--no-default-config"' in ffmpeg,
            'RM08 v20 lost Clang default-config suppression in the Termux Android wrapper')
    require('host-driver config' in ffmpeg,
            'RM08 v20 lost the documented host-driver config isolation rationale')

    proc = subprocess.run(
        [sys.executable, str(CANON)],
        cwd=ROOT,
        text=True,
        capture_output=True,
    )
    if proc.returncode != 0:
        detail = (proc.stderr or proc.stdout).strip()
        raise SystemExit('RM08 canonical packaged-execution verifier still fails: ' + detail)

    print('XGO RM08 canonical contract reconciliation v20: PASS')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
