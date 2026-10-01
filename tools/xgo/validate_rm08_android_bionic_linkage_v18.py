#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BUILD = ROOT / "app/XDM.Android/tools/build-aria2-runtime.py"
VERIFY = ROOT / "app/XDM.Android/tools/verify-aria2-runtime.py"
CANON = ROOT / "app/XDM.Android/tools/validate-rm08-aria2-packaged-execution.py"

def require(cond: bool, msg: str) -> None:
    if not cond:
        raise SystemExit(msg)

def main() -> None:
    build = BUILD.read_text(encoding="utf-8")
    verify = VERIFY.read_text(encoding="utf-8")
    canon = CANON.read_text(encoding="utf-8")
    require('-static-libstdc++' in build, 'RM08 v18 lost Android static-libc++ driver linkage')
    require('-nostdlib++ -lc++_static -lc++abi' not in build, 'RM08 v18 reintroduced manual C++ runtime link bypass')
    require('prove_android_bionic_link_model' in build, 'RM08 v18 lost pre-build Bionic linkage probe')
    require('require_dynamic_bionic_linkage(metadata, label="source-built aria2 runtime")' in build, 'RM08 v18 final ELF lacks Bionic linkage qualification')
    require('"libc.so" not in needed' in build, 'RM08 v18 build does not require Android libc.so DT_NEEDED')
    require('"libc++_shared.so" in needed' in build, 'RM08 v18 build does not reject shared libc++')
    require('require_dynamic_bionic_linkage' in verify and '"libc.so" not in needed' in verify, 'RM08 v18 verifier does not fail closed on zero-Bionic payloads')
    require('"schemaVersion": 4' in build, 'RM08 v18 did not bump native recipe marker schema')
    require('static-libc++' in canon and 'dynamic Bionic' in canon, 'canonical RM08 static validator is stale for v18')
    print('XGO RM08 Android dynamic-Bionic/static-libc++ linkage v18: PASS')

if __name__ == '__main__':
    main()
