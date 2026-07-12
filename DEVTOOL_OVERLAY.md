# XDM Overlay — Resume integrity and crash recovery

This overlay implements the reliable-transfer foundation on top of commit `d9c9756`.

## Transfer integrity

- replaces generic partial files with transactional `.xdm.part` artifacts;
- writes atomic, flushed `.xdm.resume.json` checkpoints;
- validates Content-Range, known total length, ETag, and Last-Modified before append;
- verifies SHA-256 or SHA-512 before final rename when an expected checksum is supplied;
- verifies the `416 Range Not Satisfiable` completion path before finalization;
- records manual SHA-256 fingerprints for completed downloads without a supplied checksum;
- preserves mismatching partials and suspect completed files for diagnosis.

## Crash recovery

- reconciles history with actual partial and segmented bytes on startup;
- migrates legacy `.part`, segmented-part, and `.finalizing` artifacts;
- recovers valid interrupted finalization using durable length/checksum markers;
- restores the active mirror after a crash during failover;
- rejects malformed, oversized, foreign, or incomplete checkpoints;
- serializes final completion against in-flight checkpoint writes so no stale checkpoint can reappear after a verified rename;
- exposes recovery review, Verify, and Repair actions in Downloads.

## Mirrors and Metalink

- supports ordered mirrors with normal retry/backoff before failover;
- restarts from zero when changing mirrors to avoid mixed-source corruption;
- imports bounded, XXE-safe Metalink v4 documents;
- imports declared size plus SHA-256/SHA-512 metadata;
- retains mirrors, size, and checksum metadata in download-list export/import.

## Qualification

The overlay adds deterministic coverage for atomic checkpoints, corrupt/oversized checkpoint quarantine, checksum success and mismatch, safe orphan handling, crash reconciliation, interrupted finalization, manual verification and repair, `416` verification, mirror failover/recovery, Metalink parsing/XXE rejection, persistence round trips, and the recovery UI contract.

`docs/parity/features.json` is intentionally unchanged because these are modern reliability enhancements rather than legacy-parity claims.
