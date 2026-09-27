# XGO-89 overlay contract — capability-ledger closure and final audit loop

Overlay ID: `XGO-89`
Wave: `12`
Prerequisites: `XGO-81` through `XGO-88` complete; XGO-88 single-authority seal green.
Target: `xgo_seal`
Validation required: yes
Failure action: `pause`
Validation task override: absent; `xgo_seal#validate` remains authoritative.

## Outcome

After XGO-89, every capability in the canonical ledger has a final disposition and an explicit executable evidence binding. The closure audit independently revalidates the ledger, fixture registry, donor references, differential classifications, evidence commands, and active-host single-authority architecture. Any future unresolved gap blocks XGO-89 and requires an explicit `XGO-RMxx` remediation overlay plus regression evidence before the affected gate may pass again.

## Capability IDs changed

No capability behavior is redefined. All 98 existing capability records gain an `evidence_id` that binds the implemented behavior to one of twelve executable evidence groups. Current disposition is 98 `IMPLEMENTED`, 0 `INTENTIONALLY_REMOVED`, and 0 non-final entries.

## Donor files/tests studied

- `engine/docs/capability-ledger.yaml`
- `engine/docs/donor-map.yaml`
- `engine/testdata/fixture-registry.json`
- `engine/testdata/seal/differential-classifications.json`
- `engine/testdata/seal/single-authority-policy.json`
- XGO subsystem auditors under `engine/cmd/xgo-*-audit`
- Android authority surfaces used by `xgo-android-audit`
- Desktop authority surfaces used by `tools/xgo/audit_desktop.py`
- XGO-81 differential, XGO-82 state-machine, XGO-83 crash, XGO-84 adversarial, XGO-85 fuzz, XGO-86 performance, XGO-87 ABI/provenance, and XGO-88 single-authority contracts

## Exact files added

- `engine/testdata/seal/capability-closure-policy.json`
- `docs/xgo/XGO_89_OVERLAY_CONTRACT.md`
- `docs/xgo/XGO_89_IMPLEMENTATION_REPORT.md`

## Exact files modified

- `.devtool.toml`
- `engine/docs/capability-ledger.yaml`
- `tools/xgo/audit_seal.py`

## Exact files deleted

None.

## Schema migration number

None.

## Wire protocol change

None.

## New invariants

1. `INV-XGO-CLOSURE-001`: capability status is final: only `IMPLEMENTED` or `INTENTIONALLY_REMOVED` is allowed at XGO-89.
2. `INV-XGO-CLOSURE-002`: every implemented capability binds exactly one neutral fixture and one known executable evidence group.
3. `INV-XGO-CLOSURE-003`: fixture registry coverage is exact; missing, duplicate, extra, or donor-drifted fixtures fail closure.
4. `INV-XGO-CLOSURE-004`: differential classification coverage is exact and `regression` is forbidden.
5. `INV-XGO-CLOSURE-005`: every intentional removal, if one is ever introduced, must classify as `intentionally_removed`, contain a non-empty `removal_rationale`, and must not claim implementation evidence.
6. `INV-XGO-CLOSURE-006`: all evidence groups referenced by implemented capabilities execute successfully during the closure node.
7. `INV-XGO-CLOSURE-007`: the closure node reruns the XGO-88 source-level single-authority scan directly; it does not accept the earlier XGO-88 report as proof.
8. `INV-XGO-CLOSURE-008`: known-invalid closure mutations must be rejected by the validator itself.
9. `INV-XGO-CLOSURE-009`: an open gap is a blocking condition and requires an explicit `XGO-RMxx` remediation contract before rerunning the affected gate.

## Preserved invariants

All XGO-81 through XGO-88 invariants remain unchanged. XGO-89 adds evidence bindings and a fresh closure audit; it does not change engine semantics, storage schema, host wire protocol, ABI, publication behavior, or authority ownership.

## Known intentional breakage

None.

## Devtool target

`xgo_seal`

## Target-local jobs added/changed

Added `capability_closure_audit`:

```text
python3 tools/xgo/audit_seal.py capability-closure \
  --output .devtool/reports/xgo/seal/capability-closure.json
```

## Workflow DAG nodes/dependencies changed

`xgo_seal#validate` appends:

```text
single_authority_scan -> capability_closure_audit
```

The resulting seal workflow has ten ordered validation nodes.

## Cross-target workflow references

None added.

## Execution-environment requirements

- Python 3
- Go toolchain
- current repository source tree
- host source files needed by the Android/Desktop contract auditors
- full Android Gradle/package/device and Desktop .NET package qualification remains part of GATE-12
- `go test -race ./...` remains a GATE-12 requirement on a race-supported Go host; Android/Termux does not falsely claim it

## Domain evidence outputs

- `.devtool/reports/xgo/seal/capability-closure.json`

The report records final status counts, all 98 capability bindings, evidence-group command results, fresh architecture scan results, negative closure self-tests, final engine test/vet evidence, a deterministic closure fingerprint, and `open_gap_count`.

## Exit criteria

- `xgo_seal` topology reports ten validation nodes.
- all 98 capabilities have final disposition.
- implemented capabilities have exact fixture and evidence bindings.
- all 12 referenced evidence groups pass.
- fixture/donor/classification cross-references have zero gaps.
- fresh single-authority architecture scan passes without reading XGO-88's report.
- three deliberate invalid closure mutations are rejected.
- full Go engine test and vet pass.
- closure report says `open_gap_count = 0` and `remediation_required = false`.

## GATE-12 boundary

XGO-89 completes Wave 12 implementation. It does not itself claim GATE-12. The final gate remains `xgo_gate_final`, which composes cross-host qualification and the complete `xgo_seal` workflow, including race-capable-host, Android, Desktop, packaging, and final release evidence.
