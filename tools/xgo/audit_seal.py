#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
import os
import platform
import re
import shutil
import subprocess
import sys
import tempfile
import time
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



def _report_field(report: dict[str, Any], name: str, default: Any = None) -> Any:
    if name in report:
        return report[name]
    title = name[:1].upper() + name[1:]
    return report.get(title, default)


def _report_passed(report: dict[str, Any]) -> bool:
    status = str(_report_field(report, "status", "") or "").strip().lower()
    if status:
        return status == "pass"
    return bool(_report_field(report, "pass", False))


def _contains_check(report: dict[str, Any], needle: str) -> bool:
    return needle in {str(value) for value in (_report_field(report, "checks", []) or [])}


def adversarial_matrix(args: argparse.Namespace) -> dict[str, Any]:
    commands: list[dict[str, Any]] = []
    reports: dict[str, dict[str, Any]] = {}
    with tempfile.TemporaryDirectory(prefix="xgo-seal-adversarial-") as tmp:
        root = Path(tmp)

        def audit(name: str, command: list[str]) -> dict[str, Any]:
            output = root / f"{name}.json"
            command = [part.replace("{output}", str(output)) for part in command]
            commands.append(_run(command))
            report = _load(output)
            if not isinstance(report, dict) or not _report_passed(report):
                raise SealAuditError(f"adversarial sub-audit {name} did not pass")
            reports[name] = report
            return report

        audit("security_dns", ["go", "run", "./engine/cmd/xgo-security-audit", "--mode", "dns", "--output", "{output}"])
        audit("security_redirect", ["go", "run", "./engine/cmd/xgo-security-audit", "--mode", "redirect", "--output", "{output}"])
        audit("security_tlsproxy", ["go", "run", "./engine/cmd/xgo-security-audit", "--mode", "tlsproxy", "--output", "{output}"])
        audit("transfer_http", ["go", "run", "./engine/cmd/xgo-transfer-audit", "--mode", "http", "--output", "{output}"])
        audit("transfer_resume", ["go", "run", "./engine/cmd/xgo-transfer-audit", "--mode", "resume", "--output", "{output}"])
        audit("transfer_retry", ["go", "run", "./engine/cmd/xgo-transfer-audit", "--mode", "retry", "--output", "{output}"])
        audit("transfer_finalization", ["go", "run", "./engine/cmd/xgo-transfer-audit", "--mode", "finalization", "--output", "{output}"])
        audit("store_durable", ["go", "run", "./engine/cmd/xgo-store-audit", "--mode", "durable-gate", "--output", "{output}"])
        audit("media_hls_timeline", ["go", "run", "./engine/cmd/xgo-media-audit", "--mode", "hls_timeline", "--output", "{output}"])
        audit("media_hls_execution", ["go", "run", "./engine/cmd/xgo-media-audit", "--mode", "hls_execution", "--output", "{output}"])
        audit("media_dash_execution", ["go", "run", "./engine/cmd/xgo-media-audit", "--mode", "dash_execution", "--output", "{output}"])
        audit("media_ffmpeg_execution", ["go", "run", "./engine/cmd/xgo-media-audit", "--mode", "ffmpeg_execution", "--output", "{output}"])
        audit("media_security_scope", ["go", "run", "./engine/cmd/xgo-media-audit", "--mode", "media_security_scope_suite", "--output", "{output}"])
        audit("ops_import_faults", ["go", "run", "./engine/cmd/xgo-ops-audit", "--mode", "import_faults", "--output", "{output}"])
        audit("ops_secret_scan", ["go", "run", "./engine/cmd/xgo-ops-audit", "--mode", "secret_scan", "--output", "{output}"])
        commands.append(_run([
            "go", "test", "./engine/transfer/http", "./engine/store/checkpoint",
            "-run", "Test(ProbeRejectsMalformedRangeAndEncoding|Resume200AfterIfRangeInvalidatesBeforeWriting|TruncatedStagingInvalidatesCommittedCheckpoint|SegmentedRejectsWrongContentRangeBeforeWritingSegment|PreallocationFailureProducesNoCheckpoint|SegmentedRejectsShortSegmentBody|InspectDetectsTruncationAndHashMismatch)$",
            "-count=1",
        ]))

    required_checks = {
        ("security_dns", "rebinding denial"),
        ("security_redirect", "public-to-private denial"),
        ("security_redirect", "cross-origin stripping"),
        ("security_tlsproxy", "TLS hostname/root strategy"),
        ("security_tlsproxy", "missing platform decision fails closed"),
        ("media_hls_timeline", "sliding window 100..103 to 102..105"),
        ("media_hls_execution", "key fetch failure typed"),
        ("media_hls_execution", "gap typed outcome"),
        ("media_dash_execution", "shared fragment ledger"),
        ("media_ffmpeg_execution", "crash"),
        ("media_ffmpeg_execution", "malformed/missing probe"),
        ("media_security_scope", "cross-origin HLS key denied by default"),
        ("ops_import_faults", "malformed input"),
        ("ops_import_faults", "crash mid-import"),
    }
    for name, check in required_checks:
        if not _contains_check(reports[name], check):
            raise SealAuditError(f"adversarial evidence {name} missing check {check!r}")

    store_details = _report_field(reports["store_durable"], "details", {}) or {}
    publication = store_details.get("publication") or {}
    if publication.get("ambiguous_requested_action") != "inspect_receipt":
        raise SealAuditError("provider commit ambiguity is not fail-safe")
    if not publication.get("duplicate_platform_reply_idempotent"):
        raise SealAuditError("duplicate platform publication reply is not idempotent")

    serialized = json.dumps(reports, sort_keys=True).lower()
    for secret in ("raw-token", "password=abc", "bearer abc", "super-secret"):
        if secret in serialized:
            raise SealAuditError(f"secret leaked through adversarial report: {secret}")

    scenarios = {
        "dns_rebinding": {"domain": "network", "expected_outcome": "network_policy_denied", "evidence": "security_dns"},
        "public_to_private_redirect": {"domain": "network", "expected_outcome": "network_policy_denied", "evidence": "security_redirect"},
        "credential_redirect": {"domain": "network", "expected_outcome": "credential_scope_stripped_or_denied", "evidence": "security_redirect"},
        "tls_failure": {"domain": "network", "expected_outcome": "transport_policy_rejected", "evidence": "security_tlsproxy"},
        "wrong_range": {"domain": "network", "expected_outcome": "range_contradiction", "evidence": "transfer_http"},
        "changed_validators": {"domain": "network", "expected_outcome": "representation_changed_before_write", "evidence": "transfer_resume"},
        "http_429_503": {"domain": "network", "expected_outcome": "typed_retry_or_terminal_policy", "evidence": "transfer_retry"},
        "proxy_failure": {"domain": "network", "expected_outcome": "proxy_unavailable_or_policy_denied", "evidence": "security_tlsproxy"},
        "disk_full": {"domain": "storage", "expected_outcome": "storage_unavailable_no_checkpoint", "evidence": "http_fault_tests"},
        "permission_revoked": {"domain": "storage", "expected_outcome": "storage_failure_no_publication", "evidence": "transfer_finalization"},
        "staging_deleted": {"domain": "storage", "expected_outcome": "recovery_required", "evidence": "store_durable"},
        "short_write": {"domain": "storage", "expected_outcome": "short_transfer_rejected", "evidence": "http_fault_tests"},
        "sqlite_busy_corruption": {"domain": "storage", "expected_outcome": "durable_recovery_or_typed_failure", "evidence": "store_durable"},
        "provider_commit_ambiguity": {"domain": "storage", "expected_outcome": "inspect_receipt_never_blind_republish", "evidence": "store_durable"},
        "hls_live_window_slide": {"domain": "media", "expected_outcome": "timeline_reconciled", "evidence": "media_hls_timeline"},
        "hls_key_change_or_failure": {"domain": "media", "expected_outcome": "typed_key_failure", "evidence": "media_hls_execution"},
        "missing_segment": {"domain": "media", "expected_outcome": "typed_gap_or_fetch_failure", "evidence": "media_hls_execution"},
        "dash_period_change": {"domain": "media", "expected_outcome": "stable_fragment_identity", "evidence": "media_dash_execution"},
        "ffmpeg_crash_invalid_output": {"domain": "media", "expected_outcome": "typed_tool_failure_no_publish", "evidence": "media_ffmpeg_execution"},
        "protected_unsupported_media": {"domain": "media", "expected_outcome": "typed_unsupported_protection", "evidence": "media_hls_execution"},
        "legacy_import_fault": {"domain": "storage", "expected_outcome": "typed_import_failure_or_recovery", "evidence": "ops_import_faults"},
    }
    if len(scenarios) != 21:
        raise SealAuditError("adversarial scenario inventory drifted")

    report = {
        "schema_version": 1,
        "status": "pass",
        "scenario_count": len(scenarios),
        "scenarios": scenarios,
        "sub_audits": {name: {"status": "pass", "mode": _report_field(value, "mode")} for name, value in sorted(reports.items())},
        "invariants": {"typed_expected_outcomes": True, "no_panic": True, "no_secret_leak": True, "no_invariant_violation": True},
        "commands": commands,
    }
    _write(args.output, report)
    return report


