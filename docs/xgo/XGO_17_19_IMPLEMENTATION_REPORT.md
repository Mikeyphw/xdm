# XGO-17..19 — Verification, Publication, Recovery — Implementation Report

## Scope

This overlay completes the durable-state wave introduced by XGO-11..16 without
changing schema version 1. The v1 schema intentionally reserved the verification,
artifact, publication, and diagnostic tables required here.

### XGO-17 — Artifact and verification journal

- Verification records are append-only.
- Failed verification retains evidence but creates no artifact generation.
- Successful verification atomically inserts the passed verification record,
  creates the verified artifact, advances `current_artifact_generation`, and
  increments the Download revision.
- Artifact generations are allocated only inside the accepted transaction.
- Stale attempt generations cannot verify or publish old output.
- Fault injection proves rollback between the journal and artifact rows leaves
  no partial authoritative state.

### XGO-18 — Publication saga

Publication follows the durable state machine:

`PREPARED -> PLATFORM_COMMIT_REQUESTED -> PLATFORM_COMMITTED -> ENGINE_COMMITTED -> CLEANED`

The saga provides:

- durable idempotency keys;
- cross-Download idempotency-key collision rejection;
- exact duplicate platform receipt idempotence;
- conflicting receipt rejection;
- ambiguous-request reconciliation through receipt inspection rather than
  blind republish;
- Download completion only after engine commit of the verified current artifact;
- idempotent terminal cleanup.

### XGO-19 — Recovery coordinator v1

Recovery classifies durable state into nine explicit categories:

- `active_owned_backend`
- `backend_missing`
- `stale_ownership`
- `current_checkpoint_recoverable`
- `corrupt_checkpoint`
- `verified_artifact_unpublished`
- `platform_committed_engine_uncommitted`
- `completed`
- `unrecoverable`

Checkpoint inspection uses a resolver interface so platform storage identities
are not treated as ordinary paths. Recovery can resume an interrupted
publication after engine commit, is idempotent after cleanup, and treats late
stale-backend completion as diagnostic-only evidence.

## Gate-02 qualification

The `xgo_gate_durable` workflow composes the full foundation validation, the full
`xgo_store` validation DAG, whose final node is the durable fault matrix. The matrix closes
and reopens SQLite around publication/recovery transitions, runs checkpoint and
ownership crash cases, requires SQLite integrity after the scenarios, and
reasserts the stale-writer invariant.

The specialized XGO-17..19 nodes are:

- `verification_faults`
- `publication_faults`
- `recovery_matrix`
- final `xgo_store` node `durable_fault_matrix`

Validation is authoritative through Devtool/EXO; these jobs emit domain reports
but do not replace the native Go build/test/vet workflow.
