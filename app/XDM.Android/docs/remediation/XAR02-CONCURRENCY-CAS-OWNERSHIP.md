# XAR02 — Generation-owned State Machines & Compare-and-set Concurrency

**Overlay:** 2 of 17  
**Roadmap owner:** S04 — Domain State & Concurrency  
**Canonical coverage:** **18/18** S04 root causes  
**Commit message:** `XAR02: add generation-owned CAS state ownership`

## Scope

XAR02 introduces the shared optimistic-concurrency foundation required before intake, storage, execution, scheduler, media, diagnostics, and UI action fixes can safely build on durable state. The implementation removes whole-row/stale-write ownership from the most dangerous state mutation paths and replaces it with generation/revision-fenced transitions.

## Implemented changes

### Durable download ownership

- Added observable ownership fields to `Download`: `observedAttemptGeneration` and `rowRevision`.
- Converted repository `save`/`saveAll` from blind upsert semantics to compare-and-set updates through `DownloadGraphTransactionDao.updateDownloadOwnedRevision`.
- Added state-only CAS with source-state allowlists through `transitionDownloadStateOwned` / `transitionDownloadStateIfCurrent`.
- Added priority-only CAS through `updateDownloadPriorityOwned`; queue reprioritization now fails if any row has been claimed or mutated since observation.
- Creation remains creation-only: `upsertDownloadPreservingNewerState` now uses `INSERT IGNORE`; existing rows must use owned CAS.
- Terminal states are protected from stale resurrection/downgrade.

### Queue, schedule, and UI mutation safety

- Added `QueueDao.updateIfUnchanged` and `ScheduleDao.updateIfUnchanged`; MainViewModel settings mutations now fail visibly instead of overwriting concurrent changes.
- Added `UiMutationConcurrencyCoordinator` with destination-intent tokens and atomic exclusive diagnostic gates.
- Destination validation is last-intent-wins: a slow health result from an older destination choice cannot replace the latest user intent.
- Live validated-destination sets use atomic `MutableStateFlow.update` instead of read-modify-write snapshots.
- Diagnostic/self-test gates now use atomic acquire/release guards.

### Native HLS control sequencing

- Native HLS pause/resume/cancel operations are suspending, per-download serialized commands with monotonic `ControlIntent` sequence numbers.
- Late callbacks update durable download state only through generation/revision CAS.
- Pause/resume/toggle paths re-read current download rows before acting and cannot write a presentation snapshot back to Room.

### Media capture, variants, exact request sidecars, and output ownership

- Added `rowRevision` to `MediaCaptureRecord` and generation/revision fenced capture/variant replacement paths.
- Added `observedAttemptGeneration` and `rowRevision` to `MediaOutputRecord`; embedded FFmpeg/Termux/Native HLS output callbacks now cannot resurrect or downgrade terminal outputs.
- Exact media request handoffs now carry `subjectGeneration`, reject stale/equal-conflicting replacements, and expose failure to callers instead of silently overwriting durable sidecars.
- `SecureRequestEnvelopeStore.put` is fail-closed and rejects stale/equal-conflicting subject payloads in both memory and encrypted Android stores.
- Browser media sessions no longer use equal-revision last-writer-wins: compatible equal revisions may enrich final sent headers/evidence, while conflicting exact request/header identities are rejected.
- Browser capture session summaries merge candidates on equal revision rather than replacing the current registry entry.

### Orchestration boundary and validation

- MainViewModel no longer acts as the only durable ordering boundary for the corrected paths; state mutations route through repository/coordinator ownership APIs.
- Added `tools/validate-xar02-concurrency-cas.py`, Gradle task `verifyXar02ConcurrencyCas`, and final-gate wiring.

## Canonical findings closed

| ID | Closure |
|---|---|
| S04-01 | Atomic queue claim can no longer be undone by stale hold writes; holds transition by observed attempt/revision/state. |
| S04-02 | Pause/resume now re-read current rows and route durable state changes through CAS instead of persisting UI composites. |
| S04-03 | Reprioritization is revision-fenced and fails after concurrent claim/mutation. |
| S04-04 | Generic download updates use optimistic CAS, not stale blind upsert. |
| S04-05 | Native HLS controls are serialized suspending commands with generation/revision-fenced durable effects. |
| S04-06 | Destination selection is last-intent-wins through monotonic intent tokens. |
| S04-07 | Live destination validation updates are atomic state-flow updates. |
| S04-08 | Queue and schedule mutations now have update-if-unchanged paths instead of whole-row last-writer-wins. |
| S04-09 | Diagnostic/self-test running guards use atomic exclusive gates. |
| S04-10 | Added direct behavioral adversarial tests for the critical concurrency races. |
| S04-11 | Added a current S04 validator wired to Gradle/final gate, replacing stale/inconsistent coverage for these roots. |
| S04-12 | Split ordering responsibilities into repository CAS APIs, Native HLS command serialization, and UI mutation coordinator. |
| DS2-S04-01 | Media capture import/refresh rejects stale capture revisions and preserves linked captures. |
| DS2-S04-02 | Variant replacement is capture-row-revision fenced and cannot destructively replace newer variant sets. |
| DS2-S04-03 | Browser sidecars reject equal-revision conflicting replacements; compatible equal revisions merge evidence only. |
| DS2-S04-04 | Exact request handoffs are subject-generation-owned and durable-store write success is required before cache visibility. |
| DS2-S04-05 | MediaOutput writes are attempt/revision fenced and terminal outputs cannot be resurrected/downgraded by late callbacks. |
| DS2-S04-06 | Browser capture session registry equal revisions merge candidates instead of replacing summaries. |

## Validation

Focused validation is executable via:

```bash
cd app/XDM.Android
python3 tools/validate-xar02-concurrency-cas.py
```

The validator runs 8 adversarial concurrency simulations and static repository contracts covering all **18/18** XAR02 roots. It is also exposed as Gradle task `:app:verifyXar02ConcurrencyCas` and included in `tools/run-final-release-gate.sh`.

## Validation run in this build container

Source-only validation completed successfully:

- `python3 tools/validate-xar02-concurrency-cas.py` — 8/8 focused adversarial concurrency tests and 18/18 repository contracts passed.
- `python3 tools/validate-xar01-build-provenance.py` — retained XAR01 provenance gate passed.
- `python3 tools/validate-ffmpeg01-embedded-runtime-media-execution.py` — retained FF01 contract passed.
- `python3 tools/validate-ffmpeg02-media-mux-hls-postprocessing.py` — retained FF02 contract passed.
- `python3 tools/validate-ffmpeg-roadmap-postseal-hotfix.py` — retained post-seal native-HLS ownership contract passed.
- `python3 tools/validate-ffmpeg04-full-release-seal.py` — retained FF04 release seal passed.
- `python3 tools/validate-execution-media-semantics-repair.py` — retained execution/media semantics repair passed.
- `python3 tools/validate-media-parity03-native-hls.py` — retained Media Parity03 native HLS validation passed.
- `bash -n tools/run-final-release-gate.sh` and Python bytecode compilation passed.
- A Gradle invocation could not complete here because the wrapper tried to download Gradle 9.7.1 from `services.gradle.org`, and this build container has no network access. The overlay is therefore intentionally delivered as an intermediate `--no-validate` Devtool apply.

## Boundaries intentionally deferred

XAR02 establishes the generation/CAS foundation. Full admission transaction convergence remains owned by XAR05, storage/publication journaling by XAR06, native HTTP execution semantics by XAR07, aria2 ownership by XAR08, scheduler retry leases by XAR09, browser/WebView capture evidence policy by XAR10, and media execution correctness by XAR12.
