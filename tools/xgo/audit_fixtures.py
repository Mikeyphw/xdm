#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import os
import re
from pathlib import Path
from typing import Any

FIXTURE_ID_RE = re.compile(r"^xgo-cap-[a-z0-9-]+-[0-9]{3}$")
CAPABILITY_ID_RE = re.compile(r"^XGO-CAP-[A-Z0-9-]+-[0-9]{3}$")
REQUIRED_CATEGORIES = {
    "domain", "persistence", "ownership", "checkpoint", "verification",
    "publication", "recovery", "request", "security", "proxy", "http",
    "retry", "bandwidth", "checksum", "repair", "finalization", "ftp",
    "metalink", "backend", "aria2", "queue", "scheduler", "capture",
    "media", "hls", "dash", "ffmpeg", "settings", "secrets",
    "diagnostics", "import", "android", "desktop",
}
HIGH_RISK_DETAILED = {
    "ownership", "checkpoint", "publication", "recovery", "security", "http",
    "retry", "backend", "queue", "scheduler", "capture", "media", "hls",
    "dash", "ffmpeg", "diagnostics", "android", "desktop",
}
SECRET_KEY_RE = re.compile(r"(?:password|passwd|secret|token|api[_-]?key|authorization|cookie)", re.I)
BEARER_RE = re.compile(r"\bBearer\s+(?!<|REDACTED)[A-Za-z0-9._~+/=-]{8,}", re.I)
JWT_RE = re.compile(r"\beyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\b")
URL_SECRET_RE = re.compile(r"(?i)[?&](?:token|sig|signature|auth|key)=([^&#\s]+)")


class FixtureAuditError(RuntimeError):
    pass


def _load(path: Path) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError as exc:
        raise FixtureAuditError(f"missing file: {path}") from exc
    except json.JSONDecodeError as exc:
        raise FixtureAuditError(f"{path}: invalid JSON-compatible YAML/JSON: {exc}") from exc


def _write(path: Path | None, payload: dict[str, Any]) -> None:
    if path is None:
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    os.replace(tmp, path)


def _safe_rel(value: str) -> bool:
    p = Path(value)
    return bool(value) and not p.is_absolute() and ".." not in p.parts