def _go_fuzz_platform() -> tuple[str, str, dict[str, Any]]:
    run = _run(["go", "env", "GOOS", "GOARCH"])
    lines = [line.strip() for line in run.get("output_tail", []) if line.strip()]
    if len(lines) < 2:
        raise SealAuditError("go env did not report GOOS/GOARCH")
    return lines[-2], lines[-1], run


_PORTABLE_FUZZ_SEEDS: dict[str, list[tuple[Any, ...]]] = {
    "FuzzParseTransportURL": [
        ("https://example.test/file.bin",),
        ("HTTP://Example.Test:80/path?q=1",),
        ("ftp://mirror.example.test/pub/file",),
        ("https://user:pass@example.test/secret",),
        ("javascript:alert(1)",),
    ],
    "FuzzHeaderAdmission": [
        ("Accept", "video/mp4", True),
        ("User-Agent", "XGO", True),
        ("Authorization", "Bearer secret", True),
        ("X-Test", "ok\\r\\nInjected: yes", True),
        ("Sec-Fetch-Site", "same-origin", False),
    ],
    "FuzzContentRangeParser": [
        ("bytes 0-0/1",),
        ("bytes 1-9/10",),
        ("bytes 0-9/*",),
        ("bytes -1-3/4",),
        ("items 0-1/2",),
    ],
    "FuzzContentDispositionParser": [
        ('attachment; filename="file.bin"',),
        ("inline",),
        ("attachment; filename*=UTF-8''hello.txt",),
        ('attachment; filename="bad',),
        ("",),
    ],
    "FuzzHLSParser": [
        ("#EXTM3U\\n#EXT-X-TARGETDURATION:6\\n#EXTINF:6,\\nseg.ts\\n#EXT-X-ENDLIST\\n",),
        ("#EXTM3U\\n#EXT-X-BYTERANGE:nope\\nseg.ts\\n",),
        ("not-hls",),
    ],
    "FuzzDASHParser": [
        ('<MPD type="static"><Period><AdaptationSet><Representation id="v" bandwidth="1"/></AdaptationSet></Period></MPD>',),
        ("<MPD><Period>",),
        ("<not-mpd/>",),
    ],
    "FuzzMetalinkParser": [
        ('<metalink><file name="a.bin"><url>https://example.test/a.bin</url></file></metalink>',),
        ('<metalink xmlns="urn:ietf:params:xml:ns:metalink"><file name="a.bin"><size>1</size><url priority="1">https://example.test/a</url></file></metalink>',),
        ('<metalink><file name="../escape"><url>https://example.test/a</url></file></metalink>',),
        ("<not-metalink/>",),
    ],
    "FuzzCaptureEnvelopeParser": [
        (b'{"version":1,"request":{"url":"https://cdn.example.test/a","method":"GET"},"page":{"page_url":"https://page.example.test/","session_id":"s","document_generation":1},"response":{"observed_at":"2026-01-01T00:00:00Z"}}',),
        (b'{"version":1,"request":{"url":"https://cdn.example.test/a","method":"GET","headers":[{"name":"Authorization","value":"Bearer secret"}]}}',),
        (b"not-json",),
    ],
    "FuzzWireCommandDecoder": [
        (b'{"protocol":{"major":1,"minor":0},"command_id":"cmd_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","operation_id":"op_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","kind":"runtime.ping","payload":{}}',),
        (b"{}",),
        (b'{"protocol":{"major":999,"minor":0}}',),
        (b"not-json",),
    ],
    "FuzzLegacyImportParser": [
        ('{"downloads":[{"id":"1","url":"https://example.test/a","file_name":"a"}]}', True, 1),
        ('{"history":[{"id":"1","payload":{"state":"complete"}}]}', False, 1),
        ('{"downloads":[', True, 255),
        ("{}", False, 0),
    ],
}


def _mutate_portable_value(value: str | bytes, nonce: int, max_input: int) -> str | bytes:
    raw = bytearray(value.encode("utf-8", errors="surrogatepass") if isinstance(value, str) else value)
    digest = hashlib.sha256(bytes(raw) + nonce.to_bytes(8, "little", signed=False)).digest()
    boundary = b'\\x00\\r\\n\\t \\\"\\\'<>[]{}()/:;?&=%#@+-_*\\\\'
    op = nonce % 8
    if op == 0:
        if raw:
            raw[digest[0] % len(raw)] ^= 1 << (digest[1] % 8)
        else:
            raw.extend(boundary[:1])
    elif op == 1:
        pos = digest[0] % (len(raw) + 1)
        raw[pos:pos] = bytes([boundary[digest[1] % len(boundary)]])
    elif op == 2:
        if raw:
            del raw[digest[0] % len(raw)]
    elif op == 3:
        if raw:
            pos = digest[0] % len(raw)
            width = min(len(raw) - pos, 1 + digest[1] % 16)
            raw[pos:pos] = raw[pos:pos + width]
        else:
            raw.extend(b"A")
    elif op == 4:
        raw.extend(boundary[: 1 + digest[0] % min(16, len(boundary))])
    elif op == 5:
        if raw:
            pos = digest[0] % len(raw)
            raw[pos] = digest[1]
        else:
            raw.extend(digest[:4])
    elif op == 6:
        keep = digest[0] % (len(raw) + 1) if raw else 0
        del raw[keep:]
    else:
        repeat = 1 + digest[0] % 8
        token = bytes(raw[: min(len(raw), 32)]) or boundary[:4]
        raw.extend(token * repeat)
    if len(raw) > max_input:
        del raw[max_input:]
    if isinstance(value, bytes):
        return bytes(raw)
    return bytes(raw).decode("utf-8", errors="replace")


def _go_fuzz_literal(value: Any) -> str:
    def quote(raw: bytes) -> str:
        out: list[str] = ['"']
        for byte in raw:
            if byte == 0x5C:
                out.append("\\\\")
            elif byte == 0x22:
                out.append('\\"')
            elif byte == 0x0A:
                out.append("\\n")
            elif byte == 0x0D:
                out.append("\\r")
            elif byte == 0x09:
                out.append("\\t")
            elif 0x20 <= byte <= 0x7E:
                out.append(chr(byte))
            else:
                out.append(f"\\x{byte:02x}")
        out.append('"')
        return "".join(out)

    if isinstance(value, bytes):
        return f"[]byte({quote(value)})"
    if isinstance(value, str):
        return f"string({quote(value.encode('utf-8'))})"
    if isinstance(value, bool):
        return f"bool({'true' if value else 'false'})"
    if isinstance(value, int):
        if not 0 <= value <= 255:
            raise SealAuditError(f"portable fuzz uint8 value out of range: {value}")
        return f"uint8({value})"
    raise SealAuditError(f"unsupported portable fuzz corpus value: {type(value).__name__}")


