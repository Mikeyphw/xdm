# XGO-81..83 implementation report

## Result

This overlay activates the first three Wave 12 seal campaigns under the existing `xgo_seal` target:

1. donor-vs-Go differential classification;
2. deterministic state-machine property testing;
3. explicit crash/durability recovery coverage.

The implementation intentionally does not include XGO-84 or GATE-12.

### v2 donor aggregate correction

The first artifact revision used the correct donor IDs, paths, per-file SHA-256 values, and sizes, but serialized the aggregate rows without the trailing newline required by the existing Wave 0 donor-freeze contract. The real donor validation therefore reached the aggregate check only after every individual donor fingerprint had passed, then produced `9523620a...` instead of the pinned `e1170e28...`. v2 uses the exact `audit_foundation._aggregate` byte representation and preserves all fail-closed donor checks.

## Differential classification

All 98 capabilities in the current ledger receive one explicit classification bound to their existing neutral fixture and donor references. Classification data pins both the fixture's semantic `expected` payload and the ledger's canonical behavior text by SHA-256, so a future semantic edit cannot silently inherit an old classification.

The live Devtool validation node re-verifies every donor-map source file against the untouched donor checkout, records donor Git HEAD/branch and test-source evidence, and blocks on any regression/unclassified capability.

A synthetic donor mirror was used only to exercise the runner implementation in the artifact-authoring container because that container does not have the user's untouched `~/Code/xdm` Git checkout. This is not recorded as donor validation evidence; the real overlay validation requires the actual donor checkout and pauses on mismatch.

## State-machine campaign

The new `engine/seal` campaign uses the real Go domain state objects instead of a string-only mock. Permanent regression fixtures and generated deterministic sequences exercise arbitrary ordering of the 12 roadmap operations and enforce seven named invariants.

Artifact-authoring evidence on Linux/amd64:

- `go test ./engine/seal -run '^TestStateMachine' -count=1` — PASS
- domain + SQLite + migration + recovery supporting suites — PASS
- `go test -race ./engine/seal ./engine/store/sqlite ./engine/backends/migration ./engine/recovery -count=1` — PASS

The seed corpus gives every permanent sequence a stable identity, while generated cases surface their numeric `seed-NNN` identity for promotion when a new regression is discovered.

## Crash/durability matrix

The matrix deliberately reuses existing production-level durability hooks and recovery tests. A new reopen test fills the ownership lifecycle boundary coverage by physically closing and reopening the SQLite authority after reserve, claim, task bind, ready and activation stages.

Artifact-authoring evidence:

- ownership durability reopen matrix — PASS
- migration crash recovery at every durable boundary — PASS
- recovery restart suite — PASS
- durable store gate — PASS
- fragment fault matrix — PASS
- HLS execution/restart matrix — PASS
- DASH execution/restart matrix — PASS
- FFmpeg crash/publication recovery matrix — PASS

The generated crash report confirms all 17 required roadmap injection points and the four XGO-83 conclusions: expected recovery everywhere, no duplicate publication, no stale completion, and no unverified resume.

## Devtool integration

`xgo_seal#validate` now contains four explicitly ordered nodes:

```text
bootstrap_contract
  -> differential_runner
  -> state_machine_fuzz
  -> full_crash_matrix
```

`audit_topology.py target-ready --target xgo_seal` passes with four validation nodes.

Validation remains required and non-deferred. No `validation.tasks` override is introduced, and no Devtool bootstrap/install hook is added.

## Wave boundary

Wave 12 continues after this overlay with XGO-84 and later seal work. The final implementation overlay of the wave and **GATE-12 — Final seal** must remain separate: the last implementation overlay is not allowed to absorb the authoritative `xgo_gate_final` closure overlay.
