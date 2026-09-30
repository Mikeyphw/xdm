# XGO-RM06 — Android aria2 runtime and recovery convergence

Date: 2026-09-30
Gate: GATE-12 remediation loop
Owner: Android embedded aria2 runtime/recovery boundary

## Why RM06 exists

The Gate-12 aria2 audit found three integration defects that source/contract validation had not exercised as one real runtime boundary:

1. XDM's authenticated aria2 RPC endpoint is `http://127.0.0.1`, while the app network-security configuration denied all cleartext traffic.
2. Saved aria2 sessions were filtered line-by-line even though aria2 input/session files are URI records followed by indented option lines. That could retain `gid=` while deleting the URI and the rest of the record.
3. The OpenSSL-backed aria2 runtime had no Android CA-store bridge, so HTTPS certificate verification had no explicit Android trust source.

RM06 is part of the existing GATE-12 remediation loop. It does not create a new gate and does not reapply GATE-12.

## Implemented

- Keep app-wide cleartext disabled and allow cleartext only for the exact loopback host `127.0.0.1` used by authenticated aria2 JSON-RPC.
- Keep `rpc-listen-all=false` and the existing per-install RPC secret boundary.
- Add a record-aware aria2 saved-session parser. Ownership is determined only from an exact `gid=` option inside a complete URI record; retained records are rewritten atomically as complete records.
- Drop malformed, GID-less, and foreign records atomically rather than retaining fragments. Sanitizer read/write failures now fail closed instead of being reported as zero removals.
- Remove session pruning from generic `Aria2SessionStore.prepare()` so ordinary directory/log/config preparation cannot mutate durable recovery state.
- Reconcile and checkpoint an authenticated persisted XDM-owned aria2 daemon before pruning the durable session file. If `aria2.saveSession()` fails, leave the owned daemon running and fail startup closed instead of risking stale-state pruning; only a successfully checkpointed daemon is stopped and replaced.
- Materialize `AndroidCAStore` into an app-private `noBackupFilesDir` PEM bundle and launch aria2 with `check-certificate=true` plus `ca-certificate=<bundle>`.
- Add executable JVM tests for multi-line session ownership behavior and source contracts for the loopback-only cleartext/trust/recovery ordering boundaries.

## Validation ownership

RM06 introduces a focused Devtool Android validation target that runs only `:transfer-aria2:test`. Broader Android packaging, lint, device instrumentation, native-runtime provenance and final Gate-12 qualification remain separate boundaries.

## Gate-12 consequence

The previously retained Android unit-test checkpoint is invalid for the touched aria2 module. After RM06 and the following aria2 remediation items complete, the Gate-12 continuation must include refreshed aria2 unit/runtime evidence before relying on the old checkpoint.