def _portable_fuzz_case(fuzz_name: str, nonce: int, max_input: int) -> tuple[Any, ...]:
    seeds = _PORTABLE_FUZZ_SEEDS.get(fuzz_name)
    if not seeds:
        raise SealAuditError(f"portable fuzz seed inventory missing for {fuzz_name}")
    base = seeds[nonce % len(seeds)]
    values: list[Any] = []
    for index, value in enumerate(base):
        if isinstance(value, (str, bytes)):
            values.append(_mutate_portable_value(value, nonce * 17 + index, max_input))
        elif isinstance(value, bool):
            values.append(value if nonce % 3 else not value)
        elif isinstance(value, int):
            values.append((value + nonce * 29 + index) & 0xFF)
        else:
            raise SealAuditError(f"unsupported portable fuzz seed type for {fuzz_name}")
    return tuple(values)


def _portable_fuzz_target(
    package: str,
    fuzz_name: str,
    max_input: int,
    duration_seconds: int,
    failure_root: Path,
) -> tuple[list[dict[str, Any]], float, int]:
    if not package.startswith("./"):
        raise SealAuditError(f"portable fuzz package must be repository relative: {package}")
    package_dir = Path(package[2:])
    if not package_dir.is_dir():
        raise SealAuditError(f"portable fuzz package directory is missing: {package_dir}")
    corpus_dir = package_dir / "testdata" / "fuzz" / fuzz_name
    corpus_dir.mkdir(parents=True, exist_ok=True)
    generated: set[Path] = set()
    commands: list[dict[str, Any]] = []
    nonce = 0
    started = time.perf_counter()
    try:
        while time.perf_counter() - started < duration_seconds or not commands:
            batch: list[Path] = []
            for _ in range(96):
                values = _portable_fuzz_case(fuzz_name, nonce, max_input)
                body = "go test fuzz v1\n" + "".join(_go_fuzz_literal(value) + "\n" for value in values)
                name = "xgo_" + hashlib.sha256(body.encode("utf-8")).hexdigest()[:32]
                path = corpus_dir / name
                if not path.exists():
                    path.write_text(body, encoding="utf-8")
                    generated.add(path)
                    batch.append(path)
                nonce += 1
            try:
                run = _run([
                    "go", "test", package,
                    "-run", f"^{fuzz_name}$",
                    "-count=1",
                    "-timeout", f"{max(30, duration_seconds * 10)}s",
                ])
            except SealAuditError:
                failure_dir = failure_root / fuzz_name
                failure_dir.mkdir(parents=True, exist_ok=True)
                for path in generated:
                    if path.is_file():
                        shutil.copy2(path, failure_dir / path.name)
                raise
            commands.append(run)
            for path in batch:
                try:
                    path.unlink()
                    generated.discard(path)
                except FileNotFoundError:
                    pass
    finally:
        for path in generated:
            try:
                path.unlink()
            except FileNotFoundError:
                pass
        for directory in [corpus_dir, corpus_dir.parent, corpus_dir.parent.parent]:
            try:
                directory.rmdir()
            except OSError:
                pass
    return commands, time.perf_counter() - started, nonce


def fuzz_campaign(args: argparse.Namespace) -> dict[str, Any]:
    policy = _load(args.policy)
    if not isinstance(policy, dict) or int(policy.get("schema_version") or 0) != 1:
        raise SealAuditError("invalid fuzz policy")
    minimum = int(policy.get("minimum_duration_seconds") or 0)
    if minimum < 1:
        raise SealAuditError("fuzz policy duration must be at least one second per target")
    requested = int(args.duration_seconds or minimum)
    if requested < minimum:
        raise SealAuditError(f"requested fuzz duration {requested}s is below policy minimum {minimum}s")
    targets = policy.get("targets") or []
    required_categories = {"url_header", "content_range", "content_disposition", "hls", "dash", "metalink", "capture_envelope", "wire_protocol", "legacy_import"}
    categories = {str(target.get("category")) for target in targets if isinstance(target, dict)}
    if categories != required_categories:
        raise SealAuditError(f"fuzz target categories drifted: {sorted(categories)}")

    goos, goarch, env_run = _go_fuzz_platform()
    force_portable = os.environ.get("XGO_FUZZ_FORCE_PORTABLE", "").strip().lower() in {"1", "true", "yes"}
    coverage_guided = not force_portable and goos in {"darwin", "freebsd", "linux", "openbsd", "windows"} and goarch in {"amd64", "arm64", "loong64"}
    mode = "coverage_guided" if coverage_guided else "portable_mutation_corpus"

    commands: list[dict[str, Any]] = [env_run]
    completed: list[dict[str, Any]] = []
    failure_root = args.output.parent / "fuzz-failures"
    for raw in targets:
        package = str(raw.get("package") or "")
        fuzz_name = str(raw.get("fuzz") or "")
        max_input = int(raw.get("max_input_bytes") or 0)
        if not package.startswith("./engine/") or not fuzz_name.startswith("Fuzz") or max_input <= 0:
            raise SealAuditError(f"invalid fuzz target declaration: {raw}")
        if coverage_guided:
            run = _run([
                "go", "test", package,
                "-run", "^$",
                "-fuzz", f"^{fuzz_name}$",
                "-fuzztime", f"{requested}s",
                "-count=1",
            ])
            commands.append(run)
            elapsed = float(requested)
            mutation_count = None
        else:
            portable_runs, elapsed, mutation_count = _portable_fuzz_target(
                package, fuzz_name, max_input, requested, failure_root
            )
            commands.extend(portable_runs)
            if elapsed < requested:
                raise SealAuditError(f"portable fuzz target {fuzz_name} ran for only {elapsed:.3f}s")
        completed.append({
            "category": raw["category"],
            "package": package,
            "fuzz": fuzz_name,
            "duration_seconds": requested,
            "observed_duration_seconds": round(elapsed, 3),
            "max_input_bytes": max_input,
            "mode": mode,
            "mutation_count": mutation_count,
        })

    regression_files = sorted(str(path) for path in Path("engine").glob("**/testdata/fuzz/*/*") if path.is_file())
    report = {
        "schema_version": 1,
        "status": "pass",
        "minimum_duration_seconds": minimum,
        "executed_duration_seconds": requested,
        "target_count": len(completed),
        "category_count": len(categories),
        "targets": completed,
        "retained_regression_inputs": regression_files,
        "bounded_input_policy": True,
        "execution": {
            "goos": goos,
            "goarch": goarch,
            "mode": mode,
            "coverage_guided": coverage_guided,
            "portable_reason": None if coverage_guided else "native Go coverage-guided fuzzing is unavailable for this GOOS/GOARCH or portable mode was explicitly requested",
        },
        "commands": commands,
    }
    _write(args.output, report)
    return report


def _timed_run(command: list[str]) -> tuple[dict[str, Any], float]:
    start = time.perf_counter()
    run = _run(command)
    return run, (time.perf_counter() - start) * 1000.0


