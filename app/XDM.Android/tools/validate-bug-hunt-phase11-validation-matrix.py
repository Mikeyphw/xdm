#!/usr/bin/env python3
from __future__ import annotations
import json, re, sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
REPO=ROOT.parent.parent
errors=[]
def text(rel:str)->str:
    p=ROOT/rel
    if not p.is_file():
        errors.append(f'missing {rel}')
        return ''
    return p.read_text(encoding='utf-8')
def repo_text(rel:str)->str:
    p=REPO/rel
    if not p.is_file():
        errors.append(f'missing {rel}')
        return ''
    return p.read_text(encoding='utf-8')
def require(cond,msg):
    if not cond: errors.append(msg)

matrix_path=ROOT/'tools/bug-hunt-phase11-validation-matrix.json'
try:
    matrix=json.loads(matrix_path.read_text(encoding='utf-8'))
except Exception as exc:
    raise SystemExit(f'cannot parse Phase 11 matrix: {exc}')
entries=matrix.get('entries', [])
require(matrix.get('roadmap_phase') == 11, 'matrix must declare roadmap phase 11')
require(matrix.get('current_room_schema') == 17, 'matrix must bind current Room schema 17')
require(matrix.get('total_requirements') == 80, 'matrix must declare all 80 roadmap requirements')
require(len(entries) == 80, f'matrix must contain 80 entries, found {len(entries)}')
ids=[e.get('id') for e in entries]
require(ids == [f'BH11-{i:03d}' for i in range(1,81)], 'matrix IDs must be contiguous BH11-001..BH11-080')
roadmap=text('docs/ANDROID_BUG_HUNT_REMEDIATION_ROADMAP.md')
section=roadmap.split('## Phase 11: Validation Matrix',1)[1].split('## Suggested Work Order',1)[0] if '## Phase 11: Validation Matrix' in roadmap and '## Suggested Work Order' in roadmap else ''
roadmap_items=[line[2:].strip() for line in section.splitlines() if line.startswith('- ')]
require(len(roadmap_items) == 80, f'repo roadmap must include complete 80-item Phase 11 list, found {len(roadmap_items)}')
for i,(expected,entry) in enumerate(zip(roadmap_items, entries),1):
    require(entry.get('requirement') == expected, f'{entry.get("id", i)} requirement must match roadmap text exactly')

runner=text('tools/run-bug-hunt-phase11-validation-matrix.sh')
workflow=repo_text('.github/workflows/android.yml')
contract=text('app/src/test/kotlin/com/mikeyphw/xdm/android/BugHuntPhase11ValidationMatrixContractTest.kt')
doc=text('docs/release/BUG-HUNT-PHASE-11-VALIDATION-MATRIX.md')
project=json.loads(text('PROJECT_MANIFEST.json') or '{}')
phase=project.get('bug_hunt_phase_11_validation_matrix', {})

for needle in ['validate-bug-hunt-phase1-external-control-secrets-privacy.py','validate-bug-hunt-phase2-download-execution.py','validate-bug-hunt-phase3-storage-publication-verification-repair.py','validate-bug-hunt-phase4-queue-scheduling-state-machines.py','validate-bug-hunt-phase5-browser-handoff-media.py','validate-bug-hunt-phase6-database-integrity-migrations.py','validate-bug-hunt-phase7-post-processing-termux.py','validate-bug-hunt-phase8-download-actions-ui-truthfulness.py','validate-bug-hunt-phase9-accessibility-adaptive-layout.py','validate-bug-hunt-phase10-release-upgrade-packaging.py','validate-bug-hunt-phase11-validation-matrix.py']:
    require(needle in runner, f'Phase 11 runner missing {needle}')
for task in [':core-model:test',':persistence:testDebugUnitTest',':scheduler:testDebugUnitTest',':media:test',':browser-extension:test',':app:testDebugUnitTest',':app:connectedDebugAndroidTest',':app:lintRelease',':app:bundleRelease']:
    require(task in runner, f'Phase 11 runner missing Gradle task {task}')
require('run-bug-hunt-phase10-release-gate.sh' in runner, 'Phase 11 runner must include release gate handoff')
require('run-phase10-install-upgrade-matrix.sh' in runner, 'Phase 11 runner must mention install/upgrade matrix')
require('--static-only' in runner and '--release-only' in runner and '--device-only' in runner, 'Phase 11 runner must expose static/release/device modes')
require('bug-hunt-phase11-validation-matrix.json' in runner, 'runner must name matrix file')
require('validate-bug-hunt-phase11-validation-matrix.py' in workflow and 'run-bug-hunt-phase11-validation-matrix.sh --static-only --ci' in workflow, 'Android CI must run Phase 11 static matrix gate')

