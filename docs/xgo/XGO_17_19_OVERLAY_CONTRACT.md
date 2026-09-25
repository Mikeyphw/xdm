# XGO-17..19 Overlay Contract

## Owning target

`xgo_gate_durable`

A successful artifact apply qualifies GATE-02; no separate manual gate is
required.

## Preconditions

- XGO-00..16 are present.
- `xgo_foundation` and `xgo_store` validate successfully.
- Native Termux can build the Go workspace and link the SQLite C ABI.

## Required invariants

1. Only the current attempt generation may create verification/publication
   authority.
2. Failed verification never allocates an ArtifactGeneration.
3. Passed verification plus artifact creation plus Download pointer/revision
   advance is atomic.
4. Publication idempotency keys are durable and unique across Downloads.
5. Ambiguous requested publication is inspected, never blindly replayed.
6. Download completion requires a verified current artifact and durable platform
   commit receipt.
7. Repeated platform replies, engine commit, cleanup, and terminal recovery are
   idempotent where the durable identity matches exactly.
8. Recovery does not infer direct filesystem access from host storage identity.
9. Stale backend completion is diagnostic-only.
10. SQLite integrity and stale-writer fencing survive every Gate-02 fault case.

## Validation DAG

`xgo_gate_durable#validate` is a pure cross-target composition:

- `target:xgo_foundation#validate`
- `target:xgo_store#validate`
  - including `verification_faults`, `publication_faults`, `recovery_matrix`, and the final `durable_fault_matrix` node

Artifact validation must remain `required = true`, `allow_deferred = false`, and
`failure_action = "pause"`. Do not add `validation.tasks`; the explicit Devtool
workflow DAG is authoritative.
