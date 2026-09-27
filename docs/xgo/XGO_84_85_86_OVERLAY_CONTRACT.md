# XGO-84..86 Overlay Contract

## Identity

- Overlay ID: `XGO-84..86`
- Wave: 12 — Differential audit, adversarial testing, and final removal seal
- Owning Devtool target: `xgo_seal`
- Merge decision: XGO-84, XGO-85, and XGO-86 share one seal-only ownership boundary and form a strict ordered chain: adversarial matrix → parser fuzz campaign → performance/resource budget. XGO-87 is intentionally excluded because ABI/provenance compatibility is a separate release-contract boundary.
- Prerequisite: XGO-81/82/83 v2 committed and its `xgo_seal` nodes present.
- Validation: target workflow is authoritative; overlay requires validation with `failure_action = pause`; `validation.tasks` is intentionally absent.

## Capability IDs changed

No canonical donor capability changes. This overlay hardens already-migrated capabilities and adds Wave-12 seal evidence. Existing capability IDs exercised include security/request/HTTP/retry/finalization/media/ops/Android/Desktop authority surfaces already represented by the capability ledger and fixture registry.

## Donor files/tests studied

No new donor authority is imported. XGO-84 is explicitly post-parity adversarial hardening and XGO-85/86 are migration-seal campaigns. The implementation consumes the previously sealed donor/capability mapping indirectly through the existing subsystem auditors and fixtures rather than introducing a second donor-specific behavior model.

## Exact files modified

- `.devtool.toml`
- `tools/xgo/audit_seal.py`

## Exact files added

- `docs/xgo/XGO_84_85_86_OVERLAY_CONTRACT.md`
- `docs/xgo/XGO_84_85_86_IMPLEMENTATION_REPORT.md`
- `engine/api/v1/fuzz_test.go`
- `engine/media/fuzz_test.go`
- `engine/ops/fuzz_test.go`
- `engine/security/header/fuzz_test.go`
- `engine/security/urlpolicy/fuzz_test.go`
- `engine/transfer/http/fuzz_test.go`
- `engine/transfer/metalink/fuzz_test.go`
- `engine/seal/performance_test.go`
- `engine/testdata/seal/fuzz-policy.json`
- `engine/testdata/seal/performance-budget.json`

## Exact files deleted

None.

## Schema / wire changes

- Schema migration: none.
- Wire protocol change: none.
- ABI change: none.

## New invariants

1. `INV-XGO-ADVERSARIAL-001`: every required Wave-12 network/storage/media adversarial scenario maps to an explicit typed expected outcome and to passing executable evidence.
2. `INV-XGO-ADVERSARIAL-002`: an adversarial seal report containing planted credential/token material is a hard failure.
3. `INV-XGO-FUZZ-001`: every required attacker-controlled parser category has a bounded fuzz target and each target runs for at least the committed policy duration.
4. `INV-XGO-FUZZ-002`: a fuzz crash/hang blocks validation; permanent regression corpus files under `testdata/fuzz` are inventoried in the seal evidence.
5. `INV-XGO-PERF-001`: all committed engine-scale workloads remain below the portable wall-clock ceilings in `performance-budget.json`.
6. `INV-XGO-PERF-002`: scheduler scale remains qualified at 2,500 queued jobs and Android/Desktop host-authority/JNI/startup contract probes remain green while engine performance is measured.
7. `INV-XGO-SEAL-DAG-001`: XGO-84/85/86 execute only after XGO-83 and in the order `adversarial_matrix → fuzz_campaign → performance_budget`.

## Preserved invariants

- One Go engine remains authoritative for canonical execution/domain state.
- Hosts remain side-effect and projection surfaces rather than second engines.
- Generation/revision/ownership fencing remains authoritative.
- Publication remains verification-gated and crash-safe.
- Secret-bearing material remains outside diagnostic/report output.
- Existing XGO-81/82/83 nodes and their evidence remain unchanged and precede this overlay.

## Known intentional breakage

None.

## Measurement scope

XGO-86 enforces portable engine-scale ceilings directly in `xgo_seal`. Android and Desktop host-resource evidence in this target is intentionally limited to executable JNI/service/authority/startup contract probes because `xgo_seal` runs in a native Termux/shared validation environment rather than inside a physical Android instrumentation session or a Desktop GUI process. GATE-12 must requalify physical host CPU/wakeup/RSS/startup metrics on their real hosts; the seal report records this requirement explicitly instead of fabricating those measurements.

## Devtool target-local jobs added

### `adversarial_matrix`

Runs existing subsystem auditors and focused fault tests to cover:

- DNS rebinding, public→private redirects, credential redirect stripping/denial, TLS/proxy policy, wrong ranges, validator changes, retryable 429/503 behavior;
- disk/preallocation failure, short response, staging/recovery faults, durable SQLite state, and ambiguous provider publication receipts;
- HLS sliding windows/key/gap/protection failures, DASH execution, FFmpeg crash/invalid output, media credential scope;
- legacy import failures and secret-output scanning.

Produces `.devtool/reports/xgo/seal/adversarial-matrix.json`.

### `fuzz_campaign`

Runs ten Go fuzz targets spanning the nine roadmap parser categories:

- URL/header;
- Content-Range;
- Content-Disposition;
- HLS;
- DASH XML;
- Metalink;
- CaptureEnvelope;
- wire protocol;
- legacy import.

The committed minimum is one second per target and all harnesses cap input size before parsing. Produces `.devtool/reports/xgo/seal/fuzz-campaign.json`.

### `performance_budget`

Enforces committed portable ceilings for:

- 64 MiB single transfer path;
- 64 MiB segmented path;
- 5,000 small downloads;
- 10,000 HLS fragments;
- 10,000 DASH timeline entries;
- 100,000 imported history rows;
- 2,500 queued jobs;
- 1,000 recovery-startup downloads.

It also executes Android JNI/scheduler/legacy-authority probes and Desktop engine-host/e2e/legacy-authority probes as host-resource contract proxies. Produces `.devtool/reports/xgo/seal/performance-budget.json`.

## Workflow DAG changes

`xgo_seal#validate` becomes:

`bootstrap_contract → differential_runner → state_machine_fuzz → full_crash_matrix → adversarial_matrix → fuzz_campaign → performance_budget`

No cross-target workflow reference is introduced.

## Domain evidence outputs

- `.devtool/reports/xgo/seal/adversarial-matrix.json`
- `.devtool/reports/xgo/seal/fuzz-campaign.json`
- `.devtool/reports/xgo/seal/performance-budget.json`

## Exit criteria

- adversarial report has all 21 required scenarios, typed expected outcomes, and all four hard invariants true;
- fuzz report covers all nine categories, runs all ten targets at/above policy duration, and has no crash/hang;
- performance report contains all eight scale workloads and no budget violation;
- Android/Desktop host contract proxies pass;
- `xgo_seal` topology recognizes seven ordered workflow nodes;
- changed Go/Python sources compile and focused tests pass;
- XGO-87 remains untouched.
