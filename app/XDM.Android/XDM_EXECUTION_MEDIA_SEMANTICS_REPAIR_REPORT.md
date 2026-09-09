# XDM execution/media semantics repair

This repair closes the post-DL03 functional gap exposed by real browser-direct and progressive-media journeys.

## Functional boundary

- Browser request replay context (Referer, User-Agent, Cookie, Authorization) is orthogonal to resource shape.
- `DirectFile` and `DirectMedia` execute as ordinary direct HTTP transfers; progressive MP4/audio no longer require playlist capability.
- `AdaptivePlaylist`, `SiteResolver`, `LiveRecording`, and `ProtectedDiagnostic` retain specialized handling.
- The explicit transfer shape is stored in the encrypted request handoff so scheduler restart and backend migration cannot reinterpret a direct request because a handoff exists.
- URL expiry is derived from credential-bearing URL lifetime/manifest freshness, not merely from the presence of browser headers.

## Media state boundary

- Progressive direct media is ready without playlist variants.
- "Check media" short-circuits direct resources instead of invoking playlist-only resolution.
- Backend selection/execution failures no longer overwrite media resolver status.
- Live Locator keeps sensitive request headers in a bounded process-local cache across configuration recreation; Android saved state carries only a non-secret context key.

## Persistence

Room remains schema 21. No new Room column or migration is required; transfer semantics travel inside the existing encrypted request envelope.

## Validation

The canonical static release gate includes `tools/validate-execution-media-semantics-repair.py`, and module tests cover browser direct files, progressive MP4 with captured context, adaptive playlist rejection, direct-media readiness without variants, and the separation of replay context from credentials/expiry.

## v2 carry-forward reconciliation

The first full Gradle run proved the production repair compiled and the canonical static gate passed, but two historical `MediaCaptureServiceTest` methods still asserted the superseded progressive-MP4 -> aria2 behavior. v2 replaces those expectations with direct-Native replay-context and no-synthetic-aria2-transient coverage. No production runtime behavior or Room schema changed in v2.

## v3 release-contract reconciliation

The second full Gradle run passed media and reached `:app:testDebugUnitTest`, where the historical post-DL03 contract still required the post-DL03 seal to be the current final authority even though the canonical gate had correctly advanced authority to this execution/media repair. v3 carries that contract forward to the new authority while retaining post-DL03 as the historical baseline. It also makes the repair contract's `user.dir` lookup explicitly non-null to remove the Kotlin/Java nullability warning. Production runtime behavior and Room schema remain unchanged from v2.

## Promise-delivery audit follow-up — 2026-09-08

A source-level re-audit of the applied release found and closed three carry-forward gaps in the execution/media repair:

- the legacy `isMediaRequest` compatibility bit no longer influences default `DownloadRequest.transferShape`;
- redownload/link-refresh keeps a specialized shape when a replacement signed URL is opaque, while explicit `.mp4`/`.m3u8`/`.mpd` evidence may still change it;
- Live Locator keeps exact source/page/variant URLs together with headers in its bounded process-local context cache, restores saved media kind for opaque manifests, and serializes no executable request URL into Android saved-state.

Room remains schema 21.

