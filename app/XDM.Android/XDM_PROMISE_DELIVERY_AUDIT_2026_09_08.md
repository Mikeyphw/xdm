# XDM Android Promise Delivery Audit — 2026-09-08

## Baseline

Audited the uploaded current repository after the execution/media semantics repair and Add/Media UX remodel. The audit traced intake, request-shape classification, encrypted handoff, redownload/link refresh, scheduler execution, backend migration, Live Locator recreation, media state, and the direct-media UI.

## Confirmed delivered

- Browser replay headers are orthogonal to transfer shape and URL expiry.
- Direct files and progressive media use direct HTTP semantics; HLS/DASH remain adaptive.
- Transfer shape survives encrypted persistence, scheduler reconstruction, and backend migration.
- Direct media is ready without playlist variants and execution failures do not overwrite resolver state.
- Add Download uses one explicit action with advanced diagnostics collapsed.
- Direct media cards use Download as the primary action.
- Room remains schema 21.

## Gaps found and closed

1. **Legacy media boolean fallback** — `DownloadRequest.isMediaRequest=true` could still default the shape to `AdaptivePlaylist`. The bit is now source-compatibility-only and cannot promote a direct resource.
2. **Opaque redownload/link refresh** — a known `AdaptivePlaylist` could be downgraded to `DirectFile` when its refreshed signed URL had no extension. Refresh now preserves specialized semantics when the new URL has no stronger shape evidence.
3. **Live Locator recreation/privacy** — saved-state serialized exact source/page/variant URLs and restore reclassified opaque manifests from URL/MIME. Exact executable URLs plus headers now stay only in the bounded process-local context cache; Bundle state carries a cache key and semantic/display metadata, and saved kind is used to restore opaque HLS/DASH.
4. **Direct-media Options sheet** — the main card hid fake quality controls but Options could still show a single `Primary` row. The track picker now uses the same meaningful-choice gate.

## Validation

The canonical final gate owns `tools/validate-promise-delivery-audit.py`. Unit/source contracts cover legacy-boolean isolation, opaque-shape clone/refresh continuity, Live Locator saved-state privacy/semantic restoration, and direct-media Options gating.
