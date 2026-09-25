# XGO-29..31 Implementation Report

## Delivered

### XGO-29 — Random-access segmented staging

- Added checkpoint-aligned segment planning for 2/4/8+ range partitions.
- Added one-file preallocation and random-access range writes; normal segmented HTTP no longer needs merge files.
- Persisted the segment plan under the current `AttemptGeneration`.
- Segment completion is accepted only when committed checkpoint evidence covers the exact segment interval with no gap.
- Added strict `206 Content-Range` start/end/total and body-length validation.
- Range rejection is detected before segmented checkpoint evidence and can fall back to the existing single-stream executor.
- Added out-of-order completion, overlap/short response, preallocation failure and final byte-for-byte tests.

### XGO-30 — If-Range resume and sparse partial recovery

- Added a recovery planner that inspects every committed checkpoint by read-back hash rather than trusting staging length.
- Corrupt, truncated or representation-incompatible checkpoint rows are durably marked invalid under the current-generation fence.
- Invalidated block identities may be replaced only by newly fetched bytes that pass the checkpoint durability sequence again.
- Missing ranges are built as the complement of valid sparse blocks, preserving later valid blocks across an earlier hole.
- Changed validators force a clean restart plan.
- Conditional-range `200` invalidates old committed evidence before response bytes can append to staging.
- Added unchanged resume, sparse repair, changed ETag, truncation/corruption, EOF-complete and stale-attempt coverage.

### XGO-31 — Canonical retry policy

- Added typed retry outcomes: `retry_now`, `retry_at`, `hold`, `terminal`.
- Inputs include canonical failure policy, attempt count, Retry-After, queue limits, network availability, request replayability and user override.
- Added Retry-After seconds and HTTP-date parsing.
- Exponential backoff uses injected clock/jitter sources and bounded maximum delay.
- TLS/invalid/integrity-style terminal failures, auth/route/storage holds, offline holds and non-replayable request behavior are explicit.
- Retry decisions are persisted on the failed attempt through revision CAS/current-generation fencing; absolute deadlines survive process restart.
- Added HTTP 429/500/503/auth classification and deterministic timing tests.

## Capability closure

The following capability-ledger entries are now `IMPLEMENTED`:

- `XGO-CAP-HTTP-004`
- `XGO-CAP-HTTP-005`
- `XGO-CAP-HTTP-006`
- `XGO-CAP-RETRY-001`

`xgo-cap-http-004` is promoted to a detailed fixture. The shared ledger remains **96 capabilities / 96 fixtures**.

## Devtool validation

`xgo_transfer#validate` now runs:

1. native Go restore/build/test/vet
2. `transfer_contract_audit`
3. `http_probe_lab`
4. `representation_matrix`
5. `http_range_lab`
6. `resume_lab`
7. `checkpoint_crash_matrix`
8. `retry_matrix`

Verified run: **11 stages, 42 tests, 2 passing Go packages, 0 warnings, 0 errors, 1 verified Go executable**.

GATE-04 remains open. XGO-32..35 still add bandwidth/connection arbitration, whole-artifact checksum verification, selective repair and authoritative finalization.
