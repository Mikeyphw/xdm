#!/usr/bin/env python3
"""XDM source-owned Devtool contract: read-only, portable, no downloads or Gradle.

Validates config structure, wrapper target/workflow routes, workflow reference
resolution and ordered DAG dependencies, path ownership, and Python test
collection requirements. Devtool's own loader remains the execution authority.
"""
from __future__ import annotations

import json
from pathlib import Path
import sys
import tomllib

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tools' / 'xgo'))
from audit_topology import _audit_io_paths, _audit_artifact_paths, TopologyError  # noqa: E402


def require(ok: bool, message: str) -> None:
    if not ok:
        raise ValueError(message)


def audit(root: Path = ROOT) -> dict:
    path = root / '.devtool.toml'
    data = tomllib.loads(path.read_text(encoding='utf-8'))
    targets = data.get('targets', {})
    jobs = data.get('jobs', {})
    wrapper = data.get('wrapper', {})
    require(wrapper.get('schema') == 2, 'wrapper schema must be 2')
    require(isinstance(targets, dict) and len(targets) >= 42, 'XDM target inventory incomplete')
    require(isinstance(jobs, dict), 'global jobs table malformed')
    require((root/'engine/go.mod').is_file(), 'Go module missing')
    require('xgo_foundation' in targets and 'xgo_ci' in targets, 'required XGO targets absent')
    cmds = wrapper.get('commands') or {}
    require(isinstance(cmds, dict) and 'ci-preflight' in cmds, 'CI wrapper commands missing')
    for name, item in cmds.items():
        target = item.get('target')
        require(target in targets, f'wrapper {name!r} refers to unknown target: {target!r}')
        workflow = item.get('workflow')
        if workflow:
            require(workflow in (targets[target].get('workflows') or {}),
                    f'wrapper {name!r} missing workflow {target}#{workflow}')
        require(not (workflow and item.get('devtool')), f'wrapper {name!r} has contradictory modes')

    validated = 0
    for target_name, target in targets.items():
        require(isinstance(target, dict), f'{target_name}: malformed target')
        _audit_artifact_paths(target_name, target)
        _audit_io_paths(path, target_name, target)
        local_jobs = target.get('jobs') or {}
        require(isinstance(local_jobs, dict), f'{target_name}: malformed job section')
        for wf_name, steps in (target.get('workflows') or {}).items():
            require(isinstance(steps, list) and steps, f'{target_name}#{wf_name}: empty workflow')
            ids = set()
            for index, raw in enumerate(steps):
                step = {'id': f'legacy-{index}', 'ref': raw, 'depends_on': []} if isinstance(raw, str) else raw
                require(isinstance(step, dict), f'{target_name}#{wf_name}: malformed step')
                name = step.get('id')
                ref = step.get('ref')
                require(isinstance(name,str) and name and name not in ids, f'{target_name}#{wf_name}: duplicate step ID')
                deps = step.get('depends_on', [])
                require(isinstance(deps,list) and set(deps) <= ids,
                        f'{target_name}#{wf_name}.{name}: dependencies must refer to preceding nodes')
                require(isinstance(ref,str) and ':' in ref, f'{target_name}#{wf_name}.{name}: malformed ref')
                kind, identifier = ref.split(':', 1)
                if kind == 'target':
                    child, sep, child_wf = identifier.partition('#')
                    require(sep and child in targets, f'{target_name}#{wf_name}: unknown target {ref!r}')
                    child_workflows = targets[child].get('workflows') or {}
                    require(child_wf in child_workflows or child_wf == 'validate',
                            f'{target_name}#{wf_name}: unresolved child workflow {ref!r}')
                elif kind == 'job':
                    require(identifier in local_jobs or identifier in jobs,
                            f'{target_name}#{wf_name}: unknown job {ref!r}')
                elif kind == 'runner':
                    runner, sep, action = identifier.partition('#')
                    require(sep and runner and action, f'{target_name}#{wf_name}: invalid runner {ref!r}')
                else:
                    raise ValueError(f'{target_name}#{wf_name}: unsupported ref kind {kind!r}')
                ids.add(name)
                validated += 1

    foundation = targets['xgo_foundation']['jobs']['foundation_tests']
    require(foundation.get('command') == ['python3','-m','pytest','-q','tools/xgo/tests'],
            'foundation_tests must collect pytest functions and unittest cases')
    for job_name in ('ci-go-unit','ci-go-vet'):
        job = targets['xgo_ci']['jobs'][job_name]
        require(job.get('root') == 'engine', f'{job_name}: incorrect job root')
        require(job.get('inputs') == ['**','go.mod','go.sum'], f'{job_name}: incorrect module-root input scope')
    firefox = jobs['xdm-rem18-firefox-audit'].get('shell_command') or ''
    require('status=$?' in firefox and 'exit "$status"' in firefox and '| tee ' not in firefox,
            'REM18 Firefox failure propagation missing')
    return {'status':'PASS', 'target_count':len(targets), 'wrapper_command_count':len(cmds),
            'validated_workflow_nodes':validated, 'ci_wrappers':sorted(k for k in cmds if k.startswith('ci-'))}


def main() -> int:
    try:
        report = audit()
    except (ValueError, TopologyError, OSError, KeyError, tomllib.TOMLDecodeError) as exc:
        print(f'XDM_DEVTOOL_CONTRACT_FAIL: {exc}', file=sys.stderr)
        return 1
    print(json.dumps(report, sort_keys=True))
    return 0

if __name__ == '__main__':
    raise SystemExit(main())
