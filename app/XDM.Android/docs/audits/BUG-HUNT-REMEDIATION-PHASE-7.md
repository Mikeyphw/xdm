# Android Bug Hunt Remediation Phase 7

## Scope

Phase 7 replaces the former in-memory Termux/media helpers with a durable post-processing execution system. It covers automatic triggers, idempotent event claims, immutable retry generations, process-death recovery, exact process ownership, Android-to-Termux artifact bridging, transactional Android publication, metadata reconciliation, capacity/tool/format preflight, and truthful controls.

## Durable execution model

- `post_processing_jobs` stores the immutable job specification, root and parent attempt IDs, attempt generation, owner/run identities, bridge artifacts, requested control generation, progress, timeout, tool versions, verification result, final URI, and terminal outcome.
- `post_processing_claims` stores the unique `(subjectId, subjectGeneration, trigger, ruleId, actionId)` claim and cascades with its owning job.
- Automatic claims are retained when manual history is cleared, preventing process recreation or repository replay from executing the same action twice.
- Retry parses the preserved immutable specification and creates a new child attempt with a monotonically increasing generation. It does not mutate or relaunch the failed row.
- Room schema 16 adds both tables. The migration chain remains historically honest: 14 to 15 retains the previously exported external-command security fields, and 15 to 16 adds post-processing.

## Trigger and result routing

- Download automation consumes the real `TransferTerminalEvent`, including its durable backend attempt generation.
- Media-capture automation derives a stable generation from capture identity, linked download, resolution timestamp, and creation timestamp. Mutable `updatedAtEpochMs` is not used as a claim identity.
- The Termux pending result carries `runId`, `jobId`, and the one-attempt process token. Result reconciliation requires the persisted run and token to match.
- FFprobe and yt-dlp JSON update the owning capture and variants. Published outputs are associated with the owning download/capture and canonical Android URI.

## Artifact and secret boundary

- Android-readable `content://` inputs are copied into XDM-owned shared bridge artifacts only after input, output, tool-scratch, final-destination, and reserve capacity checks pass.
- Command payloads are written to an XDM-owned payload file. The RUN_COMMAND argument launches only the short managed wrapper; URLs and tool arguments do not appear directly in Android intent extras or process listings.
- Signed/bearer-like query parameters and credential-bearing yt-dlp arguments are rejected. Result previews and failures pass through the structural privacy redactor.
- Output, metadata, progress, owner, and payload bridge identities are persisted before execution and selectively retained for recovery when Android publication is incomplete.

## Process ownership and controls

- The wrapper starts the payload in its own process group where `setsid` is available and records job ID, random one-attempt token, PID, process group, and payload path.
- Pause, resume, cancel, probe, and force-cancel first verify the expected job/token and confirm the live process command line references the exact owned payload.
- Cancel sends `TERM`, waits a bounded ten seconds, and exposes force cancellation only when the same owned process group remains alive. Force cancellation never targets a generic Termux process.
- Timeout is persisted and converted into the same owned graceful cancellation flow. One monitor coroutine is allowed per job.

## Preflight and publication

- Android preflight validates filename UTF-8 byte limits, traversal, output MIME/container compatibility, root readiness, Termux permission, and previously probed missing tools.
- The managed payload verifies tool availability/version and uses FFprobe or yt-dlp simulation before output-producing work. Known missing streams, unsupported formats, and impossible output containers fail before bytes are written.
- FFmpeg/yt-dlp may overwrite only the newly allocated XDM staging artifact. Final conflict behavior remains controlled by `AndroidDestinationWriter`.
- Android copies the staged result into destination staging, flushes and syncs, verifies stable size and SHA-256, promotes transactionally, and only then records the final URI.
- `Verify SHA-256` is Android-local, cancellable, progress-aware, and unavailable without exactly one 64-hex expected digest.

## UI and settings

- DataStore `PostProcessingSettings` remains the single preference authority. The former independent in-memory automation settings are removed from execution decisions.
- The durable job card exposes pause, resume, graceful cancel, exact-owner force cancellation, publication recovery, and new-attempt retry only in applicable states.
- The UI displays attempt generation, run ID, PID, progress, timeout/recovery message, and final publication state. Clearing history explicitly retains automatic claim tombstones.

## Executable coverage

- `PostProcessingPhase7ContractTest` exercises checksum/name/secret policy, generation-scoped claims, immutable JSON round trips, stable capture generation, tool/format preflight, and source-level integration invariants.
- `PostProcessingMigrationTest` validates 15 to 16 and 14 to 16 using Room's migration helper and production database configuration.
- `tools/validate-bug-hunt-phase7-post-processing-termux.py` verifies the complete source, schema, manifest, migration, process-control, bridge, security, runtime, UI, and regression-test contract.

## Environment limitation

The implementation environment could not download the pinned Gradle 9.4.1 distribution because DNS/network access to `services.gradle.org` was unavailable and the wrapper was not cached. The overlay therefore includes a deterministic source validator and migration/schema checks, and must still run the repository's normal Gradle test/lint tasks through Devtool on the target machine.
