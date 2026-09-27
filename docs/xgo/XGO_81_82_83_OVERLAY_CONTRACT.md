# XGO-81..83 overlay contract

## Metadata

- Overlay ID: `xgo_81_82_83_differential_state_crash_campaign_v2`
- Wave: 12 — differential audit, adversarial testing, and final removal seal
- Roadmap items: XGO-81, XGO-82, XGO-83
- Prerequisites: XGO-80 and GATE-11 closed
- Capability IDs changed: none; all current capability IDs are audited/classified
- Donor files/tests studied: all entries pinned by `engine/docs/donor-map.yaml`; live donor checkout is re-fingerprinted during XGO-81 validation
- Schema migration: none
- Wire protocol change: none
- Devtool target: `xgo_seal`
- Validation: required, non-deferred, `failure_action = pause`, maximum warnings 0
- Merge decision: XGO-81/82/83 are merged because they share one `xgo_seal` ownership/validation boundary and the outputs form one ordered campaign. XGO-84 is intentionally excluded. GATE-12 remains a separate future wave-closing overlay.
- v2 correction: donor aggregate serialization is byte-for-byte aligned with the authoritative Wave 0 `audit_foundation._aggregate` contract (sorted tab-delimited rows with a trailing newline). v1 omitted the final newline and therefore rejected a donor checkout whose 80 individual pinned fingerprints were all valid.

## Exact files added

- `docs/xgo/XGO_81_82_83_OVERLAY_CONTRACT.md`
- `docs/xgo/XGO_81_82_83_IMPLEMENTATION_REPORT.md`
- `engine/seal/campaign.go`
- `engine/seal/state_machine_test.go`
- `engine/seal/crash_matrix_test.go`
- `engine/testdata/seal/differential-classifications.json`
- `engine/testdata/seal/state-machine-seeds.json`
- `tools/xgo/audit_seal.py`

## Exact files modified

- `.devtool.toml`

## Exact files deleted

- none

## XGO-81 — legacy differential runner and behavior classification

`differential_runner` loads the capability ledger, neutral fixture registry, fixture payloads, donor map, and explicit differential classification manifest. It:

1. requires exactly one classification for every capability;
2. binds every classification to the capability's neutral fixture;
3. verifies the semantic `expected` payload fingerprint and canonical-behavior fingerprint so behavior drift requires reclassification;
4. verifies the untouched donor checkout against every pinned donor size/SHA-256 and aggregate fingerprint;
5. records donor Git identity and legacy donor test-source evidence per capability;
6. accepts only these dispositions: Android preserved, Desktop preserved, merged improvement, bug fixed, intentionally removed, or regression;
7. blocks immediately on any `regression` result;
8. blocks if a capability is unclassified, fixture/donor linkage drifts, or an intentionally removed classification is not reflected as `INTENTIONALLY_REMOVED` in the ledger;
9. emits a stable deterministic fingerprint over the sorted semantic report.

Current explicit classification coverage is all 98 capabilities: 38 Android-preserved, 12 Desktop-preserved, 48 merged/redesigned improvements, zero regressions.

## XGO-82 — state-machine property campaign

`state_machine_fuzz` validates the required operation vocabulary:

- start
- pause
- resume
- cancel
- retry
- migrate backend
- backend loss
- crash
- recover
- verify
- publish
- delete

The campaign uses the real Go domain transition types for download lifetime, attempt lifetime, verified artifact publication, typed backend failure, and attempt-generation fencing. It runs:

- 12 permanent regression-seed sequences from `engine/testdata/seal/state-machine-seeds.json`;
- 256 deterministic pseudo-random sequences × 96 operations;
- the relevant domain, SQLite authority, backend migration and recovery suites;
- the Go race detector on `engine/seal`, `store/sqlite`, `backends/migration`, and `recovery` when the host Go target supports `-race`.

On hosts where Go does not support `-race` (notably native Android/Termux toolchains), the target performs a mandatory 20× deterministic stress fallback and records the limitation. GATE-12 still requires a real race-capable host before final closure.

