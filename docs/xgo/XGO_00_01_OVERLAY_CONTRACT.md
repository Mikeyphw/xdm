# XGO-00 + XGO-01 overlay contract

## Covered roadmap items

- XGO-00 — Freeze donor baseline and bootstrap XGO Devtool execution
- XGO-01 — Build capability ledger at behavioral granularity

These adjacent items are intentionally combined because XGO-00 is small, both are owned by `xgo_foundation`, and they share one coherent validation boundary.

## Prerequisites

- Apply in `~/Code/xgo-unified-go-engine`.
- The sibling donor checkout exists at `~/Code/xdm`.
- The migration worktree is clean before apply.
- The donor checkout has no tracked modifications.

## Devtool ownership

Target: `xgo_foundation`

Target-local jobs:

- `foundation_tests`
- `donor_audit`
- `capability_ledger_audit`

Workflow:

```text
foundation_tests
      ↓
donor_audit
      ↓
capability_ledger_audit
```

The artifact declares required validation with `failure_action = pause` and intentionally does not declare `validation.tasks`.

## Files added

- `engine/docs/donor-baseline.md`
- `engine/docs/donor-map.yaml`
- `engine/docs/capability-ledger.yaml`
- `tools/xgo/audit_foundation.py`
- `tools/xgo/tests/test_audit_foundation.py`
- `docs/xgo/XGO_00_01_FOUNDATION_REPORT.md`
- `docs/xgo/XGO_00_01_OVERLAY_CONTRACT.md`

## Files modified

- `.devtool.toml`

## State/schema/API changes

None. This overlay does not introduce Go domain code, database state, ABI, or wire schema.

## New invariants

1. The selected primary donor sources are content-addressed and validated against the untouched sibling donor checkout.
2. The real donor Git HEAD and branch are recorded by validation instead of invented from an archive without `.git`.
3. Every initial capability has a stable ID, donor strategy, canonical target behavior, future overlay, and planned test/fixture identity.
4. Every pinned donor file is referenced by at least one capability.
5. XGO validation begins through Devtool target-local jobs and an explicit target workflow; no XGO-specific validation wrapper is introduced.

## Intentional breakage

None expected. Existing XDM Android/Desktop targets remain untouched.

## Exit criteria

- `xgo_foundation#validate` succeeds.
- Donor fingerprint has zero missing or mismatched files.
- Donor checkout is a Git worktree with zero tracked modifications.
- Capability ledger has at least 80 unique capabilities.
- Every donor map entry is referenced.
- Every capability references an existing donor or explicitly uses `new_design`.
- Target overlay and planned fixture identifiers are structurally valid.