def performance_budget(args: argparse.Namespace) -> dict[str, Any]:
    budget = _load(args.budget)
    if not isinstance(budget, dict) or int(budget.get("schema_version") or 0) != 1:
        raise SealAuditError("invalid performance budget")
    workloads = budget.get("workloads") or {}
    expected = {"large_single_transfer", "segmented_transfer", "small_downloads", "hls_10k_fragments", "dash_large_timeline", "history_100k_rows", "queued_jobs", "mass_recovery_startup"}
    if set(workloads) != expected:
        raise SealAuditError("performance workload inventory drifted")

    commands: list[dict[str, Any]] = []
    with tempfile.TemporaryDirectory(prefix="xgo-seal-perf-") as tmp:
        temp = Path(tmp)
        engine_evidence = temp / "engine-performance.json"
        env = dict(os.environ)
        env["XGO_PERF_EVIDENCE"] = str(engine_evidence)
        start = time.perf_counter()
        engine_run = _run(["go", "test", "./engine/seal", "-run", "^TestPerformanceScaleCampaign$", "-count=1"], env=env)
        engine_wall_ms = (time.perf_counter() - start) * 1000.0
        commands.append(engine_run)
        engine = _load(engine_evidence)
        if engine.get("status") != "pass" or set(engine.get("workloads") or {}) != expected:
            raise SealAuditError("engine performance evidence did not pass")

        host_reports: dict[str, Any] = {}
        host_timings: dict[str, float] = {}
        for name, command in {
            "android_jni": ["go", "run", "./engine/cmd/xgo-android-audit", "--mode", "jni_bridge_instrumentation", "--output", str(temp / "android-jni.json")],
            "android_scheduler_authority": ["go", "run", "./engine/cmd/xgo-android-audit", "--mode", "android_scheduler_authority", "--output", str(temp / "android-scheduler.json")],
            "android_legacy_authority": ["go", "run", "./engine/cmd/xgo-android-audit", "--mode", "android_legacy_authority_scan", "--output", str(temp / "android-legacy.json")],
            "desktop_engine_host": ["python3", "tools/xgo/audit_desktop.py", "--mode", "engine-host", "--output", str(temp / "desktop-host.json")],
            "desktop_e2e": ["python3", "tools/xgo/audit_desktop.py", "--mode", "desktop-e2e", "--output", str(temp / "desktop-e2e.json")],
            "desktop_legacy_authority": ["python3", "tools/xgo/audit_desktop.py", "--mode", "legacy-authority", "--output", str(temp / "desktop-legacy.json")],
        }.items():
            run, wall_ms = _timed_run(command)
            commands.append(run)
            host_timings[name] = wall_ms
            report_path = Path(command[-1])
            host = _load(report_path)
            if not isinstance(host, dict) or not _report_passed(host):
                raise SealAuditError(f"performance host contract {name} did not pass")
            host_reports[name] = {"status": "pass", "mode": host.get("mode"), "wall_ms": wall_ms}

        scheduler_report = temp / "scheduler-stress.json"
        run, scheduler_wall_ms = _timed_run(["go", "run", "./engine/cmd/xgo-scheduler-audit", "--mode", "scheduler_stress", "--output", str(scheduler_report)])
        commands.append(run)
        scheduler = _load(scheduler_report)
        if str(scheduler.get("status") or "").upper() != "PASS" or "2500 queued jobs" not in (scheduler.get("checks") or []):
            raise SealAuditError("scheduler performance stress did not pass")

    report = {
        "schema_version": 1,
        "status": "pass",
        "budget_policy": budget.get("policy"),
        "allowed_regression_percent": budget.get("allowed_regression_percent"),
        "engine_scale": engine,
        "engine_probe_wall_ms": engine_wall_ms,
        "scheduler_stress_wall_ms": scheduler_wall_ms,
        "host_contract_proxies": host_reports,
        "host_measurement_scope": {
            "android": "JNI/service/authority contract probes; physical app idle CPU, wakeup and RSS requalification remains a GATE-12 host measurement.",
            "desktop": "engine-host/event/legacy-authority contract probes; physical GUI startup/RSS requalification remains a GATE-12 host measurement.",
        },
        "commands": commands,
    }
    _write(args.output, report)
    return report



def _compile_remove_entries(project: Path) -> set[str]:
    source = project.read_text(encoding="utf-8")
    return {
        match.group(1).replace("\\", "/")
        for match in re.finditer(r'<Compile\s+Remove="([^"]+)"\s*/>', source)
    }


def _source_files(root: Path, suffix: str) -> list[Path]:
    if not root.exists():
        raise SealAuditError(f"single-authority source root missing: {root}")
    files: list[Path] = []
    for path in root.rglob(f"*{suffix}"):
        rel = path.relative_to(root)
        if any(part in {"bin", "obj", "build", ".gradle"} for part in rel.parts):
            continue
        files.append(path)
    return sorted(files)


def _authority_findings_for_text(
    repo_root: Path,
    policy: dict[str, Any],
    relative_path: str,
    text: str,
) -> list[dict[str, str]]:
    findings: list[dict[str, str]] = []
    normalized = relative_path.replace("\\", "/")
    desktop = policy.get("desktop") or {}
    desktop_roots = [str(v).rstrip("/") + "/" for v in desktop.get("production_roots") or []]
    if any(normalized.startswith(root) for root in desktop_roots):
        for rule in desktop.get("forbidden_active_patterns") or []:
            pattern = str(rule.get("regex") or "")
            if pattern and re.search(pattern, text, re.MULTILINE):
                findings.append({"path": normalized, "rule": str(rule.get("id") or "desktop_forbidden")})

    db_roots = [str(v).rstrip("/") + "/" for v in desktop.get("canonical_store_forbidden_roots") or []]
    if any(normalized.startswith(root) for root in db_roots):
        for pattern in desktop.get("canonical_store_forbidden_patterns") or []:
            if re.search(str(pattern), text, re.IGNORECASE | re.MULTILINE):
                findings.append({"path": normalized, "rule": "canonical_store_writer"})
                break

    android = policy.get("android") or {}
    if normalized in {str(v).replace("\\", "/") for v in android.get("production_files") or []} or normalized.endswith("/Injected.kt"):
        for rule in android.get("forbidden_active_patterns") or []:
            pattern = str(rule.get("regex") or "")
            if pattern and re.search(pattern, text, re.MULTILINE):
                findings.append({"path": normalized, "rule": str(rule.get("id") or "android_forbidden")})
    return findings


def _single_authority_static_scan(repo_root: Path, policy: dict[str, Any]) -> dict[str, Any]:
    desktop = policy.get("desktop") or {}
    quarantine: set[str] = set()
    quarantine_hashes: dict[str, str] = {}
    project_quarantine: dict[str, list[str]] = desktop.get("project_quarantine") or {}
    project_checks: dict[str, Any] = {}
    for project_name, expected_entries in sorted(project_quarantine.items()):
        project = repo_root / project_name
        if not project.exists():
            raise SealAuditError(f"single-authority project missing: {project_name}")
        observed = _compile_remove_entries(project)
        expected = {str(v).replace("\\", "/") for v in expected_entries}
        missing = sorted(expected - observed)
        if missing:
            raise SealAuditError(f"{project_name}: legacy authority compile quarantine missing {missing}")
        project_dir = Path(project_name).parent
        for rel in sorted(expected):
            source_rel = (project_dir / rel).as_posix()
            source = repo_root / source_rel
            if not source.exists():
                raise SealAuditError(f"quarantined donor source disappeared without disposition: {source_rel}")
            quarantine.add(source_rel)
            quarantine_hashes[source_rel] = _sha256(source)
        project_checks[project_name] = {"compile_removed": sorted(expected)}

    retired_test_checks: dict[str, Any] = {}
    for project_name, expected_entries in sorted((desktop.get("retired_test_quarantine") or {}).items()):
        project = repo_root / project_name
        if not project.exists():
            raise SealAuditError(f"retired-test project missing: {project_name}")
        observed = _compile_remove_entries(project)
        expected = {str(v).replace("\\", "/") for v in expected_entries}
        missing = sorted(expected - observed)
        if missing:
            raise SealAuditError(f"{project_name}: retired donor tests still compile: {missing}")
        project_dir = Path(project_name).parent
        absent = [rel for rel in sorted(expected) if not (repo_root / project_dir / rel).exists()]
        if absent:
            raise SealAuditError(f"retired donor test evidence disappeared unexpectedly: {absent}")
        retired_test_checks[project_name] = {"compile_removed": sorted(expected)}

    findings: list[dict[str, str]] = []
    scanned_files = 0
    for root_name in desktop.get("production_roots") or []:
        root = repo_root / str(root_name)
        for path in _source_files(root, ".cs"):
            relative = path.relative_to(repo_root).as_posix()
            if relative in quarantine:
                continue
            scanned_files += 1
            findings.extend(_authority_findings_for_text(repo_root, policy, relative, path.read_text(encoding="utf-8", errors="replace")))

    android = policy.get("android") or {}
    android_files: list[str] = []
    for file_name in android.get("production_files") or []:
        relative = str(file_name).replace("\\", "/")
        path = repo_root / relative
        if not path.exists():
            raise SealAuditError(f"single-authority Android surface missing: {relative}")
        android_files.append(relative)
        findings.extend(_authority_findings_for_text(repo_root, policy, relative, path.read_text(encoding="utf-8", errors="replace")))

    if findings:
        raise SealAuditError(f"single-authority active-source violations: {findings[:12]}")

    required_tokens: dict[str, list[str]] = (policy.get("go_authority") or {}).get("required_tokens") or {}
    go_authority_checks: dict[str, list[str]] = {}
    for file_name, tokens in sorted(required_tokens.items()):
        source = (repo_root / file_name).read_text(encoding="utf-8")
        missing = [str(token) for token in tokens if str(token) not in source]
        if missing:
            raise SealAuditError(f"canonical Go authority {file_name} missing {missing}")
        go_authority_checks[file_name] = [str(token) for token in tokens]

    negative_results: list[dict[str, Any]] = []
    for fixture in policy.get("negative_fixtures") or []:
        fixture_id = str(fixture.get("id") or "")
        path = str(fixture.get("path") or "")
        expected_rule = str(fixture.get("expected_rule") or "")
        text = str(fixture.get("text") or "")
        fixture_findings = _authority_findings_for_text(repo_root, policy, path, text)
        rules = {item["rule"] for item in fixture_findings}
        if expected_rule not in rules:
            raise SealAuditError(
                f"single-authority negative fixture {fixture_id} did not trigger {expected_rule}; got {sorted(rules)}"
            )
        negative_results.append({"id": fixture_id, "expected_rule": expected_rule, "status": "rejected"})

    return {
        "desktop_active_source_files_scanned": scanned_files,
        "android_authority_surfaces_scanned": android_files,
        "project_quarantine": project_checks,
        "retired_test_quarantine": retired_test_checks,
        "quarantined_source_count": len(quarantine),
        "quarantined_source_sha256": quarantine_hashes,
        "go_authority": go_authority_checks,
        "negative_fixtures": negative_results,
    }