def lint(ledger_path: Path, donor_map_path: Path, registry_path: Path, output: Path | None) -> dict[str, Any]:
    ledger = _load(ledger_path)
    donor_map = _load(donor_map_path)
    registry = _load(registry_path)
    if not isinstance(ledger, dict) or ledger.get("schema_version") != 1:
        raise FixtureAuditError("unsupported capability ledger schema")
    if not isinstance(donor_map, dict) or donor_map.get("schema_version") != 1:
        raise FixtureAuditError("unsupported donor map schema")
    if not isinstance(registry, dict) or registry.get("schema_version") != 1:
        raise FixtureAuditError("unsupported fixture registry schema")

    capabilities_raw = ledger.get("capabilities")
    donor_entries = donor_map.get("entries")
    fixtures_raw = registry.get("fixtures")
    if not isinstance(capabilities_raw, list) or not capabilities_raw:
        raise FixtureAuditError("capability ledger has no capabilities")
    if not isinstance(donor_entries, list) or not donor_entries:
        raise FixtureAuditError("donor map has no entries")
    if not isinstance(fixtures_raw, list) or not fixtures_raw:
        raise FixtureAuditError("fixture registry has no fixtures")

    capabilities = {str(c.get("id") or ""): c for c in capabilities_raw if isinstance(c, dict)}
    donor_ids = {str(d.get("id") or "") for d in donor_entries if isinstance(d, dict)}
    if "" in capabilities or "" in donor_ids:
        raise FixtureAuditError("empty capability or donor id")

    expected_fixture_ids: dict[str, str] = {}
    for cid, cap in capabilities.items():
        if not CAPABILITY_ID_RE.fullmatch(cid):
            raise FixtureAuditError(f"invalid capability id: {cid}")
        test_id = str(cap.get("test_id") or "")
        if not test_id.startswith("fixture:"):
            raise FixtureAuditError(f"{cid}: XGO-02 requires language-neutral fixture id, got {test_id!r}")
        fid = test_id.split(":", 1)[1]
        if not FIXTURE_ID_RE.fullmatch(fid):
            raise FixtureAuditError(f"{cid}: invalid fixture id {fid!r}")
        if fid in expected_fixture_ids:
            raise FixtureAuditError(f"fixture id {fid} is assigned to multiple capabilities")
        expected_fixture_ids[fid] = cid

    seen: set[str] = set()
    categories: set[str] = set()
    detailed_categories: set[str] = set()
    checked_files = 0

    repo_root = Path.cwd().resolve()
    for idx, entry in enumerate(fixtures_raw):
        if not isinstance(entry, dict):
            raise FixtureAuditError(f"fixtures[{idx}] must be an object")
        fid = str(entry.get("fixture_id") or "")
        if not FIXTURE_ID_RE.fullmatch(fid):
            raise FixtureAuditError(f"fixtures[{idx}] has invalid fixture_id {fid!r}")
        if fid in seen:
            raise FixtureAuditError(f"duplicate fixture id {fid}")
        seen.add(fid)

        rel = str(entry.get("path") or "")
        if not _safe_rel(rel):
            raise FixtureAuditError(f"{fid}: unsafe fixture path {rel!r}")
        path = (repo_root / rel).resolve()
        try:
            path.relative_to(repo_root)
        except ValueError as exc:
            raise FixtureAuditError(f"{fid}: fixture escapes repository") from exc
        document = _load(path)
        checked_files += 1
        if not isinstance(document, dict) or document.get("schema_version") != 1:
            raise FixtureAuditError(f"{rel}: unsupported schema")
        if str(document.get("fixture_id") or "") != fid:
            raise FixtureAuditError(f"{rel}: fixture_id mismatch")

        category = str(entry.get("category") or "")
        if category != str(document.get("category") or "") or not category:
            raise FixtureAuditError(f"{fid}: category mismatch")
        categories.add(category)
        if bool(entry.get("detailed")):
            detailed_categories.add(category)

        cap_ids = [str(v) for v in entry.get("capability_ids") or []]
        doc_cap_ids = [str(v) for v in document.get("capability_ids") or []]
        if cap_ids != doc_cap_ids or len(cap_ids) != 1:
            raise FixtureAuditError(f"{fid}: expected exactly one matching capability link")
        cid = cap_ids[0]
        if expected_fixture_ids.get(fid) != cid:
            raise FixtureAuditError(f"{fid}: registry does not match ledger assignment")
        donors = [str(v) for v in entry.get("donor_ids") or []]
        doc_donors = [str(v) for v in document.get("donor_ids") or []]
        if donors != doc_donors:
            raise FixtureAuditError(f"{fid}: donor link mismatch")
        unknown = sorted(set(donors) - donor_ids)
        if unknown:
            raise FixtureAuditError(f"{fid}: unknown donor ids: {', '.join(unknown)}")
        expected = document.get("expected")
        if not isinstance(expected, dict):
            raise FixtureAuditError(f"{fid}: expected must be an object")
        invariants = expected.get("invariants")
        if not isinstance(invariants, list) or not invariants or not all(str(v).strip() for v in invariants):
            raise FixtureAuditError(f"{fid}: expected.invariants must be non-empty strings")

    missing = sorted(set(expected_fixture_ids) - seen)
    extra = sorted(seen - set(expected_fixture_ids))
    if missing or extra:
        raise FixtureAuditError(f"fixture/ledger mismatch: missing={missing}, extra={extra}")
    missing_categories = sorted(REQUIRED_CATEGORIES - categories)
    if missing_categories:
        raise FixtureAuditError(f"missing fixture categories: {', '.join(missing_categories)}")
    missing_detailed = sorted(HIGH_RISK_DETAILED - detailed_categories)
    if missing_detailed:
        raise FixtureAuditError(f"high-risk categories need a detailed scenario: {', '.join(missing_detailed)}")
    if registry.get("fixture_count") != len(seen):
        raise FixtureAuditError("fixture_count does not match registry contents")

    report = {
        "schema_version": 1,
        "status": "pass",
        "fixture_count": len(seen),
        "checked_files": checked_files,
        "capability_count": len(capabilities),
        "category_count": len(categories),
        "detailed_fixture_count": sum(1 for e in fixtures_raw if isinstance(e, dict) and bool(e.get("detailed"))),
        "high_risk_categories_covered": sorted(HIGH_RISK_DETAILED),
    }
    _write(output, report)
    return report


