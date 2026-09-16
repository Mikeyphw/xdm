# RM01 — Transfer Core / Recovery Integrity

Roadmap position: **overlay 1 of 4**.

## Problem closed

The transfer pipeline could prepare Native publication state before durable ownership selected the real attempt generation. A request with generation `0` (or another stale value) therefore created staging/publication state under that generation, while ownership later advanced to a different generation. The transfer could finish all payload bytes and then fail while promoting the completed artifact with `Publication generation requires a positive attempt generation`.

The same ordering hazard existed in backend migration: the target backend could prepare its artifacts before ownership transfer allocated the target generation.

## RM01 invariants

RM01 makes the generation a pre-execution fence rather than a post-preparation fact:

1. `BackendOwnershipStore.reserveGeneration()` reserves a positive generation before backend preparation.
2. The exact reserved generation is put into `DownloadRequest` before `prepare()` / `prepareForAdoption()`.
3. `claim()`, `adopt()`, and `transfer()` consume that exact generation.
4. Backend `add()` may create a non-writing task, but payload activation is fenced behind a caller callback.
5. `TransferExecutionRuntime` durably persists the exact owned generation into the Download row before activation.
6. Migration reserves the target generation before target preparation and persists the transferred generation before target activation.
7. Reconciled Native staging artifacts can be adopted in place. Their physical staging/checkpoint/journal identity is preserved while the checkpoint and future publication transaction are rebound to the newer generation.
8. A failed final save can retry publication from the completed staging artifact without performing the network transfer again.

Generation gaps are allowed. A process can die after reservation but before ownership is written; correctness requires uniqueness and fencing, not contiguous values.

## Recovery behavior

A reconciled Native artifact is adoptable only when the ownership record proves:

- same download and destination,
- Native artifact format,
- same backend installation identity,
- resumable reconciliation state,
- exact physical artifact identity,
- and a newer reserved generation.

On adoption, XDM does **not** copy or rename payload bytes merely to advance generation. The existing Native checkpoint must still identify the old owned generation and source request. It is atomically rewritten to the newly attached ownership generation before activation starts.

If the pre-activation persistence callback fails, backend activation never runs. The startup task is detached where possible and ownership is recorded as a reconciled resumable artifact rather than allowing an unbound writer to continue.

## Regression coverage

RM01 adds/extends executable tests for:

- generation reservation before prepare/add/activation,
- persistence failure before activation,
- runtime Download-generation binding before backend activation,
- migration target generation fencing,
- Room reservation exactness across claim/adoption,
- Native reconciled-artifact adoption without payload copy,
- and the retained final-save recovery test that retries publication after the network server is gone.

The static RM01 seal is `tools/validate-rm01-transfer-core-recovery.py`, exposed as Gradle task `:app:verifyRm01TransferCoreRecoveryIntegrity` and carried into `tools/run-final-release-gate.sh`.

## Validation policy

RM01 is an intermediate overlay. Its artifact is intended for the roadmap's `--no-validate` apply flow, followed by targeted validation. The final full Android/release matrix remains Overlay 4 of 4.
