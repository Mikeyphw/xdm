# XDM selective repair and checksum workflow overlay

## Purpose

Implements recovery plan items 3 and 4 on top of commit `9ed4807`:

1. A range-preserving verify-and-repair service.
2. A complete SHA-256/SHA-512 verification workflow.

## Included

- Persists independent expected and calculated SHA-256/SHA-512 values in an atomic sidecar.
- Migrates legacy and Metalink checksums into the dual-checksum workflow.
- Calculates configured hashes in one pass and exposes verification progress separately from download progress.
- Records a clearly labeled local SHA-256 integrity value when no independent expected checksum exists.
- Builds verified 4 MiB block manifests so later repair requests only suspicious or missing ranges.
- Validates remote length, strong ETag, Last-Modified, range boundaries, and response lengths before writes.
- Reconstructs partial files from segment artifacts at their planned offsets.
- Rebuilds checkpoints and atomically finalizes only after checksum verification succeeds.
- Keeps Verify, selective Verify and repair, and destructive Restart from zero as separate actions.
- Adds clipboard/checksum-file import, calculated-checksum copy, and download-list schema v4 support.
- Adds regression coverage for dual automatic verification, persisted state, local-only records, and single-range repair.

## Validation

Devtool should restore, build, and test `app/XDM/XDM.Modern.sln` with zero warnings and zero errors.
