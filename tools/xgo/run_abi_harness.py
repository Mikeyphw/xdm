#!/usr/bin/env python3
from __future__ import annotations
import argparse, json, os, shutil, subprocess
from pathlib import Path


def run(cmd: list[str], cwd: Path) -> subprocess.CompletedProcess[str]:
    p = subprocess.run(cmd, cwd=cwd, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    if p.returncode:
        raise SystemExit(f"command failed ({p.returncode}): {' '.join(cmd)}\n{p.stdout}")
    return p


def main() -> int:
    ap=argparse.ArgumentParser(); ap.add_argument('--output', type=Path, required=True); args=ap.parse_args()
    root=Path.cwd(); outdir=root/'.devtool/build/xgo/abi'; outdir.mkdir(parents=True, exist_ok=True)
    lib=outdir/'libxdmcore.so'
    build=run(['go','build','-buildmode=c-shared','-o',str(lib),'./engine/bridge/cabi'],root)
    header=lib.with_suffix('.h')
    if not header.is_file(): raise SystemExit(f'missing generated header: {header}')
    cc=os.environ.get('CC') or shutil.which('clang') or shutil.which('cc') or shutil.which('gcc')
    if not cc: raise SystemExit('no C compiler found for ABI harness')
    harness=outdir/'abi_harness'
    run([cc,'-std=c11','-Wall','-Wextra','-Werror','-I',str(outdir),str(root/'engine/bridge/cabi/harness/abi_harness.c'),str(lib),'-Wl,-rpath,'+str(outdir),'-o',str(harness)],root)
    executed=run([str(harness)],root)
    nm=shutil.which('llvm-nm') or shutil.which('nm')
    symbols=[]
    if nm:
        sym=run([nm,'-D','--defined-only',str(lib)],root).stdout
        required=['xdm_engine_create','xdm_engine_command','xdm_engine_next_frame','xdm_engine_platform_reply','xdm_engine_metadata','xdm_engine_shutdown','xdm_buffer_free']
        for name in required:
            if name not in sym: raise SystemExit(f'missing ABI symbol: {name}')
        symbols=required
    report={'schema_version':1,'status':'pass','library':str(lib.relative_to(root)),'header':str(header.relative_to(root)),'harness':str(harness.relative_to(root)),'symbols':symbols,'harness_output':executed.stdout.strip(),'build_output':build.stdout.strip()}
    args.output.parent.mkdir(parents=True,exist_ok=True); args.output.write_text(json.dumps(report,indent=2,sort_keys=True)+'\n')
    print(json.dumps(report,sort_keys=True))
    return 0
if __name__=='__main__': raise SystemExit(main())