phase11_self_evidence={
    'tools/bug-hunt-phase11-validation-matrix.json',
    'tools/run-bug-hunt-phase11-validation-matrix.sh',
}
levels=set()
validator_seen=set()
task_seen=set()
for entry in entries:
    eid=entry.get('id','<missing>')
    levels.add(entry.get('execution_level'))
    require(entry.get('gate') == 'tools/run-bug-hunt-phase11-validation-matrix.sh', f'{eid} must route through Phase 11 gate')
    refs=entry.get('evidence_files') or []
    vals=entry.get('validators') or []
    tasks=entry.get('gradle_tasks') or []
    require(len(refs) >= 2, f'{eid} must have at least two evidence files')
    require(vals, f'{eid} must list validators')
    require(tasks, f'{eid} must list Gradle tasks')
    require(any('/src/test/' in r or r.endswith('.py') or r.endswith('.sh') for r in refs), f'{eid} has doc-only evidence')
    phase_specific_refs=[r for r in refs if r not in phase11_self_evidence]
    phase_specific_tests=[r for r in phase_specific_refs if '/src/test/' in r and 'Phase11' not in Path(r).name]
    phase_specific_validators=[v for v in vals if 'phase11' not in v.lower()]
    require(phase_specific_refs, f'{eid} has self-only Phase 11 evidence')
    require(phase_specific_tests, f'{eid} must include phase-specific executable evidence beyond the Phase 11 matrix/runner')
    require(phase_specific_validators, f'{eid} must include a phase-specific validator beyond Phase 11')
    for rel in refs + vals:
        p=ROOT/rel
        require(p.is_file(), f'{eid} references missing file {rel}')
    for val in vals:
        validator_seen.add(val)
        require(val in runner, f'{eid} validator {val} must be invoked by runner')
    for task in tasks:
        task_seen.add(task)
        if task.startswith(':app:connected') or task in {':app:lintRelease',':app:bundleRelease',':app:testReleaseUnitTest'}:
            require(task in runner or 'RELEASE_TASKS' in runner or 'DEVICE_TASKS' in runner, f'{eid} task {task} must be represented in runner')
        else:
            require(task in runner or 'COMMON_TASKS' in runner, f'{eid} task {task} must be represented in runner')

for expected in ['unit','instrumentation','release']:
    require(expected in levels, f'matrix must include {expected} execution rows')
for expected in ['tools/validate-bug-hunt-phase6-database-integrity-migrations.py','tools/verify-phase10-backup-policy.py','tools/verify-phase10-release-artifacts.py']:
    require(expected in validator_seen, f'matrix entries must reference {expected}')
for expected in [':app:connectedDebugAndroidTest',':app:bundleRelease',':persistence:testDebugUnitTest']:
    require(expected in task_seen, f'matrix entries must require {expected}')

for needle in ['BH11-001','BH11-080','80 roadmap requirements','doc-only evidence','Phase 11 Validation Matrix','self-only Phase 11 evidence']:
    require(needle in contract or needle in doc, f'contract/doc missing {needle}')
require(phase.get('status') == 'implemented', 'PROJECT_MANIFEST must mark bug_hunt_phase_11_validation_matrix implemented')
require(phase.get('roadmap_requirement_count') == 80, 'PROJECT_MANIFEST must record 80 Phase 11 requirements')
require(phase.get('matrix_file') == 'tools/bug-hunt-phase11-validation-matrix.json', 'PROJECT_MANIFEST must point at matrix file')
require(phase.get('runner') == 'tools/run-bug-hunt-phase11-validation-matrix.sh', 'PROJECT_MANIFEST must point at Phase 11 runner')
require(phase.get('coverage_levels') == ['unit','instrumentation','device','release'], 'PROJECT_MANIFEST must record coverage levels')
require(project.get('next_phase') == 'complete', 'PROJECT_MANIFEST next_phase must be complete after bug-hunt Phase 11')

if errors:
    print('Bug-hunt Phase 11 validation matrix failed:')
    for e in errors: print('- '+e)
    sys.exit(1)
print('Bug-hunt Phase 11 validation matrix passed: 80 roadmap rows, executable evidence, CI runner, and manifest handoff verified')
