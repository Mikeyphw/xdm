"""XDM Phase 2 TOML ownership and audit contract tests (no device/Gradle)."""
from __future__ import annotations

import importlib.util
from pathlib import Path
import tomllib

ROOT = Path(__file__).resolve().parents[3]
SCRIPT = ROOT / 'scripts/devtool_phase02_audit.py'


def test_phase02_all_targets_opt_in_with_inputs():
    data = tomllib.loads((ROOT / '.devtool.toml').read_text(encoding='utf-8'))
    assert len(data['targets']) >= 42
    for name, target in data['targets'].items():
        assert target['metadata']['affected_ownership'] == 'explicit', name
        assert target['inputs'], name


def test_phase02_real_scenario_contract_is_executable():
    source = SCRIPT.read_text(encoding='utf-8')
    assert 'load_wrapper_project_config' in source
    assert 'analyze_affected_targets' in source
    assert "'unknown_file': 'workspace-wide'" in source
    spec = importlib.util.spec_from_file_location('phase02_audit', SCRIPT)
    assert spec and spec.loader
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    assert callable(mod.audit)
