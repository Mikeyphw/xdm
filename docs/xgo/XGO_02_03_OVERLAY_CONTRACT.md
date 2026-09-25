# XGO-02 + XGO-03 overlay contract

## Covered roadmap items

- XGO-02 — Extract language-neutral donor fixtures
- XGO-03 — Complete Devtool target/workflow DAG topology

These adjacent foundation items are combined because XGO-02 is specification-heavy but small in executable implementation, both are owned by `xgo_foundation`, and XGO-03 consumes the fixture/audit infrastructure directly.

## Prerequisites

- XGO-00+01 has been applied successfully.
- `xgo_foundation` already exists in `.devtool.toml`.
- `~/Code/xdm` remains the clean donor checkout.
- Apply in `~/Code/xgo-unified-go-engine`.

## Devtool ownership

Artifact target: `xgo_foundation`

The authoritative validation DAG becomes:

```text
foundation_tests
      ↓
donor_audit
      ↓
capability_ledger_audit
      ↓
fixture_lint
      ↓
fixture_secret_scan
      ↓
devtool_topology_audit
```

The artifact declares required validation, uses `failure_action = pause`, and intentionally omits `validation.tasks`.

All XGO subsystem and gate targets are explicitly pinned to `execution_environment = "native-termux"` during bootstrap. This prevents ordinary future XGO overlays from inheriting the repository-global `auto` environment. Android/Desktop targets are intentionally promoted to their eventual runner/environment only in their reconnection waves.

## Files added

- `engine/docs/fixture-schema.md`
- `engine/testdata/README.md`
- `engine/testdata/fixture-registry.json`
- 85 language-neutral fixture documents under `engine/testdata/<category>/`
- `tools/xgo/audit_fixtures.py`
- `tools/xgo/audit_topology.py`
- unit tests for both auditors
- XGO-02+03 report/contract

## Files modified

- `.devtool.toml`

## New invariants

1. Every initial capability ledger entry owns exactly one stable fixture ID.
2. Every fixture references known capability and donor IDs.
3. High-risk areas have at least one concrete input/decision fixture.
4. Fixture payloads may not contain probable literal secrets.
5. Every planned XGO subsystem target exists before its first implementation overlay.
6. Every XGO target has an explicit non-empty `validate` workflow.
7. Gate targets compose target workflows, never foreign target-local jobs.
8. Bootstrap XGO execution is native-Termux unless a later overlay deliberately changes a target's environment.
9. No custom XGO validation wrapper or parallel generic evidence system exists.

## Intentional limitations

- Future subsystem targets use a `command` bootstrap job until their native runner becomes valid.
- They do not claim that unimplemented subsystem code already exists.
- Gate workflows are topology scaffolding only until their constituent targets gain real implementation validation.
- XGO-04 promotes `xgo_foundation` to the Go runner after `engine/go.mod` exists.

## Exit criteria

- `xgo_foundation#validate` executes all six nodes successfully.
- fixture registry contains exactly 85 unique fixtures.
- every ledger `fixture:` reference resolves.
- donor/capability/fixture links have no dangling IDs.
- secret scan reports zero findings.
- all 11 subsystem targets and 13 gate targets load.
- all XGO bootstrap targets resolve to `native-termux`.
- topology audit finds no unknown refs or local DAG cycles.
