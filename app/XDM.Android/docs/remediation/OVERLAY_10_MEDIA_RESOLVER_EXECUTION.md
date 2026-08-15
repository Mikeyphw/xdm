# Master remediation Overlay 10 — media resolver/execution

Base: successfully applied Phase 08+09 v3 commit `79df336c`.

This intermediate overlay implements the roadmap items M-031, the app/media side of M-032, M-047, and the media side of M-055. Campaign Gradle/unit/lint validation remains deferred; the artifact is applied with `--no-validate` and retains the final validation task contract in its schema-v2 manifest.

## M-031 — real selected request and dispatch contract

- Resolve the selected variant to its exact secure handoff URL and merge its request headers over capture-level headers by case-insensitive header name. Variant evidence is more specific than capture-level evidence for that selected request.
- Carry the configured destination URI from preferences through media queue planning, backend preview, and the real `Download` row. The media button no longer substitutes Public Downloads.
- Build and evaluate `MediaDispatchPlan` before any app queue or Termux job mutation. Protected media, stale metadata, missing track/format choice, Termux prerequisites, and secret-leak gates hold dispatch rather than being advisory-only.
- Preserve exact selected-track identity and explicit yt-dlp format selection in the execution plan.

## M-032 app/media side — one real execution owner

- Direct/native/aria2 media remains app-owned and creates a normal `Download`.
- yt-dlp/live media is Termux-owned. The button calls the suspending durable Termux enqueue and does **not** manufacture a queued `Download` row.
- Durable Termux job insertion and the `media_outputs` external-owner row happen in the same Room transaction before launch.
- A retry receives a new external job ID/attempt generation and a new output row in the same transaction. Earlier output generations remain addressable.
- Sensitive authenticated session data is not downgraded onto a command line. Cookie/Authorization/Proxy-Authorization or credential-bearing input holds the Termux path for Overlay 11's secure transient bridge.

## M-047 — one capture, many output generations

Room schema 20 adds `media_outputs` as a child entity of `media_captures`. Each output records:

- capture ID;
- owner kind (`AppDownload` or `TermuxJob`);
- real owner ID;
- optional app `downloadId`;
- attempt generation;
- destination/file/MIME metadata;
- selected track IDs;
- structured output state;
- committed artifact URI/generation when available.

A unique owner-kind/owner-ID/generation index prevents duplicate identity while allowing one capture to retain many outputs and retry generations. Migration 19→20 creates the table and indices and backfills every existing capture→download compatibility link.

The Library is output/generation keyed. App-owned `media_outputs` now synchronize transactionally whenever the authoritative `Download` advances state or ownership generation. A new Download generation creates a new child row; verified completion identity is persisted on the Download CAS/upsert path and mirrored into that generation. Fresh-redownload/restart-from-zero paths clone media lineage into the replacement Download instead of silently dropping it. For Termux-owned outputs, durable post-processing state is synchronized into the output row, including the completed external artifact URI/generation.

A capture stays reviewable after its first output, so users can choose another quality/track set or destination and create a second output. Capture/variant secure handoffs are retained for that repeat execution and are removed together when the parent capture is explicitly deleted.

“Remove library record” targets the selected output generation rather than deleting the parent capture. App-owned removal writes a `Hidden` tombstone so later state synchronization cannot resurrect that generation while still preserving lineage; the Download/file remains intact. Termux-owned removal transactionally deletes the terminal/recovery job metadata and its output row, while synchronization re-checks durable job existence inside the same transaction as any output upsert so a stale observer emission cannot resurrect a removed generation. Production Library/diagnostics disable the pre-v20 capture-link fallback. Historical app generations never borrow the current Download generation's state or bypass completed-artifact validation.

## M-055 media side — structured execution failures

`MediaExecutionLibraryPlanner.classifyFailure()` no longer infers engine failure from error-message text. It classifies from structured protection state, execution strategy, manifest freshness, `DownloadState`, and `BackendType`. Free-text error detail is retained only as bounded human detail after the category is established.

The Media3 player-side structured `PlaybackException` work from Phase 09 is preserved; Overlay 10 does not regress it.

## Deferred validation contract

The artifact declares but does not run:

- `:app:compileDebugKotlin`
- `:app:testDebugUnitTest`
- `:app:lintDebug`

Targeted pre-delivery checks cover source promises, Kotlin syntax for the pure-media changes/contracts, schema/migration structure and backfill simulation, and overlay hash/preimage/apply integrity. Full campaign validation remains reserved for Overlay 13.
