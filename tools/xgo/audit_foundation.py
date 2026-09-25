#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
from pathlib import Path
from typing import Any

CAPABILITY_ID_RE = re.compile(r"^XGO-CAP-[A-Z0-9-]+-[0-9]{3}$")
OVERLAY_ID_RE = re.compile(r"^XGO-[0-9]{2}$")
ALLOWED_STRATEGIES = {"android_led", "desktop_led", "merged", "redesign", "new_design"}
ALLOWED_STATUSES = {"PLANNED", "IMPLEMENTED", "INTENTIONALLY_REMOVED"}
REQUIRED_AREAS = {
    "domain",
    "persistence",
    "ownership",
    "checkpoint",
    "verification",
    "publication",
    "recovery",
    "request",
    "security",
    "http",
    "retry",
    "backend",
    "queue",
    "scheduler",
    "capture",
    "media",
    "hls",
    "dash",
    "ffmpeg",
    "settings",
    "diagnostics",
    "android",
    "desktop",
}


class AuditError(RuntimeError):
    pass


def _load_json_yaml(path: Path) -> dict[str, Any]:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError as exc:
        raise AuditError(f"missing file: {path}") from exc
    except json.JSONDecodeError as exc:
        raise AuditError(f"{path}: invalid JSON-compatible YAML: {exc}") from exc
    if not isinstance(data, dict):
        raise AuditError(f"{path}: top level must be an object")
    return data


def _sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def _aggregate(entries: list[dict[str, Any]]) -> str:
    payload = "".join(
        f"{entry['id']}\t{entry['path']}\t{entry['sha256']}\n"
        for entry in sorted(entries, key=lambda value: str(value["id"]))
    )
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


def _safe_relative(path_text: str) -> bool:
    p = Path(path_text)
    return bool(path_text) and not p.is_absolute() and ".." not in p.parts and ".git" not in p.parts


