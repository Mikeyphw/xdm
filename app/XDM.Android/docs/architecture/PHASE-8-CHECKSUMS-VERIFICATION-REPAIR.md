# Phase 8 — checksums, verification, and selective repair

Phase 8 turns backend completion into a provisional state until XDM can verify the resulting file.

## Rules

- A registered checksum expectation must be evaluated before a transfer reaches `Completed`.
- aria2 completion remains provisional until XDM records the checksum result.
- SHA-256 and SHA-512 expectations are normalized before storage.
- Verification progress is persisted independently from transfer progress.
- Re-verification reads local bytes only and does not contact the network.
- A mismatch never produces a completed state.
- Trusted block manifests are written after successful verification.
- Native selective repair uses trusted block manifests to request only corrupt or missing ranges.
- Cross-backend repair requires the ownership generation to remain valid.

## UI placement

The Android topography contract is preserved. Checksum input lives inside Add Download, verification state is shown inside each download card, and diagnostics report Room schema v8.

## Recovery behavior

When the completed file is missing or a checksum mismatches, the download moves to `RecoveryRequired`. Existing bytes are preserved; no backend overwrites trusted blocks without an explicit repair plan.
