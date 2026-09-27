from __future__ import annotations

import importlib.util
from pathlib import Path

import pytest

MODULE_PATH = Path(__file__).resolve().parents[1] / "audit_gate12.py"
spec = importlib.util.spec_from_file_location("audit_gate12", MODULE_PATH)
assert spec and spec.loader
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)


def test_report_validation_rejects_failed_wave12_node():
    policy = {
        "expected_counts": {
            "capabilities": 98,
            "differential_regressions": 0,
            "state_machine_operations": 12,
            "crash_injection_points": 17,
            "adversarial_scenarios": 21,
            "fuzz_targets": 10,
            "fuzz_categories": 9,
            "performance_workloads": 8,
            "closure_open_gaps": 0,
        },
        "race_policy": {
            "allow_unsupported_host_with_stress_fallback": True,
            "supported_status": "pass",
            "unsupported_status": "unsupported_on_host",
        },
    }
    reports = {name: {"status": "pass"} for name in (
        "bootstrap", "differential", "state_machine", "crash_matrix", "adversarial",
        "fuzz", "performance", "abi_provenance", "single_authority", "capability_closure",
    )}
    reports["fuzz"]["status"] = "fail"
    with pytest.raises(mod.Gate12Error, match="fuzz did not pass"):
        mod.validate_reports(reports, policy)


def test_topology_rejects_historical_cross_host_gate():
    policy = {
        "gate_target": "xgo_gate_final",
        "expected_gate_nodes": [{"id": "bad", "ref": "target:xgo_gate_cross_host#validate", "depends_on": []}],
        "expected_seal_nodes": ["a"],
    }
    config = {"targets": {
        "xgo_gate_final": {"workflows": {"validate": policy["expected_gate_nodes"]}},
        "xgo_seal": {"workflows": {"validate": [{"id": "a", "ref": "job:a", "depends_on": []}]}},
    }}
    with pytest.raises(mod.Gate12Error, match="must not replay"):
        mod.validate_topology(config, policy)


def test_closure_zero_open_gaps_is_accepted():
    policy = {
        "expected_counts": {
            "capabilities": 98, "differential_regressions": 0, "state_machine_operations": 12,
            "crash_injection_points": 17, "adversarial_scenarios": 21, "fuzz_targets": 10,
            "fuzz_categories": 9, "performance_workloads": 8, "closure_open_gaps": 0,
        },
        "race_policy": {
            "allow_unsupported_host_with_stress_fallback": True,
            "supported_status": "pass", "unsupported_status": "unsupported_on_host",
        },
    }
    reports = {
        "bootstrap": {"status": "pass"},
        "differential": {"status": "pass", "capability_count": 98, "classification_counts": {"regression": 0}},
        "state_machine": {"status": "pass", "operation_count": 12, "race_detector": {"status": "unsupported_on_host", "mandatory_stress_fallback": {"returncode": 0}}},
        "crash_matrix": {"status": "pass", "injection_point_count": 17, "invariants": {"x": True}},
        "adversarial": {"status": "pass", "scenario_count": 21, "invariants": {"x": True}},
        "fuzz": {"status": "pass", "target_count": 10, "category_count": 9, "bounded_input_policy": True, "execution": {"mode": "portable_mutation_corpus"}},
        "performance": {"status": "pass", "engine_scale": {"workloads": {f"w{i}": {"status": "pass"} for i in range(8)}}},
        "abi_provenance": {"status": "pass"},
        "single_authority": {"status": "pass", "invariants": {"x": True}, "static_scan": {"negative_fixtures": [{"status": "rejected"}]}},
        "capability_closure": {"status": "pass", "open_gap_count": 0, "remediation_required": False, "closure": {"capability_count": 98}, "closure_fingerprint": "abc", "final_engine_commands": [{"returncode": 0}, {"returncode": 0}]},
    }
    result = mod.validate_reports(reports, policy)
    assert result["capability_count"] == 98
