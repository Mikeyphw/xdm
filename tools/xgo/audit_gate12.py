#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
import tomllib
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[2]


class Gate12Error(RuntimeError):
    pass


def load_json(path: Path) -> dict[str, Any]:
    if not path.is_file():
        raise Gate12Error(f"missing required Gate-12 evidence: {path.relative_to(ROOT) if path.is_relative_to(ROOT) else path}")
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise Gate12Error(f"invalid JSON evidence {path}: {exc}") from exc
    if not isinstance(value, dict):
        raise Gate12Error(f"Gate-12 evidence must be an object: {path}")
    return value


def canonical_hash(value: Any) -> str:
    raw = json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=True).encode("utf-8")
    return hashlib.sha256(raw).hexdigest()


def require(condition: bool, message: str) -> None:
    if not condition:
        raise Gate12Error(message)


def validate_topology(config: dict[str, Any], policy: dict[str, Any]) -> dict[str, Any]:
    targets = config.get("targets") or {}
    gate = targets.get(policy["gate_target"]) or {}
    observed_gate = ((gate.get("workflows") or {}).get("validate") or [])
    expected_gate = policy.get("expected_gate_nodes") or []
    require(observed_gate == expected_gate, f"GATE-12 topology drifted: {observed_gate}")

    refs = [str(node.get("ref") or "") for node in observed_gate]
    require("target:xgo_gate_cross_host#validate" not in refs,
            "lean GATE-12 must not replay the historical cross-host gate")
    require(refs.count("target:xgo_seal#validate") == 1,
            "GATE-12 must execute the Wave 12 xgo_seal workflow exactly once")

    seal = targets.get("xgo_seal") or {}
    seal_nodes = ((seal.get("workflows") or {}).get("validate") or [])
    observed_ids = [str(node.get("id") or "") for node in seal_nodes]
    expected_ids = [str(v) for v in policy.get("expected_seal_nodes") or []]
    require(observed_ids == expected_ids, f"Wave 12 seal topology drifted: {observed_ids}")
    return {"gate_node_count": len(observed_gate), "seal_node_count": len(seal_nodes), "gate_refs": refs}


def validate_reports(reports: dict[str, dict[str, Any]], policy: dict[str, Any]) -> dict[str, Any]:
    for report_id, report in reports.items():
        require(report.get("status") == "pass", f"Wave 12 report {report_id} did not pass")

    counts = policy.get("expected_counts") or {}
    differential = reports["differential"]
    require(int((differential.get("classification_counts") or {}).get("regression") or 0) == int(counts["differential_regressions"]),
            "differential regression count is non-zero")
    require(int(differential.get("capability_count") or 0) == int(counts["capabilities"]),
            "differential capability count drifted")

    state = reports["state_machine"]
    require(int(state.get("operation_count") or 0) == int(counts["state_machine_operations"]),
            "state-machine operation inventory drifted")
    race = state.get("race_detector") or {}
    race_status = str(race.get("status") or "")
    race_policy = policy.get("race_policy") or {}
    if race_status == race_policy.get("supported_status"):
        pass
    elif race_status == race_policy.get("unsupported_status") and race_policy.get("allow_unsupported_host_with_stress_fallback"):
        fallback = race.get("mandatory_stress_fallback") or {}
        require(int(fallback.get("returncode", 1)) == 0,
                "race detector unsupported on this host and mandatory stress fallback failed")
    else:
        raise Gate12Error(f"unacceptable race qualification status: {race_status!r}")

    crash = reports["crash_matrix"]
    require(int(crash.get("injection_point_count") or 0) == int(counts["crash_injection_points"]),
            "crash injection inventory drifted")
    require(all(bool(v) for v in (crash.get("invariants") or {}).values()),
            "crash matrix invariant failed")

    adversarial = reports["adversarial"]
    require(int(adversarial.get("scenario_count") or 0) == int(counts["adversarial_scenarios"]),
            "adversarial scenario inventory drifted")
    require(all(bool(v) for v in (adversarial.get("invariants") or {}).values()),
            "adversarial invariant failed")

    fuzz = reports["fuzz"]
    require(int(fuzz.get("target_count") or 0) == int(counts["fuzz_targets"]), "fuzz target count drifted")
    require(int(fuzz.get("category_count") or 0) == int(counts["fuzz_categories"]), "fuzz category count drifted")
    require(bool(fuzz.get("bounded_input_policy")), "fuzz campaign lost bounded-input policy")

    performance = reports["performance"]
    workloads = ((performance.get("engine_scale") or {}).get("workloads") or {})
    require(len(workloads) == int(counts["performance_workloads"]), "performance workload inventory drifted")
    require(all((item or {}).get("status") == "pass" for item in workloads.values()),
            "one or more performance workloads failed")

    authority = reports["single_authority"]
    require(all(bool(v) for v in (authority.get("invariants") or {}).values()),
            "single-authority invariant failed")
    negatives = ((authority.get("static_scan") or {}).get("negative_fixtures") or [])
    require(bool(negatives) and all((item or {}).get("status") == "rejected" for item in negatives),
            "single-authority negative scanner proof is incomplete")

    closure = reports["capability_closure"]
    open_gap_count = closure.get("open_gap_count")
    require(open_gap_count is not None and int(open_gap_count) == int(counts["closure_open_gaps"]),
            "capability closure has open gaps")
    summary = closure.get("closure") or {}
    require(int(summary.get("capability_count") or 0) == int(counts["capabilities"]),
            "capability closure count drifted")
    require(not bool(closure.get("remediation_required")), "capability closure still requires remediation")
    final_commands = closure.get("final_engine_commands") or []
    require(len(final_commands) >= 2 and all(int((item or {}).get("returncode", 1)) == 0 for item in final_commands),
            "XGO-89 final engine test/vet qualification is missing or failed")

    return {
        "capability_count": int(summary.get("capability_count") or 0),
        "closure_fingerprint": closure.get("closure_fingerprint"),
        "race_status": race_status,
        "fuzz_mode": (fuzz.get("execution") or {}).get("mode"),
        "performance_workload_count": len(workloads),
    }


