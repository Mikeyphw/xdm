#!/usr/bin/env python3
"""Read-only XDM/Devtool Phase 2 real-configuration affected-ownership audit.

Runs Devtool's actual config loader and affected selector; no downloads or builds.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys
import tomllib


def audit(xdm_root: Path, devtool_root: Path) -> dict:
    xdm_root = xdm_root.expanduser().resolve()
    devtool_root = devtool_root.expanduser().resolve()
    config_file = xdm_root / '.devtool.toml'
    data = tomllib.loads(config_file.read_text(encoding='utf-8'))
    targets = data['targets']
    invalid = [name for name, target in targets.items()
               if target.get('metadata', {}).get('affected_ownership') != 'explicit'
               or not target.get('inputs')]
    if invalid:
        raise RuntimeError(f'missing explicit ownership/inputs: {invalid}')
    if not (devtool_root / 'devtool/affected.py').is_file():
        raise RuntimeError(f'Devtool source unavailable at {devtool_root}')
    # Load the installed checkout, never a remote runtime or ambient pip
    # installation; use the selected Devtool's canonical parser.
    sys.path.insert(0, str(devtool_root))
    from devtool.config import load_wrapper_project_config
    from devtool.affected import analyze_affected_targets, _target_match_reasons

    cfg = load_wrapper_project_config(str(xdm_root), str(config_file))
    if len(cfg.targets) != len(targets):
        raise RuntimeError('Devtool loader target count differs from TOML')

    probes = {
        'go-store': ('engine/store/sqlite/phase02_source_probe.go', 'xgo_store', 'xgo_rm08_aria2_device'),
        'android-test': ('app/XDM.Android/app/src/androidTest/Phase02Probe.kt', 'xdm_android', 'xgo_store'),
        'desktop': ('app/XDM/src/XDM.App/Phase02Probe.cs', 'xdm_modern', 'xgo_store'),
    }
    result = {}
    for case, (path, expect, reject) in probes.items():
        reasons_yes = _target_match_reasons(cfg, expect, path)
        reasons_no = _target_match_reasons(cfg, reject, path)
        if not reasons_yes or reasons_no:
            raise RuntimeError(
                f'{case}: scope classification wrong; expected {expect}={bool(reasons_yes)}, '
                f'{reject}={bool(reasons_no)}'
            )
        selection = analyze_affected_targets(cfg, changed_paths=[path])
        if expect not in selection.targets or reject in selection.direct_targets:
            raise RuntimeError(f'{case}: affected selection unexpected: {selection.direct_targets}')
        result[case] = {'path': path, 'expected_direct': expect, 'not_direct': reject,
                        'direct_count': len(selection.direct_targets),
                        'selected_count': len(selection.targets)}

    unknown = 'phase02-unowned-sentinel/random-no-owner.bin'
    sel = analyze_affected_targets(cfg, changed_paths=[unknown])
    if unknown not in sel.uncertain_paths or set(sel.targets) != set(cfg.targets):
        raise RuntimeError('unknown file must fall back to selecting all candidates')
    config_sel = analyze_affected_targets(cfg, changed_paths=['.devtool.toml'])
    if set(config_sel.direct_targets) != set(cfg.targets):
        raise RuntimeError('configuration changes must directly select every target')

    return {'status': 'PASS', 'schema_version': 1, 'targets': len(targets),
            'scenarios': result, 'unknown_file': 'workspace-wide',
            'config_change': 'workspace-wide'}


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--xdm', type=Path, default=Path(__file__).resolve().parents[1])
    p.add_argument('--devtool', type=Path, default=Path.home() / 'Code/devtool')
    args = p.parse_args()
    try:
        result = audit(args.xdm, args.devtool)
    except (RuntimeError, OSError, ValueError, SystemExit) as exc:
        print(f'XDM_PHASE02_AFFECTED_REFUSED: {exc}', file=sys.stderr)
        return 2
    print(json.dumps(result, indent=2, sort_keys=True))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
