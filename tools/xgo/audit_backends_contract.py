#!/usr/bin/env python3
from __future__ import annotations
import argparse, json, subprocess, tomllib
from pathlib import Path

def run(cmd:list[str], root:Path)->None:
    p=subprocess.run(cmd,cwd=root,text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
    if p.returncode:
        raise SystemExit(f"command failed ({p.returncode}): {' '.join(cmd)}\n{p.stdout}")

def main()->int:
    ap=argparse.ArgumentParser(); ap.add_argument('--output',type=Path,required=True); args=ap.parse_args(); root=Path.cwd()
    report_dir=root/'.devtool/reports/xgo/backends'; report_dir.mkdir(parents=True,exist_ok=True)
    ledger_report=report_dir/'shared-ledger-audit.json'; fixture_report=report_dir/'shared-fixture-audit.json'
    run(['python3','tools/xgo/audit_foundation.py','ledger','--map','engine/docs/donor-map.yaml','--ledger','engine/docs/capability-ledger.yaml','--output',str(ledger_report)],root)
    run(['python3','tools/xgo/audit_fixtures.py','lint','--ledger','engine/docs/capability-ledger.yaml','--map','engine/docs/donor-map.yaml','--registry','engine/testdata/fixture-registry.json','--output',str(fixture_report)],root)
    ledger=json.loads((root/'engine/docs/capability-ledger.yaml').read_text())
    registry=json.loads((root/'engine/testdata/fixture-registry.json').read_text())
    caps={c['id']:c for c in ledger['capabilities']}; fixtures={f['fixture_id']:f for f in registry['fixtures']}
    cap=caps.get('XGO-CAP-REQUEST-002')
    if not cap or cap.get('status')!='IMPLEMENTED' or cap.get('target_overlay')!='XGO-36':
        raise SystemExit('XGO-CAP-REQUEST-002 is not closed by XGO-36')
    fixture_id=str(cap.get('test_id','')).removeprefix('fixture:'); meta=fixtures.get(fixture_id)
    if not meta or not meta.get('detailed'):
        raise SystemExit(f'{fixture_id} must be a detailed fixture')
    doc=json.loads((root/meta['path']).read_text())
    names={c.get('name') for c in doc.get('input',{}).get('cases',[])}
    expected_cases={'immutable_bytes_post','immutable_file_post','secret_reference_post','one_shot_post','redirect_303','redirect_307','credentialed_post','partial_post_retry'}
    if names != expected_cases:
        raise SystemExit(f'XGO-36 fixture cases mismatch: {sorted(names)}')
    cfg=tomllib.loads((root/'.devtool.toml').read_text())
    target=cfg.get('targets',{}).get('xgo_backends',{})
    if target.get('runner')!='go':
        raise SystemExit('xgo_backends must be activated on the native Go runner')
    nodes=target.get('workflows',{}).get('validate',[])
    ids=[n.get('id') for n in nodes]
    if ids != ['go','backend_contract_audit','post_replay_lab']:
        raise SystemExit(f'xgo_backends validate DAG mismatch: {ids}')
    report={
      'schema_version':1,'status':'pass','closed_capability':'XGO-CAP-REQUEST-002',
      'fixture':fixture_id,'fixture_cases':sorted(names),'workflow_nodes':ids,
      'shared_ledger_audit':json.loads(ledger_report.read_text()),
      'shared_fixture_audit':json.loads(fixture_report.read_text())
    }
    args.output.parent.mkdir(parents=True,exist_ok=True); args.output.write_text(json.dumps(report,indent=2,sort_keys=True)+'\n'); print(json.dumps(report,sort_keys=True)); return 0
if __name__=='__main__': raise SystemExit(main())
