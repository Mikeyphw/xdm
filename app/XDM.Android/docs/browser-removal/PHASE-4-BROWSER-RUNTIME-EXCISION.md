# Browser Removal Phase 4: Browser Runtime Excision

## Purpose

Phase 4 removes XDM Android's built-in browsing surface while preserving the downloader replacement paths established in Phases 2 and 3.

This is the first destructive browser-removal phase. It deletes the browser activity and WebView implementation, removes the Browser route from navigation, and removes browser startup state from the app shell and ViewModel.

## Removed runtime

- `BrowserActivity`
- `BrowserScreen`
- `AppRoute.Browser`
- the Browser launcher entry
- generic HTTP/HTTPS browsing intent handling owned by `BrowserActivity`
- Android WebKit, WebViewClient, and WebChromeClient runtime code
- browser startup URL state and routing callbacks

## Preserved downloader behavior

- `ExternalAddDownloadActivity`
- Android share-sheet intake
- explicit download-manager actions
- reviewed HTTP, HTTPS, and FTP handoff
- MIME, content-length, page-title, referrer, and source metadata
- Add Download classification and guidance
- explicit Inspect as media flow
- media resolution, track selection, queueing, execution, offline library, and Media3 playback
- native, aria2, yt-dlp/Termux, worker, scheduler, and telemetry components

## Intent boundary

XDM no longer registers a general browser activity. `MainActivity` remains the normal application launcher. `ExternalAddDownloadActivity` remains the explicit review-first receiver for links and content sent by external browsers and applications.

Ordinary app launch actions are harmlessly ignored by external-intake parsing. Valid handoff actions continue to produce neutral automation or download-intake drafts and never start a transfer directly.

## Persistence behavior

A previously persisted `Browser` route safely falls back to Downloads because route restoration already uses `runCatching { AppRoute.valueOf(...) }`.

Browser preference and historical documentation cleanup are intentionally deferred to later phases. Phase 4 removes the active runtime and all reachable UI, not every historical symbol or stored key.

## Validation

The Phase 4 contract requires:

1. no Browser route, activity, launcher, screen, startup state, or Android WebKit runtime;
2. exactly one general app launcher;
3. preserved external review-first handoff;
4. preserved Phase 2 neutral contracts and Phase 3 replacement UX;
5. protected downloader engines and media runtime still present;
6. browser-era runtime validators retired from the active release gate.
