# XGO-32 Overlay Contract

- Target: `xgo_transfer`
- Validation required: yes
- Failure action: `pause`
- Deferred validation: no
- `validation.tasks`: absent; `xgo_transfer#validate` remains authoritative.
- Execution environment: repository target remains `native-termux`.

## Authority boundaries

- One bound central arbiter handle owns native HTTP connection permits and byte-rate reservations.
- Global, host and download connection limits compose; a blocked host cannot consume another host's available capacity.
- Per-download connection limits are the authoritative segmented-HTTP concurrency cap.
- Global bandwidth and queue/profile/download hooks compose at reservation time using monotonic-time accounting.
- A Download may hold at most one future bandwidth reservation, preventing segment fan-out from monopolizing future global grants.
- Live limit changes wake queued connection requests and apply to subsequent bandwidth reservations; already-promised grant deadlines are not silently rewritten.
- Cancellation removes queued connection waiters, and connection leases release capacity idempotently.
- Diagnostics are snapshots only and never become scheduling authority.
- The scheduler/profile system may change arbiter limits later, but it may not bypass the arbiter for native transfer execution.

## Deferred work

Checksum verification, selective repair and finalization remain XGO-33..35. These form the next three-item window and should close GATE-04 together if their final authority boundaries remain coherent.