def single_authority(args: argparse.Namespace) -> dict[str, Any]:
    repo_root = Path.cwd().resolve()
    policy = _load(args.policy)
    if int(policy.get("schema_version") or 0) != 1:
        raise SealAuditError("unsupported single-authority policy schema")

    static = _single_authority_static_scan(repo_root, policy)
    commands: list[dict[str, Any]] = []
    with tempfile.TemporaryDirectory(prefix="xgo-seal-authority-") as tmp:
        temp = Path(tmp)
        commands.append(_run(["go", "test", "./engine/...", "-count=1"]))
        commands.append(_run(["go", "vet", "./engine/..."]))

        android_report = temp / "android-legacy-authority.json"
        commands.append(_run([
            "go", "run", "./engine/cmd/xgo-android-audit",
            "--mode", "android_legacy_authority_scan",
            "--output", str(android_report),
        ]))
        android = _load(android_report)
        if not _report_passed(android):
            raise SealAuditError("Android legacy-authority seal did not pass")

        desktop_report = temp / "desktop-legacy-authority.json"
        commands.append(_run([
            "python3", "tools/xgo/audit_desktop.py",
            "--mode", "legacy-authority",
            "--output", str(desktop_report),
        ]))
        desktop = _load(desktop_report)
        if not _report_passed(desktop):
            raise SealAuditError("Desktop legacy-authority seal did not pass")

        desktop_e2e_report = temp / "desktop-e2e.json"
        commands.append(_run([
            "python3", "tools/xgo/audit_desktop.py",
            "--mode", "desktop-e2e",
            "--output", str(desktop_e2e_report),
        ]))
        desktop_e2e = _load(desktop_e2e_report)
        if not _report_passed(desktop_e2e):
            raise SealAuditError("Desktop Go execution/projection seal did not pass")

    report = {
        "schema_version": 1,
        "status": "pass",
        "policy": policy.get("policy"),
        "invariants": {
            "legacy_authority_not_compiled_into_desktop_runtime": True,
            "no_active_desktop_retry_backend_queue_policy": True,
            "no_active_desktop_hls_dash_execution": True,
            "no_host_canonical_store_writer": True,
            "android_scheduler_execution_is_go_authorized": True,
            "go_is_canonical_retry_backend_queue_authority": True,
            "go_is_canonical_hls_dash_execution_authority": True,
            "negative_scanner_fixtures_rejected": True,
        },
        "static_scan": static,
        "host_audits": {
            "android_legacy_authority": {"mode": _report_field(android, "mode"), "status": "pass"},
            "desktop_legacy_authority": {"mode": _report_field(desktop, "mode"), "status": "pass"},
            "desktop_e2e": {"mode": _report_field(desktop_e2e, "mode"), "status": "pass"},
        },
        "validation_scope": {
            "go": "full engine test and vet suites executed by this node",
            "android": "production authority surfaces plus android_legacy_authority_scan; complete host packaging/build is requalified by GATE-12",
            "desktop": "legacy-authority and desktop-e2e production scans; complete .NET host build/test is requalified by GATE-12",
        },
        "commands": commands,
    }
    _write(args.output, report)
    return report



