# XGO-32 Implementation Report

## Delivered

### XGO-32 — Connection and bandwidth arbitration

- Added one central Go transfer arbiter with global, per-host and per-download connection limits.
- Added bound transfer handles so the same authority owns connection admission and byte-rate reservations.
- Added global bandwidth plus queue/profile/per-download rate hooks.
- Added monotonic reservation accounting through an injectable clock abstraction.
- Added fair per-download bandwidth admission: a Download may hold at most one future byte reservation, preventing many segment workers from reserving global bandwidth indefinitely ahead of another active Download.
- Added live limit replacement with a monotonically increasing limits revision and waiter wake-up.
- Added cancellation-safe connection queues and idempotent connection lease release.
- Added diagnostics metrics for active/peak connections, acquisitions, waits, cancellations, bandwidth requests/bytes/wait time, per-download bytes and limits revision.
- Integrated the arbiter into single-stream, sparse-resume and segmented HTTP execution. Segmented workers acquire independent connection permits through the shared bound handle, so the per-download connection scope is the authoritative segment concurrency cap.
- Preserved the previous byte-only `Limiter` hook as a compatibility fallback; when `Resources` is present the bound arbiter handle is authoritative for both connections and bytes.

## Capability closure

`XGO-CAP-BANDWIDTH-001` is now `IMPLEMENTED`, and `xgo-cap-bandwidth-001` is promoted to a detailed fixture covering connection caps, host isolation, per-download segment concurrency, composed bandwidth scopes, fairness, live updates, cancellation and diagnostics.

The shared ledger remains **96 capabilities / 96 fixtures**.

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
9. `bandwidth_stress`

The `bandwidth_stress` job verifies global/host/download connection caps, host isolation, live connection-limit updates, a measurable global throughput bound, equal byte accounting across two active transfers and clean permit release.

Verified Devtool run: **12 stages, 51 tests, 3 passing Go packages, 0 warnings, 0 errors, 1 verified Go executable**.

GATE-04 remains open. XGO-33..35 still add full-artifact checksum verification, selective repair and authoritative finalization.