def audit(policy_path: Path, output: Path) -> dict[str, Any]:
    policy = load_json(policy_path)
    require(int(policy.get("schema_version") or 0) == 1, "unsupported Gate-12 policy schema")
    with (ROOT / ".devtool.toml").open("rb") as fh:
        config = tomllib.load(fh)
    topology = validate_topology(config, policy)

    reports: dict[str, dict[str, Any]] = {}
    for entry in policy.get("reports") or []:
        report_id = str(entry.get("id") or "")
        path = ROOT / str(entry.get("path") or "")
        require(report_id and str(entry.get("path") or ""), "invalid Gate-12 report declaration")
        reports[report_id] = load_json(path)
    expected_report_ids = {str(v.get("id") or "") for v in policy.get("reports") or []}
    require(set(reports) == expected_report_ids and len(reports) == 10,
            "Gate-12 report inventory must cover exactly the ten Wave 12 seal nodes")

    summary = validate_reports(reports, policy)
    report = {
        "schema_version": 1,
        "status": "pass",
        "policy": policy.get("policy"),
        "scope": "Wave 12 only: XGO-81 through XGO-89 plus one direct Android host seal and one direct Desktop host seal. Historical gate workflows are intentionally not replayed.",
        "topology": topology,
        "wave12_report_count": len(reports),
        "wave12_reports": {key: {"status": value.get("status")} for key, value in sorted(reports.items())},
        "summary": summary,
        "host_final_seals": {
            "android": "target:xdm_android#validate (dependency-completed before this verifier)",
            "desktop": "target:xdm_modern#validate (dependency-completed before this verifier)",
        },
    }
    report["final_fingerprint"] = canonical_hash({
        "topology": topology,
        "summary": summary,
        "reports": sorted(reports),
        "scope": report["scope"],
    })
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description="Validate the lean, Wave-12-focused XGO final gate")
    parser.add_argument("--policy", type=Path, default=ROOT / "engine/testdata/seal/gate12-policy.json")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    policy = args.policy if args.policy.is_absolute() else ROOT / args.policy
    output = args.output if args.output.is_absolute() else ROOT / args.output
    try:
        report = audit(policy, output)
    except Gate12Error as exc:
        print(json.dumps({"schema_version": 1, "status": "fail", "error": str(exc)}, sort_keys=True))
        return 1
    print(json.dumps(report, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
