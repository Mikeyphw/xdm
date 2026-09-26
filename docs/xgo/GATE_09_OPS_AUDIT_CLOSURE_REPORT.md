# GATE-09 operational core audit closure

## Scope

This closure audits GATE-09 after XGO-62 through XGO-65 landed. The gate target is `xgo_gate_ops`, which must validate settings ownership, secret-broker/redaction behavior, bounded diagnostics/support snapshots, and idempotent legacy import recovery on top of the full upstream engine chain.

## Gaps found

1. `xgo_gate_ops` composed only `foundation -> store -> ops`, skipping the security, transfer, backend, scheduler, and media checkpoints that are part of the cumulative engine chain before operational-core validation.
2. The GATE-09 promise for secret scanning across diagnostic DB/events/support snapshot was covered by separate component tests, but not by an explicit gate-level validation node.
3. The import crash/retry suite existed as `import_faults`, but the gate lacked a combined checkpoint that ties crash recovery and duplicate idempotency to support-snapshot secret scanning.

## Fix

- Updated `xgo_gate_ops#validate` to compose `foundation -> store -> security -> transfer -> backends -> scheduler -> media -> ops`.
- Added `ops_gate_suite` to `xgo_ops#validate`. It injects planted secrets through diagnostic event context and support-snapshot logs, verifies the support snapshot scanner, then exercises crash-after-stage recovery plus duplicate import idempotency.

## Validation evidence

- `settings_audit`: PASS
- `secret_scan`: PASS
- `diagnostics_stress`: PASS
- `import_faults`: PASS
- `ops_gate_suite`: PASS
- `xgo_ops` topology: PASS, 6 validation nodes
- `xgo_gate_ops` topology: PASS, 8 validation nodes
- fixture lint: PASS
- fixture secret scan: PASS
- capability ledger audit: PASS
- `go test ./engine/ops ./engine/cmd/xgo-ops-audit`: PASS
- `go test ./engine/...`: PASS
- `go vet ./engine/...`: PASS

## Gate status

Applying this overlay with `xgo_gate_ops` closes GATE-09 when Devtool reports zero diagnostics and all validations pass.
