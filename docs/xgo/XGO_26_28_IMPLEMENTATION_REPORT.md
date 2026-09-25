# XGO-26..28 Implementation Report

## Delivered

### XGO-26 — Representation probe and metadata discovery

- Added `engine/transfer/http` probe ownership.
- HEAD is preferred, with 403/405 fallback to `GET Range: bytes=0-0`.
- Probe records exact length when knowable, byte-range support, ETag strength, Last-Modified, Content-Disposition filename, Content-Type and effective URL.
- Transparent/non-identity content encoding is rejected so byte offsets cannot silently change.
- Malformed or contradictory Content-Range metadata is rejected; unknown length stays unknown.

### XGO-27 — Representation identity and resume compatibility

- Added explicit representation identity strength ordering.
- Strong ETag is authoritative when available.
- Weak ETag never authorizes byte reuse.
- Last-Modified + length and length-only reuse require explicit weaker-policy opt-in.
- Effective resource identity is opaque and derived from the effective transport URL.
- Mirror changes require explicit mirror compatibility policy, even when strong validators match.
- `CanResume(previous,current,policy)` returns a typed allow/deny reason.

### XGO-28 — Single-stream native HTTP execution

- Added bounded streaming into staging through the existing durable checkpoint committer.
- No byte becomes resumable merely because it was read or written; only a committed checkpoint block advances durable progress.
- Added context cancellation, limiter hooks, progress callbacks and exact expected-length enforcement.
- Pause hands off only at a durable checkpoint boundary.
- Restart from a committed prefix sends a Range request with If-Range and validates Content-Range against the persisted representation.
- Added SQLite-backed attempt lifecycle adapter for `reserved -> prepared -> running`, `running -> paused`, `paused -> running`, failure and produced-artifact transitions.
- Network, cancellation, representation/range and storage errors map to canonical typed failures.

## Capability closure

The following ledger entries are now `IMPLEMENTED` with detailed neutral fixtures:

- `XGO-CAP-HTTP-001`
- `XGO-CAP-HTTP-002`
- `XGO-CAP-HTTP-003`

The shared ledger remains 96 capabilities / 96 fixtures.

## Devtool validation

`xgo_transfer` is promoted from its bootstrap command target to the native Go runner.

Authoritative workflow:

1. native Go restore/build/test/vet
2. `transfer_contract_audit`
3. `http_probe_lab`
4. `representation_matrix`
5. `http_range_lab`

GATE-04 is not closed by this overlay; XGO-29..35 remain outstanding.
