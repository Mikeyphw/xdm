# GATE-12 Final Seal Overlay Contract

Overlay ID: `XGO-GATE-12`
Wave: 12
Prerequisites: XGO-81 through XGO-89 applied and individually green
Capability IDs changed: none
Donor files/tests studied: Wave 12 closure evidence and host contracts only
Schema migration: none
Wire protocol change: none

## Outcome

GATE-12 is a **Wave-12-focused final gate**. It must not replay the historical XGO gate chain.

The authoritative `xgo_gate_final#validate` DAG is exactly:

1. `target:xgo_devtool_policy#validate` — cheap read-only enforcement that XGO-owned orchestration never launches raw Gradle.
2. `target:xgo_seal#validate` — all ten Wave 12 nodes, XGO-81 through XGO-89.
3. `target:xdm_android#validate` — one direct Android host build/test/package seal.
4. `target:xdm_modern#validate` — one direct Desktop .NET build/test seal.
5. `target:xgo_final_wave12#validate` — final evidence/invariant verifier.

`xgo_gate_cross_host` is intentionally absent. Older subsystem/gate workflows are not replayed merely because this is the final gate.

## New invariants

- GATE-12 first proves the XGO Devtool-execution policy, then executes the ten-node Wave 12 seal exactly once.
- GATE-12 directly requalifies each real host exactly once after the Wave 12 seal.
- Every Wave 12 report is present and has `status=pass` before final seal completion.
- Differential regressions are zero across all 98 capabilities.
- State-machine, crash, adversarial, fuzz, performance, ABI/provenance, single-authority, and capability-closure evidence retain their sealed inventories.
- Capability closure has zero open gaps and the XGO-89 final Go test/vet commands passed.
- On a race-supported Go host, the race campaign must have passed. On Termux/Android, the existing mandatory stress fallback is accepted and reported truthfully; no claim of native race execution is made.
- The final verifier fails closed on missing/stale/failed Wave 12 evidence or Gate-12 topology drift.

## Files added

- `engine/testdata/seal/gate12-policy.json`
- `tools/xgo/audit_gate12.py`
- `tools/xgo/tests/test_audit_gate12.py`
- `docs/xgo/GATE_12_FINAL_SEAL_OVERLAY_CONTRACT.md`
- `docs/xgo/GATE_12_FINAL_SEAL_IMPLEMENTATION_REPORT.md`

## Files modified

- `.devtool.toml`
- `tools/xgo/audit_seal.py`

## Devtool

Target: `xgo_gate_final`
Validation required: yes
Failure action: pause
Validation tasks: absent; target workflow is authoritative

## Exit criteria

- Devtool topology accepts `xgo_gate_final` with exactly five target-level nodes (the original four-node gate plus the RM05 v11 Devtool-execution policy preflight).
- `xgo_gate_cross_host` is not referenced by GATE-12.
- `xgo_seal` retains exactly ten Wave 12 nodes.
- XGO Python regression tests pass.
- Full Go engine tests and vet pass.
- Final verifier passes after the policy preflight and its three host/seal upstream target dependencies complete.


## Post-GATE-12 RM05 v11 amendment

The gate was originally authored and applied with four target-level nodes. The
RM05 repair cycle exposed a repository-orchestration defect: continuation jobs
invoked `./gradlew` directly instead of entering through Devtool's Android
runner. RM05 v11 therefore prepends the read-only `xgo_devtool_policy` node to
both the repair continuation and authoritative final gate. The Wave-12 seal
itself remains exactly ten nodes and is not reapplied or expanded by this
amendment.
