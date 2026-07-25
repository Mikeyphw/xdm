# Browser Removal Phase 2: Neutral Intake Extraction

## Status

Phase 2 is a forward-only extraction phase. The built-in browser remains runnable so its behavior can be compared against the new seams, but it no longer owns the downloader-facing request models.

## Goal

Separate review-first download intake and media-candidate intake from `BrowserScreen`, WebView callbacks, activities, Compose, persistence, and transfer execution.

## New neutral contracts

### `ExternalUrlPolicy`

`core-model` now owns the supported external URL policy. It normalizes HTTP, HTTPS, and FTP download handoffs, extracts URLs from shared text, derives origin hosts, and delegates sensitive-header redaction.

`BrowserHandoffPolicy` remains as a deprecated compatibility facade while older integrations and historical contracts migrate. New production code uses `ExternalUrlPolicy`.

### `DownloadIntakeDraft`

A `DownloadIntakeDraft` is the review payload consumed by Add Download. It contains normalized, bounded metadata only:

- stable intake ID
- normalized URL
- suggested filename
- source label and origin
- optional page title and page URL
- optional normalized MIME type and positive content length

It contains no repository, backend, queue, worker, or execution references.

### `DownloadIntakePlanner`

The planner creates neutral drafts for:

- a page URL selected inside the temporary built-in browser
- a direct WebView download-listener event
- external share/view/download-manager handoffs

The built-in browser can supply HTTP or HTTPS drafts only. External download intake continues to allow HTTP, HTTPS, and FTP.

### `MediaCaptureIntakePlanner`

The media planner accepts `MediaRequestFacts` and returns a classified candidate plus a record. The same seam can be used by WebView interception today and external integrations after browser removal.

## Runtime wiring

`BrowserScreen` now emits:

- `DownloadIntakeDraft` through `onOpenDownloadReview`
- `MediaRequestFacts` through `onMediaRequest`

`MainViewModel` now exposes:

- `openDownloadReview(draft)`
- `captureMediaRequest(facts)`

The previous browser-shaped methods were removed:

- `openAddFromBrowser`
- `openBrowserDownload`
- `captureBrowserMediaUrl`

`MainUiState.externalAddDraft` now stores `DownloadIntakeDraft` directly. Add Download behavior and transfer creation remain unchanged after the user reviews and confirms the draft.

## Safety boundaries

Phase 2 does not:

- delete `BrowserScreen` or `BrowserActivity`
- modify Android manifest routes or intent filters
- change Room schema or persisted downloader data
- change backend selection
- change native, aria2, yt-dlp, Termux, scheduler, worker, queue, library, or player behavior
- allow browser UI to enqueue a transfer directly

Network interception passes request headers into in-memory media facts for classification. It does not persist them in the neutral download draft. Existing media privacy and redaction policies remain authoritative for later execution handoff.

## Validation

Phase 2 adds tests for:

- browser page draft normalization
- detected-download filename, MIME, page, and size preservation
- external FTP intake preservation
- rejection of unsafe/local schemes
- media intake classification from neutral facts
- rejection of ordinary HTML pages
- compatibility delegation from `BrowserHandoffPolicy`
- source-level proof that neutral planners are execution-free

## Next phase

Phase 3 strengthens the external-browser handoff and Add Download UX so users retain the browser convenience after the internal browser is removed.
