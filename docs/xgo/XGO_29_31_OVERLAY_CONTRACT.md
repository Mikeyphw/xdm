# XGO-29..31 Overlay Contract

- Target: `xgo_transfer`
- Validation required: yes
- Failure action: `pause`
- Deferred validation: no
- `validation.tasks`: absent; `xgo_transfer#validate` remains authoritative.
- Execution environment: repository target remains `native-termux`.

## Authority boundaries

- Segment planning requires a known representation length and checkpoint-aligned, non-overlapping byte ownership.
- Preallocation happens before segmented writes; preallocation failure creates no checkpoint evidence.
- A segment worker cannot declare durable completion by returning successfully; SQLite requires exact checkpoint coverage first.
- A server that ignores Range may fall back to single stream only before segmented checkpoint evidence exists.
- Resume reuses only read-back verified committed checkpoint blocks, never file length or unverified bytes.
- Corrupt/truncated/incompatible checkpoint rows are durably invalidated and may be replaced only after fresh integrity verification.
- `200` after conditional Range is a restart signal and cannot append to existing partial data.
- Stale attempt generations cannot plan resume, invalidate checkpoints or persist retry metadata.
- Retry backoff/deadlines are canonical engine decisions with injected time/jitter; worker-local timers are not authoritative.
- The persisted retry record contains an absolute deadline so restart does not silently extend backoff.

## Deferred work

Connection/bandwidth arbitration, checksum verification, selective repair and finalization remain XGO-32..35. GATE-04 remains open.