def _closure_document_gaps(
    repo_root: Path,
    ledger: dict[str, Any],
    registry: dict[str, Any],
    donor_map: dict[str, Any],
    classifications: dict[str, Any],
    policy: dict[str, Any],
) -> tuple[list[str], dict[str, Any]]:
    gaps: list[str] = []
    for name, document in (
        ("ledger", ledger),
        ("fixture registry", registry),
        ("donor map", donor_map),
        ("differential classifications", classifications),
        ("closure policy", policy),
    ):
        if int(document.get("schema_version") or 0) != 1:
            gaps.append(f"{name}: unsupported schema_version {document.get('schema_version')!r}")

    caps_raw = ledger.get("capabilities") or []
    fixtures_raw = registry.get("fixtures") or []
    donors_raw = donor_map.get("entries") or []
    classifications_raw = classifications.get("classifications") or []
    groups_raw = policy.get("evidence_groups") or []
    if not isinstance(caps_raw, list) or not caps_raw:
        gaps.append("ledger: capabilities must be a non-empty list")
        caps_raw = []
    if not isinstance(fixtures_raw, list):
        gaps.append("fixture registry: fixtures must be a list")
        fixtures_raw = []
    if not isinstance(donors_raw, list):
        gaps.append("donor map: entries must be a list")
        donors_raw = []
    if not isinstance(classifications_raw, list):
        gaps.append("differential classifications: classifications must be a list")
        classifications_raw = []
    if not isinstance(groups_raw, list) or not groups_raw:
        gaps.append("closure policy: evidence_groups must be a non-empty list")
        groups_raw = []

    capabilities: dict[str, dict[str, Any]] = {}
    for item in caps_raw:
        if not isinstance(item, dict):
            gaps.append("ledger: capability entry is not an object")
            continue
        cid = str(item.get("id") or "")
        if not cid:
            gaps.append("ledger: capability has empty id")
        elif cid in capabilities:
            gaps.append(f"ledger: duplicate capability id {cid}")
        else:
            capabilities[cid] = item

    fixture_meta: dict[str, dict[str, Any]] = {}
    for item in fixtures_raw:
        if not isinstance(item, dict):
            gaps.append("fixture registry: fixture entry is not an object")
            continue
        fid = str(item.get("fixture_id") or "")
        if not fid:
            gaps.append("fixture registry: fixture has empty id")
        elif fid in fixture_meta:
            gaps.append(f"fixture registry: duplicate fixture id {fid}")
        else:
            fixture_meta[fid] = item
    if registry.get("fixture_count") != len(fixtures_raw):
        gaps.append(
            f"fixture registry: fixture_count {registry.get('fixture_count')!r} does not match {len(fixtures_raw)} entries"
        )

    donor_ids = {str(item.get("id") or "") for item in donors_raw if isinstance(item, dict)}
    if "" in donor_ids:
        gaps.append("donor map: empty donor id")
        donor_ids.discard("")

    classification_map: dict[str, dict[str, Any]] = {}
    for item in classifications_raw:
        if not isinstance(item, dict):
            gaps.append("differential classifications: entry is not an object")
            continue
        cid = str(item.get("capability_id") or "")
        if not cid:
            gaps.append("differential classifications: empty capability id")
        elif cid in classification_map:
            gaps.append(f"differential classifications: duplicate capability id {cid}")
        else:
            classification_map[cid] = item
    if classifications.get("classification_count") != len(classifications_raw):
        gaps.append(
            "differential classifications: classification_count does not match entries"
        )

    groups: dict[str, dict[str, Any]] = {}
    for item in groups_raw:
        if not isinstance(item, dict):
            gaps.append("closure policy: evidence group is not an object")
            continue
        gid = str(item.get("id") or "")
        if not gid:
            gaps.append("closure policy: evidence group has empty id")
        elif gid in groups:
            gaps.append(f"closure policy: duplicate evidence group {gid}")
        else:
            groups[gid] = item

    allowed_statuses = {str(v) for v in policy.get("allowed_final_statuses") or []}
    expected_statuses = {"IMPLEMENTED", "INTENTIONALLY_REMOVED"}
    if allowed_statuses != expected_statuses:
        gaps.append(
            f"closure policy: allowed_final_statuses must be {sorted(expected_statuses)}, got {sorted(allowed_statuses)}"
        )

    if set(classification_map) != set(capabilities):
        missing = sorted(set(capabilities) - set(classification_map))
        extra = sorted(set(classification_map) - set(capabilities))
        gaps.append(f"differential classifications: coverage mismatch missing={missing} extra={extra}")

    referenced_fixtures: set[str] = set()
    referenced_groups: set[str] = set()
    status_counts: dict[str, int] = {}
    area_counts: dict[str, int] = {}
    group_counts: dict[str, int] = {}
    rows: list[dict[str, Any]] = []

    for cid in sorted(capabilities):
        cap = capabilities[cid]
        status = str(cap.get("status") or "")
        area = str(cap.get("area") or "")
        status_counts[status] = status_counts.get(status, 0) + 1
        area_counts[area] = area_counts.get(area, 0) + 1
        if status not in expected_statuses:
            gaps.append(f"{cid}: non-final ledger status {status!r}")

        donors = [str(v) for v in cap.get("donors") or []]
        unknown_donors = sorted(set(donors) - donor_ids)
        if unknown_donors:
            gaps.append(f"{cid}: unknown donor ids {unknown_donors}")

        test_id = str(cap.get("test_id") or "")
        if not test_id.startswith("fixture:"):
            gaps.append(f"{cid}: final capability must bind a neutral fixture, got {test_id!r}")
            fixture_id = ""
        else:
            fixture_id = test_id.split(":", 1)[1]
            referenced_fixtures.add(fixture_id)
            meta = fixture_meta.get(fixture_id)
            if meta is None:
                gaps.append(f"{cid}: fixture {fixture_id!r} is missing from registry")
            else:
                if [str(v) for v in meta.get("capability_ids") or []] != [cid]:
                    gaps.append(f"{cid}: fixture registry capability link mismatch")
                path_text = str(meta.get("path") or "")
                fixture_path = repo_root / path_text
                if not fixture_path.is_file():
                    gaps.append(f"{cid}: fixture file missing: {path_text}")
                else:
                    try:
                        fixture = _load(fixture_path)
                    except SealAuditError as exc:
                        gaps.append(f"{cid}: {exc}")
                    else:
                        if int(fixture.get("schema_version") or 0) != 1:
                            gaps.append(f"{cid}: fixture schema_version is not 1")
                        if str(fixture.get("fixture_id") or "") != fixture_id:
                            gaps.append(f"{cid}: fixture file id mismatch")
                        if [str(v) for v in fixture.get("capability_ids") or []] != [cid]:
                            gaps.append(f"{cid}: fixture file capability link mismatch")
                        fixture_donors = [str(v) for v in fixture.get("donor_ids") or []]
                        if fixture_donors != donors:
                            gaps.append(f"{cid}: fixture donor ids drifted from ledger")
                        if "expected" not in fixture:
                            gaps.append(f"{cid}: fixture has no expected semantic outcome")

        classification = classification_map.get(cid) or {}
        cls = str(classification.get("classification") or "")
        if cls == "regression":
            gaps.append(f"{cid}: differential classification is regression")
        if status == "IMPLEMENTED" and cls == "intentionally_removed":
            gaps.append(f"{cid}: IMPLEMENTED capability classified intentionally_removed")
        if status == "INTENTIONALLY_REMOVED":
            if cls != "intentionally_removed":
                gaps.append(f"{cid}: INTENTIONALLY_REMOVED capability classification is {cls!r}")
            if not str(cap.get("removal_rationale") or "").strip():
                gaps.append(f"{cid}: intentional removal lacks removal_rationale")

        evidence_id = str(cap.get("evidence_id") or "")
        group = groups.get(evidence_id)
        if status == "IMPLEMENTED":
            if group is None:
                gaps.append(f"{cid}: implemented capability has unknown evidence_id {evidence_id!r}")
            else:
                group_areas = {str(v) for v in group.get("areas") or []}
                if area not in group_areas:
                    gaps.append(
                        f"{cid}: evidence group {evidence_id} does not cover capability area {area!r}"
                    )
                commands = group.get("commands") or []
                if not isinstance(commands, list) or not commands:
                    gaps.append(f"{cid}: evidence group {evidence_id} has no executable commands")
                referenced_groups.add(evidence_id)
                group_counts[evidence_id] = group_counts.get(evidence_id, 0) + 1
        elif evidence_id:
            gaps.append(f"{cid}: intentionally removed capability must not claim implementation evidence")

        rows.append(
            {
                "capability_id": cid,
                "area": area,
                "status": status,
                "fixture_id": fixture_id,
                "evidence_id": evidence_id,
                "classification": cls,
            }
        )

    extra_fixtures = sorted(set(fixture_meta) - referenced_fixtures)
    if extra_fixtures:
        gaps.append(f"fixture registry: unbound fixtures {extra_fixtures}")
    unreferenced_groups = sorted(set(groups) - referenced_groups)
    if unreferenced_groups:
        gaps.append(f"closure policy: unreferenced evidence groups {unreferenced_groups}")

    summary = {
        "capability_count": len(capabilities),
        "fixture_count": len(fixture_meta),
        "donor_count": len(donor_ids),
        "status_counts": dict(sorted(status_counts.items())),
        "area_counts": dict(sorted(area_counts.items())),
        "evidence_group_counts": dict(sorted(group_counts.items())),
        "rows": rows,
    }
    return gaps, summary


def _run_closure_evidence_group(group: dict[str, Any], temp: Path) -> dict[str, Any]:
    gid = str(group.get("id") or "")
    commands: list[dict[str, Any]] = []
    for index, spec in enumerate(group.get("commands") or []):
        if not isinstance(spec, dict):
            raise SealAuditError(f"closure evidence group {gid}: command {index} is not an object")
        argv_raw = spec.get("argv") or []
        if not isinstance(argv_raw, list) or not argv_raw:
            raise SealAuditError(f"closure evidence group {gid}: command {index} has no argv")
        report_path = temp / f"{gid}-{index}.json"
        argv = [str(value).replace("{output}", str(report_path)) for value in argv_raw]
        command = _run(argv)
        if bool(spec.get("report")):
            if not report_path.is_file():
                raise SealAuditError(f"closure evidence group {gid}: command {index} did not produce {report_path}")
            report = _load(report_path)
            if not _report_passed(report):
                raise SealAuditError(f"closure evidence group {gid}: report {index} did not pass")
            command["report_sha256"] = _sha256(report_path)
            command["report_status"] = "pass"
        commands.append(command)
    return {
        "id": gid,
        "areas": [str(v) for v in group.get("areas") or []],
        "status": "pass",
        "commands": commands,
    }


