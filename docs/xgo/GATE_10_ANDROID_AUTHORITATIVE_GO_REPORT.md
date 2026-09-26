# GATE-10 Android authoritative-Go gate closure

## Scope

GATE-10 closes the Android host migration after XGO-66 through XGO-75. The gate target is `xgo_gate_android`. This corrected gate intentionally does **not** replay every already-closed subsystem workflow. It validates the boundary being closed: lightweight Go/ABI/platform sanity, capability/fixture/topology integrity, the complete Android authority/reconnection/import/legacy-cleanup suite, and the repository's real Android build/test/lint/instrumentation seal.

## Why the gate was reduced

The first full composition expanded foundation -> store -> security -> transfer -> backends -> scheduler -> media -> ops -> Android and then the Android runner, producing roughly 125 steps. That was valid but redundant: most of those subsystem matrices had already been qualified by their own milestones. The corrected GATE-10 keeps final integration confidence while avoiding historical roadmap replay.

## Gate workflow

The lean `xgo_gate_android#validate` workflow is:

1. Go formatting check across `engine/`.
2. Full `go test ./engine/...`.
3. Full `go vet ./engine/...`.
4. Platform-broker matrix.
5. Native C-ABI harness.
6. Capability-ledger audit.
7. Fixture lint.
8. Fixture secret scan.
9. Devtool topology audit.
10. `target:xgo_android#validate` (the 12-node Android authority/reconnection/import/legacy-cleanup suite).
11. `target:xdm_android#validate` (`android_full_seal`, using the Android runner's native validation planner).

This preserves the actual final integration seal while removing recursive execution of the closed store/security/transfer/backends/scheduler/media/ops matrices.

## Other fixes retained from GATE-10 v2

- `xdm_android` build tasks are simplified to `clean` plus `assembleDebug`; pinned runtime installation remains packaging-owned.
- Android validation uses one worker / one CPU / no daemon for the Termux qualification path.
- XGO topology audit models Devtool MP05 primary-runner fallback semantics for `xdm_android#validate`.
- Historical Android validators are rebased onto the XGO-70..75 Go-authority architecture rather than the deleted Kotlin authority paths.
- The FF04 runtime-builder resume test uses the current compiler-identity payload.
- Latent formatting defects in `engine/androidhost/bridge.go`, `packaging.go`, and `packaging_test.go` are normalized so the gate can pass its own gofmt preflight.

No product behavior or new authority path is introduced by this gate.

## Gate policy going forward

Milestone gates should validate the boundary being closed plus a curated regression set. They should not recursively replay every previous milestone unless a specific dependency or regression risk requires it.

## Gate status

GATE-10 closes only when Devtool applies this overlay with required validation and the lean `xgo_gate_android` workflow, including `android_full_seal`, completes with zero diagnostics/warnings. GATE-10 remains separate from XGO-76.
