# XDM Desktop REM08 — Recovery, checksum, finalization, relink, and repair integrity

## Scope

REM08 closes the frozen recovery/integrity ownership set: **18 findings — 5 High / 11 Medium / 2 Low**.

Owned findings: S05-01 through S05-18.

## Implemented guarantees

### Recovery admission and remote identity

- Recovery scans no longer infer “Ready to resume” from a stale persisted ETag or Last-Modified value alone.
- Resume is enabled only after the current recovery session successfully probes the remote object and proves a matching strong validator plus any known total length.
- Existing partial bytes additionally require current byte-range support before guarded resume is exposed.
- Recovery keeps a candidate while a resumed transfer is in progress and removes it only after the application state reports a clean completed transfer.
- Failed or paused recovery resumes invalidate the previous validation proof and return the candidate to recovery review.
- The UI marks recovery resume as in-progress before re-admitting the engine, closing the fast-completion race; a failed admission rescans instead of dropping the candidate.

### Recovery discovery and durable dismissal

- “Remove recovery record” writes a destination-bound recovery-dismissal tombstone instead of deleting download history or local artifacts.
- Persistent dismissals survive rescans/restarts and are removed automatically if a new transfer later claims that destination.
- Segment-only `.segments` artifacts are included in orphan discovery, with byte accounting limited to actual `*.part` segment payloads.
- Unclean aria2 transfers treat data already present at the destination as owned progress instead of misclassifying it as a missing XDM partial file.
- Windows checkpoint ownership comparisons use platform-correct path identity rather than unconditional case-sensitive comparison.

### Checksum durability and ownership

- `PersistedDownload` and resume checkpoints now persist both expected SHA-256 and SHA-512 independently, so the second configured checksum does not depend on a sidecar.
- Checksum workflow sidecars are version 2, destination-bound, and download-owner-bound.
- Valid version-1 sidecars migrate in memory; foreign owner/destination, corrupt, oversized, or incompatible sidecars are quarantined per artifact and cannot abort manager startup.
- Native and aria2 completion use the same dual-checksum verification path and require all configured expected hashes to pass.
- When no independent expected checksum exists, aria2 completion records the same local SHA-256 integrity identity as the native backend.

### Finalization and relink integrity

- Finalization journals are version 4 and use the same web JSON serializer contract for write/read/recovery.
- Malformed, oversized, or incompatible finalization journals are quarantined per artifact rather than failing global download-manager initialization.
- Finalization markers carry both configured expected checksums (or the locally recorded SHA-256 identity when appropriate), and promotion validation enforces both before accepting recovered/staged bytes.
- Relink refuses to mark a replacement file Completed unless XDM has at least a known length or expected checksum to prove it.
- Relink validates every available known constraint before changing the download destination/state and records a local SHA-256 identity when length is the only independent constraint.

### Repair and verification lifecycle

- Verify and selective-repair transient states are cleared on cancellation or exception.
- Restart-from-zero is also cancellation-safe and cannot leave `Repairing` latched after an interrupted reset.
- A repair whose final checksum still mismatches remains a recovery candidate; the workbench only dismisses after conclusive success.
- Recovery exposes Verify and repair only for HTTP(S) GET candidates with a known positive expected length and actual local artifact bytes, avoiding guaranteed-reject actions.

### Atomic persistence compatibility

- `AtomicFile.WriteAllBytesAsync` now provides the byte-payload atomic write primitive already required by segmented transfer identity storage and used by REM08 journals/tombstones.

## Regression coverage added/updated

- Persisted validators do not enable Resume until current remote validation succeeds.
- Segment-only orphan discovery.
- Persistent recovery dismissal across rescans.
- aria2 destination progress during unclean recovery.
- Recovery candidate retention until safe completion.
- Corrupt checksum sidecar quarantine.
- Foreign checksum-owner quarantine.
- Malformed finalization-journal quarantine.
- Dual-checksum finalization enforcement.
- aria2 local SHA-256 recording without an independent expected hash.
- aria2 failure when either configured expected checksum mismatches.
- Both expected checksums remain durable without the workflow sidecar.
- Relink is rejected when no identity constraint is known.
- Verification/restart cancellation clears transient integrity state.

## Validation policy

REM08 is an intermediate remediation overlay. Apply it with `--no-validate` under the campaign policy. The artifact carries Devtool schema-v2 clean-worktree and automatic single-commit policy. Full build/test/platform validation remains reserved for REM18.
