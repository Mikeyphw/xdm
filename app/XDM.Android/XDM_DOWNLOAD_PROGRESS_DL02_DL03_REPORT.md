# XDM DL02 + DL03 — Progress Pipeline Seal

Base: `0683d1e3` (successful MC04+MC05 v6)

## DL02 — Progress & Checkpoint Redesign

- Native checkpoints no longer SHA-256 the entire completed prefix around every 1 MiB.
- New checkpoints persist incremental fixed-block SHA-256 ownership proofs. Routine saves hash only newly completed blocks and may conservatively lag the live writer by less than one integrity block; pause and final-segment flushes include the tail.
- Checkpoint hashing/fsync is serialized by a dedicated save mutex. The transfer progress mutex is held only long enough to snapshot segment counters, so hashing cannot stall byte telemetry.
- Legacy whole-prefix `completedSha256` checkpoints remain readable and are upgraded on the next save.
- Resume, reconciliation and migration inspection accept either the new fixed-block proof or the legacy digest and still fail closed on corruption.
- Native speed is task-wide rolling throughput over a recent window. Segment boundaries and retry attempts no longer reset or mix the speed baseline.
- Backend live snapshots are an in-memory UI stream; Room byte/speed checkpoints are limited to roughly 3 Hz (`333 ms`) while state/backend/error/terminal transitions remain immediate.
- Each observation run resolves a durable ownership publication fence once; live snapshots validate task/generation/installation identity in memory, durable progress writes remain generation-fenced, and completion revalidates the ownership row before and after verification.
- aria2 polling is buffered with conflation, so a slow downstream Room/UI consumer cannot stretch the nominal 750 ms RPC poll interval; latest state wins.

## DL03 — Progress Pipeline Seal

- Verification still hashes every byte, but Room does not upsert on every 8 KiB read. Running verification persistence is gated by time and byte deltas (350 ms + 4 MiB, with a 1 s maximum interval); start and terminal evidence remain immediate.
- Running verification is also exposed in memory so the UI can display actual `bytesVerified / totalBytes` instead of the already-complete payload fraction.
- Completed-artifact and durable-resume inspections are cached by semantic identity. Byte/speed changes do not rescan every completed/resumable artifact.
- Operational activity observes semantic download changes only, and the store itself returns before SharedPreferences writes when fingerprints are unchanged.
- The expensive `MainUiState` projection consumes semantic Room flows. High-frequency transfer/verification data is overlaid in one cheap final projection.
- Smart/Recent/Progress tie-breaking uses stable creation/id semantics instead of `updatedAt`; Smart active ordering also no longer uses live speed.
- Runtime progress stages are explicit: Resolving, Preparing, Downloading, Merging, Verifying, Finalizing.
- Room remains schema 21; this overlay requires no migration.

## Regression gates

- Linear checkpoint-hash-work test.
- Task-wide rolling-speed test.
- Verification durable-write budget tests.
- Stable active-ordering test.
- Verification phase-progress test.
- Static DL02/DL03 promise validator.
- Completion/open-file and remediation carry-forward contracts bind completed artifacts to the validated publication fence generation.
- Historical Phase 2 / remediation native-resume contracts are carried forward from attempt-local speed and whole-prefix digest assertions to task-wide rolling speed plus incremental block-proof validation, while retaining legacy checkpoint readability.
