#!/usr/bin/env python3
from __future__ import annotations
import argparse, json, subprocess, tomllib
from pathlib import Path


def run(cmd: list[str], root: Path) -> None:
    p = subprocess.run(cmd, cwd=root, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    if p.returncode:
        raise SystemExit(f"command failed ({p.returncode}): {' '.join(cmd)}\n{p.stdout}")


def detailed_case_names(root: Path, fixtures: dict[str, dict], cap: dict) -> tuple[str, set[str]]:
    fixture_id = str(cap.get('test_id', '')).removeprefix('fixture:')
    meta = fixtures.get(fixture_id)
    if not meta or not meta.get('detailed'):
        raise SystemExit(f'{fixture_id} must be a detailed fixture')
    doc = json.loads((root / meta['path']).read_text())
    return fixture_id, {c.get('name') for c in doc.get('input', {}).get('cases', [])}


def require_capability(root: Path, caps: dict[str, dict], fixtures: dict[str, dict], cap_id: str, overlay: str, expected: set[str]) -> tuple[str, set[str]]:
    cap = caps.get(cap_id)
    if not cap or cap.get('status') != 'IMPLEMENTED' or cap.get('target_overlay') != overlay:
        raise SystemExit(f'{cap_id} is not closed by {overlay}')
    fixture_id, names = detailed_case_names(root, fixtures, cap)
    if names != expected:
        raise SystemExit(f'{overlay} fixture cases mismatch: {sorted(names)}')
    return fixture_id, names


def command_has_mode(job: dict, mode: str) -> bool:
    cmd = job.get('command', [])
    try:
        idx = cmd.index('--mode')
    except ValueError:
        return False
    return job.get('runner') == 'command' and idx + 1 < len(cmd) and cmd[idx + 1] == mode


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument('--output', type=Path, required=True)
    args = ap.parse_args()
    root = Path.cwd()
    report_dir = root / '.devtool/reports/xgo/backends'
    report_dir.mkdir(parents=True, exist_ok=True)
    ledger_report = report_dir / 'shared-ledger-audit.json'
    fixture_report = report_dir / 'shared-fixture-audit.json'
    run(['python3', 'tools/xgo/audit_foundation.py', 'ledger', '--map', 'engine/docs/donor-map.yaml', '--ledger', 'engine/docs/capability-ledger.yaml', '--output', str(ledger_report)], root)
    run(['python3', 'tools/xgo/audit_fixtures.py', 'lint', '--ledger', 'engine/docs/capability-ledger.yaml', '--map', 'engine/docs/donor-map.yaml', '--registry', 'engine/testdata/fixture-registry.json', '--output', str(fixture_report)], root)

    ledger = json.loads((root / 'engine/docs/capability-ledger.yaml').read_text())
    registry = json.loads((root / 'engine/testdata/fixture-registry.json').read_text())
    caps = {c['id']: c for c in ledger['capabilities']}
    fixtures = {f['fixture_id']: f for f in registry['fixtures']}

    specs = [
        ('XGO-CAP-REQUEST-002', 'XGO-36', {'immutable_bytes_post', 'immutable_file_post', 'secret_reference_post', 'one_shot_post', 'redirect_303', 'redirect_307', 'credentialed_post', 'partial_post_retry'}),
        ('XGO-CAP-FTP-001', 'XGO-37', {'anonymous_ftp', 'authenticated_ftp', 'ftps', 'resume', 'wrong_size', 'disconnect', 'retry', 'checksum_after_ftp'}),
        ('XGO-CAP-METALINK-001', 'XGO-38', {'valid_metalink', 'malformed_xml', 'unsupported_hash', 'duplicate_urls', 'conflicting_size_hash', 'generated_intent_fixture'}),
        ('XGO-CAP-BACKEND-001', 'XGO-39', {'canonical_request_kinds', 'method_body', 'destination', 'credential_mode', 'proxy', 'media_shape', 'resume', 'mirror_semantics', 'preflight_exactness'}),
        ('XGO-CAP-BACKEND-002', 'XGO-40', {'deterministic_same_input', 'preference_compatible_only', 'unavailable_backend_fallback', 'degraded_backend', 'direct_http_default', 'mirrors_and_ftp', 'media_external_shape', 'migration_cost', 'started_attempt_fence', 'attempt_scoped_persistence'}),
    ]
    fixture_evidence = {}
    closed = []
    for cap_id, overlay, expected in specs:
        fixture_id, names = require_capability(root, caps, fixtures, cap_id, overlay, expected)
        fixture_evidence[fixture_id] = sorted(names)
        closed.append(cap_id)

    cfg = tomllib.loads((root / '.devtool.toml').read_text())
    target = cfg.get('targets', {}).get('xgo_backends', {})
    if target.get('runner') != 'go':
        raise SystemExit('xgo_backends must remain on the native Go runner')
    nodes = target.get('workflows', {}).get('validate', [])
    ids = [n.get('id') for n in nodes]
    expected_ids = ['go', 'backend_contract_audit', 'post_replay_lab', 'ftp_lab', 'metalink_corpus', 'compatibility_matrix', 'selection_matrix']
    if ids != expected_ids:
        raise SystemExit(f'xgo_backends validate DAG mismatch: {ids}')
    expected_deps = {
        'go': [],
        'backend_contract_audit': ['go'],
        'post_replay_lab': ['backend_contract_audit'],
        'ftp_lab': ['post_replay_lab'],
        'metalink_corpus': ['ftp_lab'],
        'compatibility_matrix': ['metalink_corpus'],
        'selection_matrix': ['compatibility_matrix'],
    }
    for node in nodes:
        if node.get('depends_on', []) != expected_deps[node['id']]:
            raise SystemExit(f"xgo_backends dependency mismatch for {node['id']}: {node.get('depends_on', [])}")

    expected_modes = {
        'ftp_lab': 'ftp',
        'metalink_corpus': 'metalink',
        'compatibility_matrix': 'compatibility',
        'selection_matrix': 'selection',
    }
    for job_id, mode in expected_modes.items():
        if not command_has_mode(target.get('jobs', {}).get(job_id, {}), mode):
            raise SystemExit(f'{job_id} is not wired to xgo-backends-audit --mode {mode}')

    report = {
        'schema_version': 1,
        'status': 'pass',
        'closed_capabilities': closed,
        'fixtures': fixture_evidence,
        'workflow_nodes': ids,
        'shared_ledger_audit': json.loads(ledger_report.read_text()),
        'shared_fixture_audit': json.loads(fixture_report.read_text()),
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2, sort_keys=True) + '\n')
    print(json.dumps(report, sort_keys=True))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