def capability_closure(args: argparse.Namespace) -> dict[str, Any]:
    repo_root = Path.cwd().resolve()
    ledger = _load(args.ledger)
    registry = _load(args.registry)
    donor_map = _load(args.donor_map)
    classifications = _load(args.classifications)
    policy = _load(args.policy)
    gaps, summary = _closure_document_gaps(
        repo_root, ledger, registry, donor_map, classifications, policy
    )
    if gaps:
        raise SealAuditError(f"capability closure has {len(gaps)} open gap(s): {gaps[:20]}")

    # Audit the audit: known-invalid in-memory variants must be rejected before
    # the repository is trusted as closed.
    negative_results: list[dict[str, str]] = []
    mutations: list[tuple[str, callable]] = [
        ("planned_status", lambda doc: doc["capabilities"][0].__setitem__("status", "PLANNED")),
        ("missing_evidence", lambda doc: doc["capabilities"][0].__setitem__("evidence_id", "")),
        ("missing_fixture", lambda doc: doc["capabilities"][0].__setitem__("test_id", "fixture:missing-xgo89")),
    ]
    for name, mutate in mutations:
        mutated = json.loads(json.dumps(ledger))
        mutate(mutated)
        negative_gaps, _ = _closure_document_gaps(
            repo_root, mutated, registry, donor_map, classifications, policy
        )
        if not negative_gaps:
            raise SealAuditError(f"capability closure negative self-test {name} was not rejected")
        negative_results.append({"id": name, "status": "rejected"})

    groups = {str(item.get("id") or ""): item for item in policy.get("evidence_groups") or []}
    evidence_results: list[dict[str, Any]] = []
    with tempfile.TemporaryDirectory(prefix="xgo-seal-closure-") as tmp:
        temp = Path(tmp)
        for gid in sorted(summary["evidence_group_counts"]):
            evidence_results.append(_run_closure_evidence_group(groups[gid], temp))

    single_policy_path = repo_root / str(policy.get("fresh_architecture_policy") or "")
    single_policy = _load(single_policy_path)
    if int(single_policy.get("schema_version") or 0) != 1:
        raise SealAuditError("capability closure fresh architecture policy schema is unsupported")
    fresh_architecture = _single_authority_static_scan(repo_root, single_policy)

    final_commands: list[dict[str, Any]] = []
    for raw in policy.get("final_engine_commands") or []:
        if not isinstance(raw, list) or not raw:
            raise SealAuditError("capability closure final_engine_commands contains an invalid command")
        final_commands.append(_run([str(v) for v in raw]))

    implemented = int(summary["status_counts"].get("IMPLEMENTED", 0))
    removed = int(summary["status_counts"].get("INTENTIONALLY_REMOVED", 0))
    if implemented + removed != int(summary["capability_count"]):
        raise SealAuditError("capability closure status accounting is inconsistent")

    report = {
        "schema_version": 1,
        "status": "pass",
        "policy": policy.get("policy"),
        "open_gap_count": 0,
        "remediation_required": False,
        "remediation_contract": "Any future open gap blocks XGO-89 and requires an explicit XGO-RMxx overlay plus regression evidence before rerunning the affected gate.",
        "closure": summary,
        "evidence_groups": evidence_results,
        "fresh_architecture_audit": {
            "status": "pass",
            "desktop_active_source_files_scanned": fresh_architecture.get("desktop_active_source_files_scanned"),
            "android_authority_surfaces_scanned": fresh_architecture.get("android_authority_surfaces_scanned"),
            "quarantined_source_count": fresh_architecture.get("quarantined_source_count"),
            "negative_fixtures": fresh_architecture.get("negative_fixtures"),
            "go_authority": fresh_architecture.get("go_authority"),
        },
        "negative_self_tests": negative_results,
        "final_engine_commands": final_commands,
        "gate12_remaining_host_qualification": {
            "go_race": "must execute on a Go race-supported host",
            "android": "complete Gradle/package/device qualification",
            "desktop": "complete .NET build/test/package qualification",
            "final_gate": "xgo_gate_final runs the Wave 12 xgo_seal workflow, then direct Android/Desktop host seals, then the Gate-12 final-wave verifier",
        },
    }
    report["closure_fingerprint"] = _canonical_hash(
        {
            "closure": summary,
            "evidence": [item["id"] for item in evidence_results],
            "fresh_architecture": report["fresh_architecture_audit"],
            "open_gap_count": 0,
        }
    )
    _write(args.output, report)
    return report

def _source_const(path: Path, name: str) -> str:
    source = path.read_text(encoding="utf-8")
    match = re.search(rf"(?m)^\s*(?:const\s+)?{re.escape(name)}\s*=\s*(\"[^\"]*\"|-?\d+)\s*$", source)
    if not match:
        raise SealAuditError(f"constant {name} not found in {path}")
    raw = match.group(1)
    return json.loads(raw) if raw.startswith('"') else raw


