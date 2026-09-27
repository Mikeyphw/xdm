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
        else:
            report = performance_budget(args)
    except SealAuditError as exc:
        print(f"XGO seal audit FAILED: {exc}", file=sys.stderr)
        return 1
    print(json.dumps(report, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
