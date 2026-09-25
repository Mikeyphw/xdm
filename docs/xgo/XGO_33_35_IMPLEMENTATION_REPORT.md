# XGO-33..35 implementation report — native transfer integrity and Gate-04

## Scope

This overlay implements XGO-33, XGO-34 and XGO-35 and closes the Wave-4 native
transfer boundary. It changes transfer completion from the previously premature
`produced_artifact` state to durable `transport_complete`, then makes whole-file
verification, selective repair and finalization the only route to a publishable
ArtifactGeneration.

## XGO-33 — checksums and full-artifact verification

- Added `engine/transfer/checksum` with explicit MD5, SHA-1, SHA-256 and SHA-512
  algorithm identities.
- Normalizes expected checksum algorithm aliases and lowercase hexadecimal values
  from user/Metalink/metadata sources.
- Streams verification with bounded buffers and context cancellation.
- Returns canonical actual digest, expected digest, byte count, source and
  `xgo-checksum-v1` verifier version.
- Wrong checksums are durable failed verification records and never create an
  ArtifactGeneration.
- Transport completion remains restartable `transport_complete` until successful
  verified finalization.

## XGO-34 — selective repair

- Added `engine/transfer/repair`.
- Repair eligibility is decided with `CanResume` before checkpoint invalidation.
- Damaged ranges come only from read-back-invalid committed checkpoint evidence.
- Invalid blocks are durably revoked under the current AttemptGeneration fence.
- Exact ranges are re-fetched through conditional HTTP range requests and
  recommitted by the normal checkpoint committer.
- Multiple noncontiguous bad blocks are repaired without rewriting good blocks.
- Actual fetched bytes are required to equal the damaged range set.
- Full checksum verification runs after repair; changed representation aborts the
  repair before evidence is mutated.

## XGO-35 — finalization and artifact handoff

- Added `engine/transfer/staging.File`, an exclusive finalization freeze around
  authoritative staging writes.
- Finalization requires the current attempt to be `transport_complete`.
- Exact staging size is checked while writes are frozen.
- Successful verification, ArtifactGeneration creation, Download artifact-pointer
  update and `transport_complete -> produced_artifact` are one SQLite write
  transaction.
- Publication is then queued through the pre-existing durable saga; it does not
  declare user-visible completion.
- `PreparePublication` now requires the source attempt to be `produced_artifact`.
- Fault injection covers a crash after verified artifact creation but before
  publication preparation, leaving a recoverable unpublished artifact.
- Cancellation/checksum failure/stale attempts create no publishable artifact.

## Devtool integration

`xgo_transfer#validate` now ends with:

1. `checksum_matrix`
2. `repair_lab`
3. `finalization_faults`

The final transfer-target validation on the implementation worktree reports:

- 15 stages passed
- 62 tests passed, 0 failed, 0 skipped
- 7 Go test packages passed
- 0 warnings / 0 errors
- 1 verified Go audit executable

Shared contract audits remain closed at 96 capabilities / 96 fixtures, with
`XGO-CAP-CHECKSUM-001`, `XGO-CAP-REPAIR-001` and `XGO-CAP-FINALIZE-001` marked
`IMPLEMENTED` and promoted to detailed fixtures.

## Concurrency qualification

Native Termux/Android arm64 does not support `go test -race`, so Gate-04 does not
pretend otherwise. The native workflow retains deterministic arbitration,
checkpoint, resume, repair and finalization stress. A supplemental supported-host
Linux race run passed for:

- `./engine/transfer/...`
- `./engine/store/checkpoint`

That evidence is supplemental; the artifact itself remains native-Termux safe.

## Cumulative compatibility closure

Gate-04 qualification found one inherited Wave-2 audit fixture that still attempted
verified-artifact creation directly from a reserved attempt. The production store
correctly rejected it under the new finalization invariant. `xgo-store-audit` now
advances verification/publication fixtures through
`prepared -> running -> transport_complete` before successful verification. The
verification, publication and durable fault matrices therefore exercise the same
authority boundary as production finalization rather than a legacy shortcut.

## Gate-04

The overlay selects `xgo_gate_transfer`. The gate remains a pure composition of:

`xgo_foundation -> xgo_store -> xgo_security -> xgo_transfer`

A successful native-Termux artifact application therefore qualifies the complete
Wave-4 native transfer stack without gate-local jobs or task-exclusive validation.
