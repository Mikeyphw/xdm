#!/usr/bin/env python3
"""Capture an evidence-only Devtool configuration baseline; no Gradle, network or device."""
from __future__ import annotations
import argparse
import ast
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tomllib

ROOT = Path(__file__).resolve().parents[1]
SOURCE_FILES = [
    '.devtool.toml', 'devtoolw', 'devtoolw.cmd',
    'tools/xgo/audit_topology.py', 'tools/xgo/validate-devtool-wrapper.py',
    'scripts/devtool_actions_contract.py', '.github/workflows/devtool-xgo-engine.yml',
]

def git(*args: str) -> str:
    result = subprocess.run(['git','-C',str(ROOT),*args], stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE, text=True, check=True)
    return result.stdout.strip()

def test_inventory() -> dict:
    summary={'unittest_methods':0,'pytest_style_functions':0,'files':[]}
    for f in sorted((ROOT/'tools/xgo/tests').glob('test_*.py')):
        parsed=ast.parse(f.read_text(encoding='utf-8'))
        standalone=sum(1 for node in parsed.body if isinstance(node,(ast.FunctionDef,ast.AsyncFunctionDef)) and node.name.startswith('test_'))
        unit=0
        for node in parsed.body:
            if isinstance(node,ast.ClassDef) and any('TestCase' in ast.unparse(base) for base in node.bases):
                unit+=sum(isinstance(method,ast.FunctionDef) and method.name.startswith('test_') for method in node.body)
        summary['unittest_methods']+=unit
        summary['pytest_style_functions']+=standalone
        summary['files'].append({'path':f.relative_to(ROOT).as_posix(),'unittest_methods':unit,'pytest_style_functions':standalone})
    return summary

def audit() -> dict:
    config=tomllib.loads((ROOT/'.devtool.toml').read_text(encoding='utf-8'))
    targets=config.get('targets',{})
    rooted={name:target.get('root','') for name,target in targets.items()}
    return {'schema_version':1, 'kind':'xdm.devtool.phase01-baseline',
            'source_commit':git('rev-parse','HEAD'),'source_tree':git('rev-parse','HEAD^{tree}'),
            'branch':git('branch','--show-current'),
            'tracked_source_clean':not bool(git('status','--porcelain','--untracked-files=no')),
            'files':{name:hashlib.sha256((ROOT/name).read_bytes()).hexdigest() if (ROOT/name).exists() else None for name in SOURCE_FILES},
            'targets':len(targets),'wrapper_commands':len(config.get('wrapper',{}).get('commands',{})),
            'target_roots':rooted,'test_inventory':test_inventory(),
            'runtime_qualification':'NOT_RUN','android_build':'NOT_RUN','device_acceptance':'NOT_RUN'}

def main() -> int:
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output',type=Path,help='optional JSON output (by default stdout only)')
    args=parser.parse_args()
    report=audit()
    data=json.dumps(report,sort_keys=True,indent=2)+'\n'
    if args.output:
        dest=args.output if args.output.is_absolute() else ROOT/args.output
        dest.parent.mkdir(parents=True,exist_ok=True)
        dest.write_text(data,encoding='utf-8')
    print(data,end='')
    return 0

if __name__=='__main__':
    raise SystemExit(main())