def _iter_strings(value: Any, path: str = "$"):
    if isinstance(value, dict):
        for key, item in value.items():
            yield from _iter_strings(item, f"{path}.{key}")
    elif isinstance(value, list):
        for index, item in enumerate(value):
            yield from _iter_strings(item, f"{path}[{index}]")
    elif isinstance(value, str):
        yield path, value


def _placeholder(value: str) -> bool:
    stripped = value.strip()
    return (
        not stripped
        or stripped.startswith("<")
        or stripped.upper() in {"REDACTED", "[REDACTED]", "NONE", "NULL"}
        or stripped.startswith("fixture:")
    )


def secret_scan(registry_path: Path, output: Path | None) -> dict[str, Any]:
    registry = _load(registry_path)
    fixtures = registry.get("fixtures") if isinstance(registry, dict) else None
    if not isinstance(fixtures, list):
        raise FixtureAuditError("fixture registry has no fixtures")
    repo_root = Path.cwd().resolve()
    findings: list[dict[str, str]] = []
    scanned = 0
    for entry in fixtures:
        if not isinstance(entry, dict):
            continue
        path = repo_root / str(entry.get("path") or "")
        document = _load(path)
        scanned += 1
        for loc, value in _iter_strings(document):
            if BEARER_RE.search(value) or JWT_RE.search(value):
                findings.append({"fixture": str(entry.get("fixture_id")), "location": loc, "reason": "credential-like token"})
            for match in URL_SECRET_RE.finditer(value):
                if not _placeholder(match.group(1)):
                    findings.append({"fixture": str(entry.get("fixture_id")), "location": loc, "reason": "URL secret-like query value"})
        def visit(node: Any, loc: str = "$") -> None:
            if isinstance(node, dict):
                for key, item in node.items():
                    child = f"{loc}.{key}"
                    if SECRET_KEY_RE.search(str(key)) and isinstance(item, str) and not _placeholder(item):
                        findings.append({"fixture": str(entry.get("fixture_id")), "location": child, "reason": "secret-like field contains non-placeholder value"})
                    visit(item, child)
            elif isinstance(node, list):
                for i, item in enumerate(node):
                    visit(item, f"{loc}[{i}]")
        visit(document)
    if findings:
        preview = "; ".join(f"{f['fixture']}:{f['location']}:{f['reason']}" for f in findings[:8])
        raise FixtureAuditError(f"fixture secret scan failed ({len(findings)} findings): {preview}")
    report = {"schema_version": 1, "status": "pass", "scanned_fixture_count": scanned, "finding_count": 0}
    _write(output, report)
    return report


def main() -> int:
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command", required=True)
    lint_p = sub.add_parser("lint")
    lint_p.add_argument("--ledger", type=Path, required=True)
    lint_p.add_argument("--map", dest="donor_map", type=Path, required=True)
    lint_p.add_argument("--registry", type=Path, required=True)
    lint_p.add_argument("--output", type=Path)
    scan_p = sub.add_parser("secret-scan")
    scan_p.add_argument("--registry", type=Path, required=True)
    scan_p.add_argument("--output", type=Path)
    args = parser.parse_args()
    try:
        if args.command == "lint":
            report = lint(args.ledger, args.donor_map, args.registry, args.output)
        else:
            report = secret_scan(args.registry, args.output)
    except FixtureAuditError as exc:
        print(f"XGO fixture audit FAILED: {exc}", file=os.sys.stderr)
        return 1
    print(json.dumps(report, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
