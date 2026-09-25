# XGO-00 + XGO-01 implementation report

This overlay starts the unified Go-engine roadmap without creating fake Go scaffolding before XGO-04.

## Delivered

- Bootstraps Devtool target `xgo_foundation`.
- Adds a target-qualified validation DAG with separate EXO nodes for unit tests, donor integrity, and capability-ledger integrity.
- Pins 80 Android/Desktop donor implementation and contract-test files by SHA-256.
- Adds 85 initial granular migration capabilities.
- Records donor Git HEAD dynamically from the real `~/Code/xdm` checkout.
- Keeps donor validation native to Devtool; no custom XGO validation wrapper or parallel evidence framework is introduced.
- Adds `xgo` workspace group for future XGO target discovery.

## Why XGO-00 and XGO-01 are combined

Both roadmap items are small-to-moderate specification work owned by the same `xgo_foundation` target. Separating them would create a tiny baseline-only overlay with no stronger validation boundary. Combining them follows the XGO overlay-sizing rule without crossing subsystem ownership.

## Validation

The overlay manifest selects `xgo_foundation`, requires validation, uses `failure_action = pause`, and leaves `validation.tasks` absent.

The authoritative workflow is:

```text
foundation_tests -> donor_audit -> capability_ledger_audit
```

Generated domain reports are written below ignored Devtool runtime state:

```text
.devtool/reports/xgo/foundation/donor-audit.json
.devtool/reports/xgo/foundation/capability-ledger-audit.json
```

EXO remains authoritative for execution identity, logs, diagnostics, and validation transaction state.