### New invariant IDs

- `INV-XGO-OWNERSHIP-001` — stale attempt generations cannot mutate/complete current authority.
- `INV-XGO-GENERATION-001` — attempt generation is monotonic.
- `INV-XGO-TERMINAL-001` — terminal attempts cannot transition back into mutable execution.
- `INV-XGO-VERIFY-001` — download completion cannot exist without a current verified artifact.
- `INV-XGO-PUBLISH-001` — publication cannot exist without verified artifact identity.
- `INV-XGO-RECOVERY-001` — crash/recovery cannot synthesize or erase committed publication truth.
- `INV-XGO-DELETE-001` — deleted download lifetime is immutable.

Any newly failing generated seed is reported by stable `seed-NNN` identity and must be promoted into the permanent seed corpus before the campaign may be considered closed.

## XGO-83 — full crash/durability matrix

`full_crash_matrix` composes existing real fault-injection/reopen evidence plus a new durable ownership reopen test. It proves coverage for:

1. after generation reserve;
2. after staging create;
3. after ownership claim;
4. after backend task create;
5. before activation;
6. after activation;
7. during block write;
8. after fsync;
9. before checkpoint commit;
10. after checkpoint commit;
11. after verification;
12. after artifact creation;
13. after platform publication;
14. before engine publication commit;
15. during backend migration;
16. during HLS/DASH fragment execution/commit;
17. during/after FFmpeg.

The node reuses authoritative engine tests/audits rather than duplicating persistence semantics:

- `engine/seal` ownership durability reopen matrix;
- `xgo-store-audit --mode durable-gate`;
- migration crash recovery at every durable boundary;
- recovery coordinator restart cases;
- media fragment faults;
- HLS execution durability/restart behavior;
- DASH execution durability/restart behavior;
- FFmpeg execution/publication crash recovery.

Required matrix conclusions:

- expected recovery state at every injection point;
- no duplicate publication;
- no stale completion;
- no unverified resume.

## Preserved invariants

- EXO/Devtool remains the execution authority; JSON domain reports are supporting evidence only.
- The untouched donor checkout is never modified by the overlay.
- Android/Desktop remain host surfaces and are not reintroduced as canonical engines.
- Existing capability IDs and neutral fixture IDs remain stable.
- No new cross-target recursion is introduced.
- No schema or wire compatibility surface changes.

## Known intentional breakage

None.

## Devtool DAG changes

Target-local jobs added:

- `differential_runner`
- `state_machine_fuzz`
- `full_crash_matrix`

`xgo_seal#validate` becomes:

```text
bootstrap_contract
    -> differential_runner
    -> state_machine_fuzz
    -> full_crash_matrix
```

Cross-target workflow references: none.

## Execution-environment requirements

- authoritative target environment remains `native-termux`;
- Python 3, Git and Go must be available;
- the donor checkout resolves from the pinned donor-map default (`../xdm`) and must be a clean Git worktree;
- race-detector execution is mandatory when the active Go OS/arch supports it; unsupported native hosts run deterministic stress and defer the actual race-host proof to GATE-12.

## Domain evidence outputs

- `.devtool/reports/xgo/seal/bootstrap.json`
- `.devtool/reports/xgo/seal/differential.json`
- `.devtool/reports/xgo/seal/state-machine.json`
- `.devtool/reports/xgo/seal/crash-matrix.json`

## Exit criteria

The overlay may commit only when `xgo_seal#validate` passes through Devtool with zero warnings. Specifically:

- all capabilities are classified and no classification is `regression`;
- donor fingerprint verification passes;
- the differential report is capability-linked and deterministic;
- all permanent and generated property sequences satisfy every `INV-XGO-*` invariant;
- race/stress requirements are satisfied for the current host contract;
- all 17 crash/durability boundaries have passing recovery evidence;
- duplicate publication, stale completion and unverified resume checks remain false.