def _git(root: Path, *args: str, allow_failure: bool = False) -> str:
    proc = subprocess.run(
        ["git", "-C", str(root), *args],
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if proc.returncode != 0 and not allow_failure:
        raise AuditError(f"git {' '.join(args)} failed for {root}: {proc.stderr.strip()}")
    return proc.stdout.strip()


def _write_report(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    os.replace(tmp, path)


def audit_donor(donor_root: Path, map_path: Path, output: Path) -> dict[str, Any]:
    donor_root = donor_root.expanduser().resolve()
    repo_root = Path.cwd().resolve()
    if donor_root == repo_root:
        raise AuditError("donor root resolves to the migration repository; donor must be a separate checkout")
    if not donor_root.is_dir():
        raise AuditError(f"donor root does not exist: {donor_root}")

    inside = _git(donor_root, "rev-parse", "--is-inside-work-tree")
    if inside != "true":
        raise AuditError(f"donor root is not a Git worktree: {donor_root}")
    head = _git(donor_root, "rev-parse", "HEAD")
    branch = _git(donor_root, "branch", "--show-current", allow_failure=True) or "DETACHED"
    tracked_dirty = _git(donor_root, "status", "--porcelain", "--untracked-files=no", allow_failure=False)
    if tracked_dirty:
        raise AuditError("donor checkout has tracked modifications; commit/stash them before freezing the XGO donor baseline")

    document = _load_json_yaml(map_path)
    if document.get("schema_version") != 1:
        raise AuditError(f"{map_path}: unsupported schema_version {document.get('schema_version')!r}")
    entries = document.get("entries")
    if not isinstance(entries, list) or not entries:
        raise AuditError(f"{map_path}: entries must be a non-empty list")

    ids: set[str] = set()
    checked: list[dict[str, Any]] = []
    for idx, raw in enumerate(entries):
        if not isinstance(raw, dict):
            raise AuditError(f"{map_path}: entries[{idx}] must be an object")
        donor_id = str(raw.get("id") or "")
        rel = str(raw.get("path") or "")
        expected = str(raw.get("sha256") or "").lower()
        size = raw.get("size")
        if not donor_id or donor_id in ids:
            raise AuditError(f"{map_path}: duplicate/empty donor id {donor_id!r}")
        ids.add(donor_id)
        if not _safe_relative(rel):
            raise AuditError(f"{map_path}: unsafe donor path for {donor_id}: {rel!r}")
        if not re.fullmatch(r"[0-9a-f]{64}", expected):
            raise AuditError(f"{map_path}: invalid sha256 for {donor_id}")
        path = donor_root / rel
        if not path.is_file():
            raise AuditError(f"donor file missing for {donor_id}: {rel}")
        actual_size = path.stat().st_size
        if not isinstance(size, int) or size < 0 or size != actual_size:
            raise AuditError(f"donor size mismatch for {donor_id}: expected {size}, actual {actual_size}")
        actual = _sha256(path)
        if actual != expected:
            raise AuditError(f"donor hash mismatch for {donor_id}: {rel}: expected {expected}, actual {actual}")
        checked.append({"id": donor_id, "path": rel, "sha256": actual, "size": actual_size})

    aggregate = _aggregate(checked)
    expected_aggregate = str(document.get("aggregate_sha256") or "").lower()
    if aggregate != expected_aggregate:
        raise AuditError(f"donor aggregate mismatch: expected {expected_aggregate}, actual {aggregate}")

    report = {
        "schema_version": 1,
        "status": "pass",
        "donor_root": str(donor_root),
        "git_head": head,
        "git_branch": branch,
        "tracked_worktree_clean": True,
        "entry_count": len(checked),
        "aggregate_sha256": aggregate,
    }
    _write_report(output, report)
    return report


def audit_ledger(map_path: Path, ledger_path: Path, output: Path) -> dict[str, Any]:
    donor_map = _load_json_yaml(map_path)
    donors_raw = donor_map.get("entries")
    if not isinstance(donors_raw, list):
        raise AuditError(f"{map_path}: entries must be a list")
    donor_ids = {str(item.get("id") or "") for item in donors_raw if isinstance(item, dict)}
    if "" in donor_ids:
        raise AuditError(f"{map_path}: donor entry has empty id")

    ledger = _load_json_yaml(ledger_path)
    if ledger.get("schema_version") != 1:
        raise AuditError(f"{ledger_path}: unsupported schema_version {ledger.get('schema_version')!r}")
    capabilities = ledger.get("capabilities")
    if not isinstance(capabilities, list) or not capabilities:
        raise AuditError(f"{ledger_path}: capabilities must be a non-empty list")
    minimum = ledger.get("minimum_capability_count", 0)
    if not isinstance(minimum, int) or minimum < 1:
        raise AuditError(f"{ledger_path}: minimum_capability_count must be a positive integer")
    if len(capabilities) < minimum:
        raise AuditError(f"{ledger_path}: capability count {len(capabilities)} is below required minimum {minimum}")

    cap_ids: set[str] = set()
    referenced_donors: set[str] = set()
    areas: set[str] = set()
    overlay_counts: dict[str, int] = {}
    for idx, raw in enumerate(capabilities):
        if not isinstance(raw, dict):
            raise AuditError(f"{ledger_path}: capabilities[{idx}] must be an object")
        cid = str(raw.get("id") or "")
        if not CAPABILITY_ID_RE.fullmatch(cid):
            raise AuditError(f"{ledger_path}: invalid capability id {cid!r}")
        if cid in cap_ids:
            raise AuditError(f"{ledger_path}: duplicate capability id {cid}")
        cap_ids.add(cid)
        area = str(raw.get("area") or "")
        if not area:
            raise AuditError(f"{ledger_path}: {cid} has empty area")
        areas.add(area)
        if not str(raw.get("behavior") or "").strip():
            raise AuditError(f"{ledger_path}: {cid} has empty behavior")
        if not str(raw.get("canonical_behavior") or "").strip():
            raise AuditError(f"{ledger_path}: {cid} has empty canonical_behavior")
        strategy = str(raw.get("donor_strategy") or "")
        if strategy not in ALLOWED_STRATEGIES:
            raise AuditError(f"{ledger_path}: {cid} has invalid donor_strategy {strategy!r}")
        status = str(raw.get("status") or "")
        if status not in ALLOWED_STATUSES:
            raise AuditError(f"{ledger_path}: {cid} has invalid status {status!r}")
        overlay = str(raw.get("target_overlay") or "")
        if not OVERLAY_ID_RE.fullmatch(overlay):
            raise AuditError(f"{ledger_path}: {cid} has invalid target_overlay {overlay!r}")
        overlay_counts[overlay] = overlay_counts.get(overlay, 0) + 1
        test_id = str(raw.get("test_id") or "")
        if not test_id.startswith(("fixture:", "test:", "audit:")):
            raise AuditError(f"{ledger_path}: {cid} has invalid test_id {test_id!r}")
        donors = raw.get("donors")
        if not isinstance(donors, list):
            raise AuditError(f"{ledger_path}: {cid} donors must be a list")
        donor_refs = [str(value) for value in donors]
        if strategy != "new_design" and not donor_refs:
            raise AuditError(f"{ledger_path}: {cid} must reference donor evidence")
        unknown = sorted(set(donor_refs) - donor_ids)
        if unknown:
            raise AuditError(f"{ledger_path}: {cid} references unknown donors: {', '.join(unknown)}")
        referenced_donors.update(donor_refs)

    missing_areas = sorted(REQUIRED_AREAS - areas)
    if missing_areas:
        raise AuditError(f"{ledger_path}: missing required capability areas: {', '.join(missing_areas)}")
    unreferenced = sorted(donor_ids - referenced_donors)
    if unreferenced:
        raise AuditError(f"{ledger_path}: donor-map entries not referenced by any capability: {', '.join(unreferenced)}")

    report = {
        "schema_version": 1,
        "status": "pass",
        "capability_count": len(capabilities),
        "donor_count": len(donor_ids),
        "referenced_donor_count": len(referenced_donors),
        "areas": sorted(areas),
        "target_overlay_counts": dict(sorted(overlay_counts.items())),
    }
    _write_report(output, report)
    return report


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Validate the XGO donor freeze and capability ledger")
    sub = parser.add_subparsers(dest="command", required=True)

    donor = sub.add_parser("donor", help="verify selected donor files against the untouched donor checkout")
    donor.add_argument("--donor-root", default="../xdm")
    donor.add_argument("--map", dest="map_path", default="engine/docs/donor-map.yaml")
    donor.add_argument("--output", default=".devtool/reports/xgo/foundation/donor-audit.json")

    ledger = sub.add_parser("ledger", help="validate capability-ledger structure and donor cross-references")
    ledger.add_argument("--map", dest="map_path", default="engine/docs/donor-map.yaml")
    ledger.add_argument("--ledger", dest="ledger_path", default="engine/docs/capability-ledger.yaml")
    ledger.add_argument("--output", default=".devtool/reports/xgo/foundation/capability-ledger-audit.json")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    try:
        if args.command == "donor":
            report = audit_donor(Path(args.donor_root), Path(args.map_path), Path(args.output))
            print(f"XGO donor audit PASS: {report['entry_count']} files, {report['aggregate_sha256']}")
        else:
            report = audit_ledger(Path(args.map_path), Path(args.ledger_path), Path(args.output))
            print(f"XGO capability ledger audit PASS: {report['capability_count']} capabilities, {report['donor_count']} donors")
        return 0
    except AuditError as exc:
        print(f"XGO foundation audit FAIL: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