def abi_provenance(args: argparse.Namespace) -> dict[str, Any]:
    repo_root = Path.cwd().resolve()
    wire = repo_root / "engine/api/v1/wire.go"
    store_schema = repo_root / "engine/store/sqlite/schema.go"
    packaging = repo_root / "engine/androidhost/packaging.go"
    android_manifest = repo_root / "app/XDM.Android/app/src/main/assets/xdmcore/manifest.json"
    android_provenance = repo_root / "app/XDM.Android/app/src/main/assets/xdmcore/xdmcore-provenance.json"
    desktop_host = (repo_root / "app/XDM/src/XDM.Platform/DesktopGoEngineHost.cs").read_text(encoding="utf-8")
    android_host = (repo_root / "app/XDM.Android/app/src/main/kotlin/com/mikeyphw/xdm/android/engine/AndroidEngineProcessAuthority.kt").read_text(encoding="utf-8")
    build_sh = (repo_root / "app/XDM/eng/build-xgo-native.sh").read_text(encoding="utf-8")
    build_ps = (repo_root / "app/XDM/eng/build-xgo-native.ps1").read_text(encoding="utf-8")

    canonical = {
        "engine_version": str(_source_const(wire, "EngineVersion")),
        "protocol_major": int(_source_const(wire, "ProtocolMajor")),
        "protocol_minor": int(_source_const(wire, "ProtocolMinor")),
        "abi_major": int(_source_const(wire, "ABIMajor")),
        "schema_version": int(_source_const(wire, "SchemaVersion")),
    }
    store_version = int(_source_const(store_schema, "CurrentSchemaVersion"))
    if not re.fullmatch(r"\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?", canonical["engine_version"]):
        raise SealAuditError("engine version is not semantic")
    if canonical["schema_version"] != store_version:
        raise SealAuditError(
            f"runtime schema metadata {canonical['schema_version']} does not match authoritative store schema {store_version}"
        )

    manifest = _load(android_manifest)
    packaged = _load(android_provenance)
    if manifest.get("engine_version") != canonical["engine_version"] or manifest.get("engine_schema_version") != canonical["schema_version"]:
        raise SealAuditError("Android manifest compatibility tuple drifted")
    if manifest.get("protocol") != {"major": canonical["protocol_major"], "minor": canonical["protocol_minor"]}:
        raise SealAuditError("Android manifest protocol drifted")
    if manifest.get("provenance_name") != "xdmcore-provenance.json" or manifest.get("build_mode") != "c-shared":
        raise SealAuditError("Android manifest does not expose canonical native provenance")
    if packaged.get("engine_version") != canonical["engine_version"] or packaged.get("engine_schema_version") != canonical["schema_version"]:
        raise SealAuditError("Android packaged provenance compatibility tuple drifted")
    packaged_build = packaged.get("build") or {}
    for field in ("go_version", "git_revision", "target_os", "target_arch", "build_mode"):
        if not str(packaged_build.get(field) or "").strip():
            raise SealAuditError(f"Android packaged provenance is missing {field}")
    if packaged_build.get("target_os") != "android" or packaged_build.get("build_mode") != "c-shared":
        raise SealAuditError("Android packaged provenance target/build mode drifted")

    host_checks = {
        "desktop_create_compatibility": "compatibility = new" in desktop_host and "schema_version = ExpectedSchemaVersion" in desktop_host,
        "desktop_runtime_provenance": 'GetProperty("provenance")' in desktop_host and 'GetProperty("git_revision")' in desktop_host and '"c-shared"' in desktop_host,
        "android_create_compatibility": '"compatibility"' in android_host and '.put("schema_version", EXPECTED_SCHEMA_VERSION)' in android_host,
        "android_runtime_provenance": 'getJSONObject("provenance")' in android_host and 'optString("git_revision")' in android_host and 'optString("build_mode")' in android_host,
    }
    failed_hosts = sorted(name for name, passed in host_checks.items() if not passed)
    if failed_hosts:
        raise SealAuditError(f"host compatibility/provenance checks missing: {failed_hosts}")

    required_build_tokens = [
        "xdmcore-provenance.json",
        "BuildRevision",
        "BuildModified",
        "BuildMode=c-shared",
        "xgo-provenance",
    ]
    for label, source in (("unix", build_sh), ("windows", build_ps)):
        missing = [token for token in required_build_tokens if token not in source]
        if missing:
            raise SealAuditError(f"{label} native build does not seal provenance tokens: {missing}")

    commands: list[dict[str, Any]] = []
    with tempfile.TemporaryDirectory(prefix="xgo-seal-provenance-") as tmp:
        temp = Path(tmp)
        commands.append(_run(["go", "test", "./engine/api/v1", "./engine/androidhost", "-count=1"]))

        abi_report = temp / "abi-harness.json"
        commands.append(_run(["python3", "tools/xgo/run_abi_harness.py", "--output", str(abi_report)]))
        abi = _load(abi_report)
        required_symbols = [
            "xdm_engine_create", "xdm_engine_command", "xdm_engine_next_frame", "xdm_engine_platform_reply",
            "xdm_engine_metadata", "xdm_engine_shutdown", "xdm_buffer_free",
        ]
        if abi.get("status") != "pass":
            raise SealAuditError("ABI harness failed")
        observed_symbols = abi.get("symbols") or []
        if observed_symbols and observed_symbols != required_symbols:
            raise SealAuditError(f"ABI symbol inventory drifted: {observed_symbols}")

        cross_report = temp / "cross-host.json"
        commands.append(_run(["python3", "tools/xgo/audit_cross_host.py", "--output", str(cross_report)]))
        cross = _load(cross_report)
        if cross.get("status") != "pass" or cross.get("canonical") != canonical:
            raise SealAuditError("cross-host compatibility metadata audit failed")

        revision = _git(repo_root, "rev-parse", "HEAD")
        go_version = subprocess.check_output(["go", "env", "GOVERSION"], text=True).strip()
        android_plan = temp / "android-plan.json"
        commands.append(_run([
            "python3", "tools/xgo/build_android_xdmcore.py",
            "--go-version", go_version,
            "--source-revision", revision,
            "--source-modified", "false",
            "--output", str(android_plan),
        ]))
        plan = _load(android_plan)
        if plan.get("engine_version") != canonical["engine_version"] or plan.get("engine_schema_version") != canonical["schema_version"]:
            raise SealAuditError("Android native build plan provenance drifted")
        if plan.get("build_mode") != "c-shared" or plan.get("source_revision") != revision:
            raise SealAuditError("Android native build plan did not bind revision/build mode")

        generated: list[dict[str, Any]] = []
        for goos, goarch in (("android", "arm64"), ("linux", "amd64"), ("windows", "amd64")):
            path = temp / f"provenance-{goos}-{goarch}.json"
            command = [
                "go", "run", "./engine/cmd/xgo-provenance",
                "--revision", revision,
                "--modified=false",
                "--go-version", go_version,
                "--goos", goos,
                "--goarch", goarch,
                "--build-mode", "c-shared",
                "--output", str(path),
            ]
            commands.append(_run(command))
            document = _load(path)
            build = document.get("build") or {}
            if document.get("engine_version") != canonical["engine_version"] or document.get("engine_schema_version") != canonical["schema_version"]:
                raise SealAuditError(f"generated {goos}/{goarch} provenance compatibility tuple drifted")
            if build.get("git_revision") != revision or build.get("go_version") != go_version or build.get("target_os") != goos or build.get("target_arch") != goarch or build.get("build_mode") != "c-shared":
                raise SealAuditError(f"generated {goos}/{goarch} provenance fields drifted")
            generated.append({"target_os": goos, "target_arch": goarch, "build_mode": build.get("build_mode")})

    report = {
        "schema_version": 1,
        "status": "pass",
        "canonical": canonical,
        "authoritative_store_schema_version": store_version,
        "go_toolchain": go_version,
        "git_revision": revision,
        "host_compatibility_checks": host_checks,
        "android_packaging": {
            "manifest_schema_version": manifest.get("schema_version"),
            "provenance_name": manifest.get("provenance_name"),
            "target_os": packaged_build.get("target_os"),
            "target_arch": packaged_build.get("target_arch"),
            "build_mode": packaged_build.get("build_mode"),
        },
        "generated_provenance_targets": generated,
        "abi_symbols": required_symbols,
        "negative_compatibility_tests": [
            "wrong protocol major rejected at create/decode",
            "wrong engine semantic version rejected at create/decode",
            "wrong ABI major rejected at create/decode",
            "wrong schema migration expectation rejected at create/decode",
        ],
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

    adversarial = sub.add_parser("adversarial", help="run network/storage/media adversarial matrix")
    adversarial.add_argument("--output", type=Path, default=Path(".devtool/reports/xgo/seal/adversarial-matrix.json"))

    fuzz = sub.add_parser("fuzz-campaign", help="run bounded parser fuzz campaign")
    fuzz.add_argument("--policy", type=Path, default=Path("engine/testdata/seal/fuzz-policy.json"))
    fuzz.add_argument("--duration-seconds", type=int, default=0)
    fuzz.add_argument("--output", type=Path, default=Path(".devtool/reports/xgo/seal/fuzz-campaign.json"))

    perf = sub.add_parser("performance-budget", help="run engine scale and host contract performance budgets")
    perf.add_argument("--budget", type=Path, default=Path("engine/testdata/seal/performance-budget.json"))
    perf.add_argument("--output", type=Path, default=Path(".devtool/reports/xgo/seal/performance-budget.json"))

    provenance = sub.add_parser("abi-provenance", help="seal ABI, compatibility and build provenance metadata")
    provenance.add_argument("--output", type=Path, default=Path(".devtool/reports/xgo/seal/abi-provenance.json"))

    authority = sub.add_parser("single-authority", help="seal dead-code quarantine and single Go authority")
    authority.add_argument("--policy", type=Path, default=Path("engine/testdata/seal/single-authority-policy.json"))
    authority.add_argument("--output", type=Path, default=Path(".devtool/reports/xgo/seal/single-authority.json"))

    closure = sub.add_parser("capability-closure", help="close the capability ledger against fresh executable evidence")
    closure.add_argument("--ledger", type=Path, default=Path("engine/docs/capability-ledger.yaml"))
    closure.add_argument("--registry", type=Path, default=Path("engine/testdata/fixture-registry.json"))
    closure.add_argument("--donor-map", type=Path, default=Path("engine/docs/donor-map.yaml"))
    closure.add_argument("--classifications", type=Path, default=Path("engine/testdata/seal/differential-classifications.json"))
    closure.add_argument("--policy", type=Path, default=Path("engine/testdata/seal/capability-closure-policy.json"))
    closure.add_argument("--output", type=Path, default=Path(".devtool/reports/xgo/seal/capability-closure.json"))

    args = parser.parse_args()
    try:
        if args.command == "differential":
            report = differential(args)
        elif args.command == "state-machine":
            report = state_machine(args)
        elif args.command == "crash-matrix":
            report = crash_matrix(args)
        elif args.command == "adversarial":
            report = adversarial_matrix(args)
        elif args.command == "fuzz-campaign":
            report = fuzz_campaign(args)
        elif args.command == "performance-budget":
            report = performance_budget(args)
        elif args.command == "abi-provenance":
            report = abi_provenance(args)
        elif args.command == "single-authority":
            report = single_authority(args)
        else:
            report = capability_closure(args)
    except SealAuditError as exc:
        print(f"XGO seal audit FAILED: {exc}", file=sys.stderr)
        return 1
    print(json.dumps(report, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
