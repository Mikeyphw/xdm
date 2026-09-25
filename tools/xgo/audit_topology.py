#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import os
import re
import tomllib
from pathlib import Path
from typing import Any

SUBSYSTEM_TARGETS = [
    "xgo_foundation", "xgo_store", "xgo_security", "xgo_transfer",
    "xgo_backends", "xgo_scheduler", "xgo_media", "xgo_ops",
    "xgo_android", "xgo_desktop", "xgo_seal",
]
GATE_TARGETS = [
    "xgo_gate_spec", "xgo_gate_foundation", "xgo_gate_durable",
    "xgo_gate_security", "xgo_gate_transfer", "xgo_gate_backends",
    "xgo_gate_scheduler", "xgo_gate_media_identity", "xgo_gate_media_exec",
    "xgo_gate_ops", "xgo_gate_android", "xgo_gate_cross_host", "xgo_gate_final",
]
TARGET_REF_RE = re.compile(r"^target:([A-Za-z0-9_.-]+)#([A-Za-z0-9_.-]+)$")
JOB_REF_RE = re.compile(r"^job:([A-Za-z0-9_.-]+)$")


class TopologyError(RuntimeError):
    pass


def _load(path: Path) -> dict[str, Any]:
    try:
        with path.open("rb") as fh:
            data = tomllib.load(fh)
    except FileNotFoundError as exc:
        raise TopologyError(f"missing config: {path}") from exc
    except tomllib.TOMLDecodeError as exc:
        raise TopologyError(f"invalid TOML: {exc}") from exc
    return data


def _write(path: Path | None, payload: dict[str, Any]) -> None:
    if path is None:
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    os.replace(tmp, path)


def _steps(target_name: str, target: dict[str, Any]) -> list[dict[str, Any]]:
    workflows = target.get("workflows")
    if not isinstance(workflows, dict):
        raise TopologyError(f"{target_name}: missing workflows table")
    validate = workflows.get("validate")
    if not isinstance(validate, list) or not validate:
        raise TopologyError(f"{target_name}: validate workflow must be non-empty")
    normalized: list[dict[str, Any]] = []
    ids: set[str] = set()
    for idx, raw in enumerate(validate):
        if isinstance(raw, str):
            item = {"id": f"step_{idx}", "ref": raw, "depends_on": []}
        elif isinstance(raw, dict):
            item = dict(raw)
        else:
            raise TopologyError(f"{target_name}: invalid workflow step {idx}")
        sid = str(item.get("id") or "").strip()
        ref = str(item.get("ref") or "").strip()
        deps = item.get("depends_on") or []
        if not sid or sid in ids:
            raise TopologyError(f"{target_name}: duplicate/empty workflow step id {sid!r}")
        if not ref:
            raise TopologyError(f"{target_name}: step {sid} has empty ref")
        if not isinstance(deps, list):
            raise TopologyError(f"{target_name}: step {sid} depends_on must be a list")
        ids.add(sid)
        normalized.append({"id": sid, "ref": ref, "depends_on": [str(x) for x in deps]})
    for item in normalized:
        unknown = sorted(set(item["depends_on"]) - ids)
        if unknown:
            raise TopologyError(f"{target_name}: step {item['id']} depends on unknown nodes {unknown}")
    # DAG check
    graph = {i["id"]: set(i["depends_on"]) for i in normalized}
    pending = dict(graph)
    resolved: set[str] = set()
    while pending:
        ready = [node for node, deps in pending.items() if deps <= resolved]
        if not ready:
            raise TopologyError(f"{target_name}: validate workflow contains a dependency cycle")
        for node in ready:
            resolved.add(node)
            pending.pop(node)
    return normalized


def audit(config_path: Path, output: Path | None, only_target: str = "") -> dict[str, Any]:
    data = _load(config_path)
    targets = data.get("targets")
    if not isinstance(targets, dict):
        raise TopologyError("config has no targets table")
    expected = SUBSYSTEM_TARGETS + GATE_TARGETS
    missing = [name for name in expected if name not in targets]
    if missing:
        raise TopologyError(f"missing XGO targets: {', '.join(missing)}")

    workspace = data.get("workspace")
    groups = workspace.get("groups") if isinstance(workspace, dict) else None
    if not isinstance(groups, dict):
        raise TopologyError("workspace groups missing")
    xgo_group = groups.get("xgo")
    if not isinstance(xgo_group, list) or [str(v) for v in xgo_group] != SUBSYSTEM_TARGETS:
        raise TopologyError("workspace.groups.xgo must list all subsystem targets in canonical order")

    inspected: dict[str, Any] = {}
    names = [only_target] if only_target else expected
    for name in names:
        if name not in targets:
            raise TopologyError(f"unknown target requested: {name}")
        target = targets[name]
        if not isinstance(target, dict):
            raise TopologyError(f"{name}: target must be a table")
        env = str(target.get("execution_environment") or "")
        if env != "native-termux":
            raise TopologyError(f"{name}: bootstrap execution_environment must be native-termux, got {env!r}")
        validate = _steps(name, target)
        jobs = target.get("jobs") if isinstance(target.get("jobs"), dict) else {}
        for step in validate:
            ref = step["ref"]
            jm = JOB_REF_RE.fullmatch(ref)
            if jm and jm.group(1) not in jobs:
                raise TopologyError(f"{name}: workflow references missing target-local job {jm.group(1)!r}")
            tm = TARGET_REF_RE.fullmatch(ref)
            if tm:
                child, workflow = tm.groups()
                if child not in targets:
                    raise TopologyError(f"{name}: workflow references unknown target {child}")
                if workflow != "validate":
                    raise TopologyError(f"{name}: XGO cross-target refs must use #validate, got #{workflow}")
                _steps(child, targets[child])
        inspected[name] = {
            "runner": str(target.get("runner") or ""),
            "root": str(target.get("root") or ""),
            "execution_environment": env,
            "validate_node_count": len(validate),
        }

    # Gate target workflows must cross target boundaries, never reach into a foreign job.
    for name in GATE_TARGETS:
        for step in _steps(name, targets[name]):
            if JOB_REF_RE.fullmatch(step["ref"]):
                raise TopologyError(f"{name}: gate workflow must compose target workflows, not target-local jobs")

    report = {
        "schema_version": 1,
        "status": "pass",
        "subsystem_target_count": len(SUBSYSTEM_TARGETS),
        "gate_target_count": len(GATE_TARGETS),
        "inspected": inspected,
    }
    _write(output, report)
    return report


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("mode", choices=["audit", "target-ready"])
    p.add_argument("--config", type=Path, default=Path(".devtool.toml"))
    p.add_argument("--target", default="")
    p.add_argument("--output", type=Path)
    args = p.parse_args()
    try:
        report = audit(args.config, args.output, only_target=args.target if args.mode == "target-ready" else "")
    except TopologyError as exc:
        print(f"XGO topology audit FAILED: {exc}", file=os.sys.stderr)
        return 1
    print(json.dumps(report, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
