# XGO-14+15+16 Overlay Contract

## Scope

This overlay implements three adjacent durable-authority roadmap items under `xgo_store`:

- **XGO-14** — attempt generation reservation and stale-writer fencing;
- **XGO-15** — durable backend ownership/task activation protocol;
- **XGO-16** — integrity-backed checkpoint block ledger and commit ordering.

## Authoritative outcome

After this overlay, an attempt-owned write is authoritative only when:

1. the `(DownloadID, AttemptGeneration)` equals the Download's current generation inside the same SQLite transaction as the mutation;
2. the mutation supplies the expected row Revision where the record is mutable;
3. byte-producing/checkpoint paths additionally have active backend ownership and an active backend task for that generation.

A stale generation returns `ErrStaleAttempt` and changes zero authoritative rows.

## Files / APIs introduced

- `engine/domain/backend` — typed ownership/task states and transition contract.
- `engine/store/sqlite/authority.go` — generation reservation, fenced attempt mutation, ownership/task protocol, authoritative-writer assertion, checkpoint ledger persistence.
- `engine/store/checkpoint` — durable block committer and recovery inspection.
- `xgo-store-audit` modes `authority`, `ownership`, and `checkpoint`.

## Invariants

- Generation reservation and Download current-generation update are one transaction.
- Backend activation cannot skip claim, task binding, or ready state.
- No authoritative bytes/checkpoint evidence before active ownership + active task.
- Checkpoint row creation follows successful write, sync, and read-back integrity calculation.
- Pre-persist crash/fault leaves zero resumable rows.
- Post-persist crash/fault leaves recoverable committed evidence.
- Committed checkpoint ranges cannot overlap.
- Exact checkpoint re-commit is idempotent; conflicting evidence is rejected.

## Devtool validation

Target: `xgo_store`

Workflow nodes added:

- `stale_writer_matrix`
- `ownership_crash_matrix`
- `checkpoint_faults`

The overlay leaves `validation.tasks` absent so `xgo_store#validate` remains authoritative.

## Intentional non-scope

This overlay does not implement artifact verification (XGO-17), publication saga (XGO-18), or startup recovery orchestration (XGO-19); therefore it does not run GATE-02.
