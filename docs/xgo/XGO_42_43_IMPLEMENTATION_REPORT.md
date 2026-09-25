# XGO-42 + XGO-43 Implementation Report

## Scope

XGO-42 and XGO-43 are delivered together because they are one durable backend ownership boundary:

- XGO-42 establishes aria2 runtime/GID ownership as a generation-scoped authority tuple.
- XGO-43 reconciles restored daemon tasks and migrates authority across backends without accepting stale writers.

XGO-44 remains outside this artifact because it begins the next wave after GATE-05.

## Implemented behavior

- Added durable aria2 ownership/reconciliation semantics around `(download, generation, runtime identity, GID, staging identity)`.
- Rebinding a daemon-restored task now requires staging proof and creates a fresh generation; GID reuse alone is never accepted.
- Rebinding is a single SQLite transaction that reserves the new generation, binds runtime/GID/staging ownership, updates the current download pointer, and closes the historical owner atomically.
- Added protocol-neutral backend migration coordination with explicit boundaries: reserved, source quiesced, target established, target active, and source retired.
- Migration uses the fresh target generation as the stale-writer fence before target activation begins.
- Checkpoint/staging reuse is an explicit source decision and is not inferred from path equality.
- Late source progress/completion after generation transfer is diagnostic-only and does not mutate canonical source or target state.
- Added deterministic Devtool validation jobs for aria2 ownership/reconciliation faults and backend migration crash faults.

## Audit-loop findings fixed

The audit loop identified a crash-boundary gap in daemon runtime rebinding: reserving a generation and binding the recovered runtime/GID in separate SQLite transactions could leave an incomplete current generation if the process died between writes. The final implementation fixes this with `RebindBackendTaskGeneration`, a single transaction that atomically advances generation, binds ownership, updates the download pointer, and terminalizes the historical owner.

## Validation evidence

The final audit loop covered:

- `go test ./engine/backends/aria2 -run 'Test(Ownership|Activation|Stale|Daemon|Reconciliation)' -count=1`
- `go test ./engine/backends/migration -run TestMigration -count=1`
- `go test ./engine/...`
- `go vet` over the scoped `xgo_backends` package set
- `tools/xgo/audit_backends_contract.py`
- `xgo-backends-audit` modes: post-replay, ftp, metalink, compatibility, selection, aria2-rpc

No overlay lifecycle hook mutates the tooling setup.
