# XGO-14+15+16 Implementation Report

## Implemented

### XGO-14 — current-generation authority

- Atomic `ReserveAttemptGeneration` under `BEGIN IMMEDIATE`.
- Download revision CAS prevents duplicate concurrent generation reservation.
- Attempt mutations re-check the current generation inside the same transaction as the update.
- Superseded generations return `ErrStaleAttempt` and leave historical attempt rows unchanged.

### XGO-15 — durable backend ownership

- Typed ownership states: `claimed -> task_bound -> ready -> active -> retired`, with pre-activation abandonment states defined.
- Backend task starts as `prepared`; activation updates task and ownership together.
- `AssertAuthoritativeWriter` grants byte authority only to the current generation with both active ownership and active task.
- Superseding the generation immediately fences the previously active backend.

### XGO-16 — integrity-backed checkpoints

- `checkpoint.Committer` owns `WriteAt -> Sync -> read-back SHA-256 -> checkpoint DB commit`.
- Fault injection covers `after_write`, `after_sync`, `after_hash`, and `after_persist`.
- Faults before persistence leave no checkpoint rows; a post-persist fault retains valid evidence.
- Overlapping ranges and conflicting block identities are rejected.
- Recovery inspection detects staging truncation and hash mismatch.

## Validation

Devtool target `xgo_store` validated with:

- Go restore/build/test/vet;
- shared store/capability/fixture contract audit;
- SQLite platform smoke;
- schema audit;
- revision CAS stress;
- 64-round stale-generation matrix;
- ownership activation boundary matrix;
- checkpoint durability fault matrix.

Development simulation result: 11 stages passed, 26 Go tests passed, 4 test packages passed, 0 warnings, 0 errors.

## Capability ledger

Marked IMPLEMENTED:

- `XGO-CAP-OWNERSHIP-001`
- `XGO-CAP-OWNERSHIP-002`
- `XGO-CAP-CHECKPOINT-001`
