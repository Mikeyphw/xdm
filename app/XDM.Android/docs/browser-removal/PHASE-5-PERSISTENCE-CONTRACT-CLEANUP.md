# Browser Removal Phase 5: Persistence and Contract Cleanup

Phase 5 removes the dormant browser-era scaffolding left after the Phase 4 runtime excision. It does not remove external browser integration or any downloader capability.

## Removed

- Built-in-browser phase documents from the active architecture tree.
- Phases 37–50 browser runtime validators.
- Built-in-browser Phase 18 contracts.
- Browser-only mobile surface state.
- Browser-specific capture-quality names.
- Browser-profile terminology in the privacy audit.
- Browser phase records from the active project manifest.

A concise history remains under `docs/archive/BUILT-IN-BROWSER-HISTORY.md`. It is explicitly non-contractual.

## Preserved and neutralized

- Capture grouping, duplicate suppression, confidence scoring, analytics-noise filtering, stale-session detection, protected/live labels, and redacted diagnostics now live in `MediaCaptureQuality.kt`.
- The privacy audit scans transient external page context instead of a browser profile.
- `browser-integration` remains because it handles links supplied by external browsers and Android download actions. It contains no WebKit runtime.
- `ExternalAddDownloadActivity`, share-sheet intake, `ACTION_VIEW`, Android browser download actions, and review-first Add Download behavior remain active.
- Native direct, aria2, Termux, worker, queue, resolver, track selection, offline library, Media3 playback, and diagnostics remain unchanged.

## Locked metadata

- Room schema: 14
- `versionCode`: 21
- `versionName`: `0.20.0-rc08`
- No new top-level route
- No automatic queueing for external handoffs
