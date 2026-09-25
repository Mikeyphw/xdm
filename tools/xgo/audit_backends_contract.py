#!/usr/bin/env python3
from __future__ import annotations
import argparse, json, subprocess, tomllib
from pathlib import Path

def run(cmd:list[str], root:Path)->None:
    p=subprocess.run(cmd,cwd=root,text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
    if p.returncode:
        raise SystemExit(f"command failed ({p.returncode}): {' '.join(cmd)}\n{p.stdout}")

def detailed_case_names(root:Path, fixtures:dict[str,dict], cap:dict)->set[str]:
    fixture_id=str(cap.get('test_id','')).removeprefix('fixture:')
    meta=fixtures.get(fixture_id)
    if not meta or not meta.get('detailed'):
        raise SystemExit(f'{fixture_id} must be a detailed fixture')
    doc=json.loads((root/meta['path']).read_text())
    return fixture_id, {c.get('name') for c in doc.get('input',{}).get('cases',[])}

def main()->int:
    ap=argparse.ArgumentParser(); ap.add_argument('--output',type=Path,required=True); args=ap.parse_args(); root=Path.cwd()
    report_dir=root/'.devtool/reports/xgo/backends'; report_dir.mkdir(parents=True,exist_ok=True)
    ledger_report=report_dir/'shared-ledger-audit.json'; fixture_report=report_dir/'shared-fixture-audit.json'
    run(['python3','tools/xgo/audit_foundation.py','ledger','--map','engine/docs/donor-map.yaml','--ledger','engine/docs/capability-ledger.yaml','--output',str(ledger_report)],root)
    run(['python3','tools/xgo/audit_fixtures.py','lint','--ledger','engine/docs/capability-ledger.yaml','--map','engine/docs/donor-map.yaml','--registry','engine/testdata/fixture-registry.json','--output',str(fixture_report)],root)
    ledger=json.loads((root/'engine/docs/capability-ledger.yaml').read_text())
    registry=json.loads((root/'engine/testdata/fixture-registry.json').read_text())
    caps={c['id']:c for c in ledger['capabilities']}; fixtures={f['fixture_id']:f for f in registry['fixtures']}

    request_cap=caps.get('XGO-CAP-REQUEST-002')
    if not request_cap or request_cap.get('status')!='IMPLEMENTED' or request_cap.get('target_overlay')!='XGO-36':
        raise SystemExit('XGO-CAP-REQUEST-002 is not closed by XGO-36')
    request_fixture, request_names=detailed_case_names(root,fixtures,request_cap)
    expected_request={'immutable_bytes_post','immutable_file_post','secret_reference_post','one_shot_post','redirect_303','redirect_307','credentialed_post','partial_post_retry'}
    if request_names != expected_request:
        raise SystemExit(f'XGO-36 fixture cases mismatch: {sorted(request_names)}')

    ftp_cap=caps.get('XGO-CAP-FTP-001')
    if not ftp_cap or ftp_cap.get('status')!='IMPLEMENTED' or ftp_cap.get('target_overlay')!='XGO-37':
        raise SystemExit('XGO-CAP-FTP-001 is not closed by XGO-37')
    ftp_fixture, ftp_names=detailed_case_names(root,fixtures,ftp_cap)
    expected_ftp={'anonymous_ftp','authenticated_ftp','ftps','resume','wrong_size','disconnect','retry','checksum_after_ftp'}
    if ftp_names != expected_ftp:
        raise SystemExit(f'XGO-37 fixture cases mismatch: {sorted(ftp_names)}')

    metalink_cap=caps.get('XGO-CAP-METALINK-001')
    if not metalink_cap or metalink_cap.get('status')!='IMPLEMENTED' or metalink_cap.get('target_overlay')!='XGO-38':
        raise SystemExit('XGO-CAP-METALINK-001 is not closed by XGO-38')
    metalink_fixture, metalink_names=detailed_case_names(root,fixtures,metalink_cap)
    expected_metalink={'valid_metalink','malformed_xml','unsupported_hash','duplicate_urls','conflicting_size_hash','generated_intent_fixture'}
    if metalink_names != expected_metalink:
        raise SystemExit(f'XGO-38 fixture cases mismatch: {sorted(metalink_names)}')

    cfg=tomllib.loads((root/'.devtool.toml').read_text())
    target=cfg.get('targets',{}).get('xgo_backends',{})
    if target.get('runner')!='go':
        raise SystemExit('xgo_backends must remain on the native Go runner')
    nodes=target.get('workflows',{}).get('validate',[])
    ids=[n.get('id') for n in nodes]
    if ids != ['go','backend_contract_audit','post_replay_lab','ftp_lab','metalink_corpus']:
        raise SystemExit(f'xgo_backends validate DAG mismatch: {ids}')
    ftp_job=target.get('jobs',{}).get('ftp_lab',{})
    if ftp_job.get('runner')!='command' or '--mode' not in ftp_job.get('command',[]) or 'ftp' not in ftp_job.get('command',[]):
        raise SystemExit('ftp_lab job is not wired to xgo-backends-audit --mode ftp')
    metalink_job=target.get('jobs',{}).get('metalink_corpus',{})
    if metalink_job.get('runner')!='command' or '--mode' not in metalink_job.get('command',[]) or 'metalink' not in metalink_job.get('command',[]):
        raise SystemExit('metalink_corpus job is not wired to xgo-backends-audit --mode metalink')

    report={
      'schema_version':1,'status':'pass','closed_capabilities':['XGO-CAP-REQUEST-002','XGO-CAP-FTP-001','XGO-CAP-METALINK-001'],
      'fixtures':{request_fixture:sorted(request_names),ftp_fixture:sorted(ftp_names),metalink_fixture:sorted(metalink_names)},'workflow_nodes':ids,
      'shared_ledger_audit':json.loads(ledger_report.read_text()),
      'shared_fixture_audit':json.loads(fixture_report.read_text())
    }
    args.output.parent.mkdir(parents=True,exist_ok=True); args.output.write_text(json.dumps(report,indent=2,sort_keys=True)+'\n'); print(json.dumps(report,sort_keys=True)); return 0
if __name__=='__main__': raise SystemExit(main())
