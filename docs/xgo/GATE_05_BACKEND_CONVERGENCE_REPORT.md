# GATE-05 — Backend convergence report

## Scope

GATE-05 closes Wave 5 after XGO-36 through XGO-43. It does not introduce new backend behavior. Its purpose is to run the cumulative backend convergence target and prove that the request/body, protocol, metadata, router, aria2 RPC, durable ownership, reconciliation, and migration boundaries are wired into one authoritative validation graph.

## Covered roadmap items

- XGO-36 — replayable POST/body download semantics.
- XGO-37 — FTP/FTPS transfer adapter.
- XGO-38 — Metalink parser and canonical request expansion.
- XGO-39 — exact backend compatibility contract.
- XGO-40 — deterministic backend selection policy.
- XGO-41 — generic aria2 JSON-RPC client.
- XGO-42 — aria2 durable task ownership and runtime identity.
- XGO-43 — aria2 reconciliation and backend migration.

## Gate target

The overlay selects `xgo_gate_backends`. The gate target remains composition-only and delegates through the cumulative target graph:

```text
xgo_foundation -> xgo_store -> xgo_security -> xgo_transfer -> xgo_backends
```

Specialized backend evidence remains owned by `xgo_backends`; the gate does not reach into private target-local jobs directly.

## Required cumulative evidence

The cumulative backend validation graph proves:

- request body replay and POST retry/redirect/range-resume policy;
- FTP/FTPS auth, resume, disconnect/retry and checksum finalization handoff;
- Metalink normalization into canonical network intents;
- router/executor compatibility agreement and deterministic backend selection;
- aria2 JSON-RPC correlation, malformed reply, timeout, task control and option handling;
- generation-scoped aria2 runtime/GID ownership;
- daemon restart reconciliation without stale writer authority;
- native-to-aria2 and aria2-to-native backend migration with crash-boundary recovery.

## Gate result expectation

A successful Devtool run against `xgo_gate_backends` closes GATE-05 and completes Wave 5. Wave 6 must not start automatically; the next implementation window begins at XGO-44 only after explicit user confirmation.
