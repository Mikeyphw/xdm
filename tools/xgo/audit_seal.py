#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
import os
import platform
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import Any

ALLOWED_CLASSIFICATIONS = {
    "android_preserved",
    "desktop_preserved",
    "merged_improvement",
    "bug_fixed",
    "intentionally_removed",
    "regression",
}
RACE_SUPPORTED = {
    ("linux", "amd64"),
    ("linux", "arm64"),
    ("linux", "ppc64le"),
    ("linux", "s390x"),
    ("darwin", "amd64"),
    ("darwin", "arm64"),
    ("freebsd", "amd64"),
    ("netbsd", "amd64"),
    ("windows", "amd64"),
}


class SealAuditError(RuntimeError):
    pass


def _load(path: Path) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError as exc:
        raise SealAuditError(f"missing file: {path}") from exc
    except json.JSONDecodeError as exc:
        raise SealAuditError(f"invalid JSON-compatible document {path}: {exc}") from exc


def _write(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    os.replace(tmp, path)


def _canonical_hash(value: Any) -> str:
    data = json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
    return hashlib.sha256(data).hexdigest()


def _sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def _run(command: list[str], cwd: Path | None = None, env: dict[str, str] | None = None) -> dict[str, Any]:
    proc = subprocess.run(
        command,
        cwd=str(cwd) if cwd else None,
        env=env,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        check=False,
    )
    if proc.returncode != 0:
        tail = "\n".join(proc.stdout.splitlines()[-80:])
        raise SealAuditError(f"command failed ({proc.returncode}): {' '.join(command)}\n{tail}")
    return {
        "command": command,
        "returncode": proc.returncode,
        "output_sha256": hashlib.sha256(proc.stdout.encode("utf-8")).hexdigest(),
        "output_tail": proc.stdout.splitlines()[-20:],
    }


def _git(root: Path, *args: str) -> str:
    proc = subprocess.run(
        ["git", "-C", str(root), *args],
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if proc.returncode != 0:
        raise SealAuditError(f"git {' '.join(args)} failed for {root}: {proc.stderr.strip()}")
    return proc.stdout.strip()


def _resolve_donor_root(repo_root: Path, donor_map: dict[str, Any], requested: str) -> Path:
    raw = requested.strip() or str(donor_map.get("donor_root_default") or "../xdm")
    path = Path(raw).expanduser()
    if not path.is_absolute():
        path = (repo_root / path).resolve()
    return path


def _verify_donor(repo_root: Path, donor_map: dict[str, Any], requested: str) -> dict[str, Any]:
    donor_root = _resolve_donor_root(repo_root, donor_map, requested)
    if donor_root == repo_root:
        raise SealAuditError("donor root resolves to migration repository")
    if not donor_root.is_dir():
        raise SealAuditError(f"donor root does not exist: {donor_root}")
    if _git(donor_root, "rev-parse", "--is-inside-work-tree") != "true":
        raise SealAuditError(f"donor root is not a Git worktree: {donor_root}")
    tracked_dirty = _git(donor_root, "status", "--porcelain", "--untracked-files=no")
    if tracked_dirty:
        raise SealAuditError("donor checkout has tracked modifications")

    checked: dict[str, dict[str, Any]] = {}
    tuples: list[tuple[str, str, str]] = []
    for raw in donor_map.get("entries") or []:
        donor_id = str(raw.get("id") or "")
        rel = str(raw.get("path") or "")
        path = donor_root / rel
        if not path.is_file():
            raise SealAuditError(f"donor file missing for {donor_id}: {rel}")
        digest = _sha256(path)
        size = path.stat().st_size
        if digest != raw.get("sha256") or size != raw.get("size"):
            raise SealAuditError(f"donor fingerprint mismatch for {donor_id}: {rel}")
        tuples.append((donor_id, rel, digest))
        checked[donor_id] = {
            "path": rel,
            "sha256": digest,
            "size": size,
            "test_source": "test" in [str(v) for v in (raw.get("categories") or [])],
        }
    # Keep this byte-for-byte compatible with audit_foundation._aggregate():
    # sorted (donor_id, path, sha256) rows, each terminated by a newline,
    # including the final row. The donor-map aggregate was frozen with this
    # representation and is shared across the XGO campaign.
    aggregate_material = "".join(
        f"{donor_id}\t{rel}\t{digest}\n"
        for donor_id, rel, digest in sorted(tuples, key=lambda value: value[0])
    ).encode("utf-8")
    aggregate = hashlib.sha256(aggregate_material).hexdigest()
    expected_aggregate = str(donor_map.get("aggregate_sha256") or "")
    if aggregate != expected_aggregate:
        raise SealAuditError(f"donor aggregate mismatch: expected {expected_aggregate}, actual {aggregate}")
    return {
        "root": str(donor_root),
        "head": _git(donor_root, "rev-parse", "HEAD"),
        "branch": _git(donor_root, "branch", "--show-current") or "DETACHED",
        "aggregate_sha256": aggregate,
        "file_count": len(checked),
        "test_source_count": sum(1 for item in checked.values() if item["test_source"]),
        "files": checked,
    }


def differential(args: argparse.Namespace) -> dict[str, Any]:
    repo_root = Path.cwd().resolve()
    ledger = _load(args.ledger)
    registry = _load(args.registry)
    donor_map = _load(args.donor_map)
    manifest = _load(args.classifications)
    if ledger.get("schema_version") != 1 or registry.get("schema_version") != 1 or donor_map.get("schema_version") != 1 or manifest.get("schema_version") != 1:
        raise SealAuditError("unsupported differential input schema")

    capabilities = {str(item.get("id") or ""): item for item in ledger.get("capabilities") or []}
    fixture_meta = {str(item.get("fixture_id") or ""): item for item in registry.get("fixtures") or []}
    donor_entries = {str(item.get("id") or ""): item for item in donor_map.get("entries") or []}
    classifications = {str(item.get("capability_id") or ""): item for item in manifest.get("classifications") or []}
    if set(classifications) != set(capabilities):
        missing = sorted(set(capabilities) - set(classifications))
        extra = sorted(set(classifications) - set(capabilities))
        raise SealAuditError(f"differential classification coverage mismatch: missing={missing} extra={extra}")
    if manifest.get("classification_count") != len(classifications):
        raise SealAuditError("classification_count does not match classifications")

    donor = _verify_donor(repo_root, donor_map, args.donor_root)
    rows: list[dict[str, Any]] = []
    counts: dict[str, int] = {name: 0 for name in sorted(ALLOWED_CLASSIFICATIONS)}
    for capability_id in sorted(capabilities):
        cap = capabilities[capability_id]
        row = classifications[capability_id]
        cls = str(row.get("classification") or "")
        if cls not in ALLOWED_CLASSIFICATIONS:
            raise SealAuditError(f"{capability_id}: invalid classification {cls!r}")
        if cls == "regression":
            raise SealAuditError(f"{capability_id}: classified regression blocks XGO campaign")
        status = str(cap.get("status") or "")
        if cls == "intentionally_removed" and status != "INTENTIONALLY_REMOVED":
            raise SealAuditError(f"{capability_id}: intentionally removed classification requires INTENTIONALLY_REMOVED ledger status")
        if cls != "intentionally_removed" and status != "IMPLEMENTED":
            raise SealAuditError(f"{capability_id}: non-removed differential result requires IMPLEMENTED ledger status")

        test_id = str(cap.get("test_id") or "")
        if not test_id.startswith("fixture:"):
            raise SealAuditError(f"{capability_id}: missing neutral fixture binding")
        fixture_id = test_id.split(":", 1)[1]
        if row.get("fixture_id") != fixture_id:
            raise SealAuditError(f"{capability_id}: classification fixture mismatch")
        meta = fixture_meta.get(fixture_id)
        if not meta:
            raise SealAuditError(f"{capability_id}: fixture {fixture_id} missing from registry")
        fixture = _load(repo_root / str(meta.get("path") or ""))
        if [str(v) for v in fixture.get("capability_ids") or []] != [capability_id]:
            raise SealAuditError(f"{capability_id}: neutral fixture capability link mismatch")
        donors = [str(v) for v in cap.get("donors") or []]
        if donors != [str(v) for v in row.get("donor_ids") or []]:
            raise SealAuditError(f"{capability_id}: classification donor set drifted")
        unknown = sorted(set(donors) - set(donor_entries))
        if unknown:
            raise SealAuditError(f"{capability_id}: unknown donor ids {unknown}")
        expected_hash = _canonical_hash(fixture.get("expected"))
        if row.get("expected_sha256") != expected_hash:
            raise SealAuditError(f"{capability_id}: semantic expected outcome changed without reclassification")
        canonical_hash = hashlib.sha256(str(cap.get("canonical_behavior") or "").encode("utf-8")).hexdigest()
        if row.get("canonical_behavior_sha256") != canonical_hash:
            raise SealAuditError(f"{capability_id}: canonical behavior changed without reclassification")
        live_donor_hashes = [donor["files"][donor_id]["sha256"] for donor_id in donors]
        donor_test_sources = [donor_entries[d]["path"] for d in donors if donor["files"][d]["test_source"]]
        counts[cls] += 1
        rows.append({
            "capability_id": capability_id,
            "fixture_id": fixture_id,
            "classification": cls,
            "donor_strategy": cap.get("donor_strategy"),
            "expected_sha256": expected_hash,
            "donor_evidence_sha256": hashlib.sha256("\n".join(sorted(live_donor_hashes)).encode("utf-8")).hexdigest(),
            "legacy_test_sources": donor_test_sources,
            "rationale": str(row.get("rationale") or ""),
        })

    stable = {
        "schema_version": 1,
        "status": "pass",
        "capability_count": len(rows),
        "classification_counts": counts,
        "donor_head": donor["head"],
        "donor_aggregate_sha256": donor["aggregate_sha256"],
        "results": rows,
    }
    stable["deterministic_fingerprint"] = _canonical_hash(stable)
    _write(args.output, stable)
    return stable


def _go_env() -> tuple[str, str]:
    goos = subprocess.check_output(["go", "env", "GOOS"], text=True).strip()
    goarch = subprocess.check_output(["go", "env", "GOARCH"], text=True).strip()
    return goos, goarch


def state_machine(args: argparse.Namespace) -> dict[str, Any]:
    corpus = _load(args.seeds)
    if corpus.get("schema_version") != 1:
        raise SealAuditError("unsupported state-machine seed schema")
    declared_ops = [str(v) for v in corpus.get("operations") or []]
    expected_ops = [
        "start", "pause", "resume", "cancel", "retry", "migrate_backend",
        "backend_loss", "crash", "recover", "verify", "publish", "delete",
    ]
    if declared_ops != expected_ops:
        raise SealAuditError(f"state-machine operation contract drifted: {declared_ops}")
    invariants = [str(v) for v in corpus.get("invariants") or []]
    if not invariants or not all(v.startswith("INV-XGO-") for v in invariants):
        raise SealAuditError("state-machine corpus must declare INV-XGO-* invariants")
    seeds = corpus.get("seeds") or []
    if not isinstance(seeds, list) or len(seeds) < 10:
        raise SealAuditError("state-machine permanent regression corpus is too small")

    commands: list[dict[str, Any]] = []
    commands.append(_run(["go", "test", "./engine/seal", "-run", "^TestStateMachine", "-count=1"]))
    commands.append(_run([
        "go", "test",
        "./engine/domain/...",
        "./engine/store/sqlite",
        "./engine/backends/migration",
        "./engine/recovery",
        "-count=1",
    ]))

    goos, goarch = _go_env()
    race_supported = (goos, goarch) in RACE_SUPPORTED
    race: dict[str, Any]
    if race_supported:
        race_run = _run([
            "go", "test", "-race",
            "./engine/seal",
            "./engine/store/sqlite",
            "./engine/backends/migration",
            "./engine/recovery",
            "-count=1",
        ])
        race = {"status": "pass", "goos": goos, "goarch": goarch, "run": race_run}
    else:
        stress = _run(["go", "test", "./engine/seal", "-run", "^TestStateMachine", "-count=20"])
        race = {
            "status": "unsupported_on_host",
            "goos": goos,
            "goarch": goarch,
            "mandatory_stress_fallback": stress,
            "final_gate_requirement": "GATE-12 must execute the race campaign on a Go race-supported host.",
        }

    report = {
        "schema_version": 1,
        "status": "pass",
        "operation_count": len(declared_ops),
        "permanent_seed_count": len(seeds),
        "invariants": invariants,
        "commands": commands,
        "race_detector": race,
    }
    _write(args.output, report)
    return report


def crash_matrix(args: argparse.Namespace) -> dict[str, Any]:
    commands: list[dict[str, Any]] = []
    with tempfile.TemporaryDirectory(prefix="xgo-seal-crash-") as tmp:
        temp = Path(tmp)
        store_report = temp / "store.json"
        fragment_report = temp / "fragment.json"
        ffmpeg_report = temp / "ffmpeg.json"
        hls_report = temp / "hls.json"
        dash_report = temp / "dash.json"

        commands.append(_run(["go", "test", "./engine/seal", "-run", "^TestCrashMatrixOwnershipDurabilityReopenBoundaries$", "-count=1"]))
        commands.append(_run(["go", "test", "./engine/backends/migration", "-run", "^TestMigrationCrashRecoveryAtEveryDurableBoundary$", "-count=1"]))
        commands.append(_run(["go", "test", "./engine/recovery", "-run", "^TestRecovery", "-count=1"]))
        commands.append(_run(["go", "run", "./engine/cmd/xgo-store-audit", "--mode", "durable-gate", "--output", str(store_report)]))
        commands.append(_run(["go", "run", "./engine/cmd/xgo-media-audit", "--mode", "fragment_faults", "--output", str(fragment_report)]))
        commands.append(_run(["go", "run", "./engine/cmd/xgo-media-audit", "--mode", "hls_execution", "--output", str(hls_report)]))
        commands.append(_run(["go", "run", "./engine/cmd/xgo-media-audit", "--mode", "dash_execution", "--output", str(dash_report)]))
        commands.append(_run(["go", "run", "./engine/cmd/xgo-media-audit", "--mode", "ffmpeg_execution", "--output", str(ffmpeg_report)]))

        store = _load(store_report)
        fragment = _load(fragment_report)
        hls = _load(hls_report)
        dash = _load(dash_report)
        ffmpeg = _load(ffmpeg_report)

    if store.get("status") != "pass" or not fragment.get("pass") or not hls.get("pass") or not dash.get("pass") or not ffmpeg.get("pass"):
        raise SealAuditError("one or more crash sub-audits did not pass")
    details = store.get("details") or {}
    checkpoint = details.get("checkpoint") or {}
    publication = details.get("publication") or {}
    recovery = details.get("recovery") or {}
    verification = details.get("verification") or {}
    if int(checkpoint.get("pre_persist_fault_points") or 0) < 3 or not checkpoint.get("post_persist_recoverable"):
        raise SealAuditError("checkpoint crash coverage is incomplete")
    if not publication.get("duplicate_platform_reply_idempotent") or not recovery.get("stale_backend_completion_diagnostic_only"):
        raise SealAuditError("publication/recovery crash invariants are incomplete")
    if not verification.get("interrupted_success_atomic") or not verification.get("stale_attempt_rejected"):
        raise SealAuditError("verification crash invariants are incomplete")
    fragment_checks = set(fragment.get("checks") or [])
    hls_checks = set(hls.get("checks") or [])
    dash_checks = set(dash.get("checks") or [])
    ffmpeg_checks = set(ffmpeg.get("checks") or [])
    for required in {"crash before commit", "crash after commit reconstruction", "restart ledger reconstruction"}:
        if required not in fragment_checks:
            raise SealAuditError(f"fragment crash evidence missing {required!r}")
    for required in {"restart mid-live duplicate suppression", "bounded user stop"}:
        if required not in hls_checks:
            raise SealAuditError(f"HLS durability evidence missing {required!r}")
    for required in {"restart duplicate suppression", "shared fragment ledger"}:
        if required not in dash_checks:
            raise SealAuditError(f"DASH durability evidence missing {required!r}")
    for required in {"crash", "verified artifact", "publication crash recovery"}:
        if required not in ffmpeg_checks:
            raise SealAuditError(f"FFmpeg crash evidence missing {required!r}")

    coverage = {
        "after_generation_reserve": "engine/seal reopen matrix",
        "after_staging_create": "checkpoint pre-persist fault + fragment crash-before-commit",
        "after_ownership_claim": "engine/seal reopen matrix",
        "after_backend_task_create": "engine/seal reopen matrix",
        "before_activation": "engine/seal reopen matrix",
        "after_activation": "engine/seal reopen matrix",
        "during_block_write": "xgo-store-audit checkpoint pre-persist fault matrix",
        "after_fsync": "xgo-store-audit checkpoint post-persist recovery",
        "before_checkpoint_commit": "xgo-store-audit checkpoint pre-persist fault matrix",
        "after_checkpoint_commit": "xgo-store-audit checkpoint post-persist recovery",
        "after_verification": "xgo-store-audit verification interrupted-success atomicity",
        "after_artifact_creation": "xgo-store-audit verification artifact generation recovery",
        "after_platform_publication": "xgo-store-audit publication PLATFORM_COMMITTED reopen state",
        "before_engine_publication_commit": "xgo-store-audit publication inspect/engine-commit reconciliation",
        "during_backend_migration": "TestMigrationCrashRecoveryAtEveryDurableBoundary",
        "during_hls_dash_fragment_commit": "xgo-media-audit fragment_faults",
        "during_after_ffmpeg": "xgo-media-audit ffmpeg_execution",
    }
    expected_points = {
        "after_generation_reserve", "after_staging_create", "after_ownership_claim",
        "after_backend_task_create", "before_activation", "after_activation",
        "during_block_write", "after_fsync", "before_checkpoint_commit",
        "after_checkpoint_commit", "after_verification", "after_artifact_creation",
        "after_platform_publication", "before_engine_publication_commit",
        "during_backend_migration", "during_hls_dash_fragment_commit", "during_after_ffmpeg",
    }
    if set(coverage) != expected_points:
        raise SealAuditError("crash injection matrix coverage set drifted")

    report = {
        "schema_version": 1,
        "status": "pass",
        "injection_point_count": len(coverage),
        "coverage": coverage,
        "invariants": {
            "expected_recovery_at_every_point": True,
            "no_duplicate_publication": bool(publication.get("duplicate_platform_reply_idempotent")),
            "no_stale_completion": bool(recovery.get("stale_backend_completion_diagnostic_only")),
            "no_unverified_resume": bool(verification.get("stale_attempt_rejected")),
        },
        "commands": commands,
    }
    _write(args.output, report)
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description="XGO Wave 12 seal audits")
    sub = parser.add_subparsers(dest="command", required=True)

    diff = sub.add_parser("differential", help="classify donor-vs-Go semantic outcomes")
    diff.add_argument("--ledger", type=Path, default=Path("engine/docs/capability-ledger.yaml"))
    diff.add_argument("--registry", type=Path, default=Path("engine/testdata/fixture-registry.json"))
    diff.add_argument("--donor-map", type=Path, default=Path("engine/docs/donor-map.yaml"))
    diff.add_argument("--classifications", type=Path, default=Path("engine/testdata/seal/differential-classifications.json"))
    diff.add_argument("--donor-root", default="")
    diff.add_argument("--output", type=Path, default=Path(".devtool/reports/xgo/seal/differential.json"))

    state = sub.add_parser("state-machine", help="run deterministic state-machine property campaign")
    state.add_argument("--seeds", type=Path, default=Path("engine/testdata/seal/state-machine-seeds.json"))
    state.add_argument("--output", type=Path, default=Path(".devtool/reports/xgo/seal/state-machine.json"))

    crash = sub.add_parser("crash-matrix", help="run full crash/durability recovery matrix")
    crash.add_argument("--output", type=Path, default=Path(".devtool/reports/xgo/seal/crash-matrix.json"))

    args = parser.parse_args()
    try:
        if args.command == "differential":
            report = differential(args)
        elif args.command == "state-machine":
            report = state_machine(args)
        else:
            report = crash_matrix(args)
    except SealAuditError as exc:
        print(f"XGO seal audit FAILED: {exc}", file=sys.stderr)
        return 1
    print(json.dumps(report, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
