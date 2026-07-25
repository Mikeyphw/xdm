# Browser Removal Phase 3: External Handoff and Add Download Replacement Path

## Purpose

Phase 3 strengthens the browser-neutral workflow that will replace XDM's built-in browser. It does not remove `BrowserActivity`, `BrowserScreen`, WebView, routes, launchers, or browser persistence yet.

External browsers and applications can continue sending links to `ExternalAddDownloadActivity`. The resulting request is classified and shown in Add Download before any transfer or media probe starts.

## Handoff metadata

`MainActivity` now carries these safe, review-oriented facts into `AutomationCommandDraft` and `DownloadIntakeDraft`:

- normalized URL
- filename suggestion
- page title and page URL/referrer context
- normalized MIME type
- positive content length
- source package label when Android provides one

Raw request headers remain handled through the existing redaction path. MIME type and content length do not alter idempotency, start a backend, or bypass review.

## Intake classification

`DownloadIntakeClassifier` assigns one advisory kind:

- `DirectFile`
- `DirectMedia`
- `AdaptiveMedia`
- `Torrent`
- `PageOrUnknown`

Classification uses normalized MIME type, URL path, and filename. It is deliberately conservative. Unknown endpoints remain unknown instead of being treated as guaranteed files.

## Add Download replacement UX

External drafts show:

- source
- semantic kind
- page title when available
- filename suggestion
- MIME type and content length when available
- an explicit no-auto-queue statement

Page or unknown links use a `Start direct download` label so the user understands that this action attempts the URL itself. Direct media, adaptive media, and page/unknown drafts also expose `Inspect as media`.

## Explicit media inspection

`Inspect as media` is review-first:

1. `ExternalMediaReviewPlanner` receives the neutral draft.
2. Direct media is classified through the existing `MediaCaptureService`.
3. An ordinary HTTP/HTTPS page becomes a page-probe record with `MediaSourceKind.Unknown`.
4. The record is saved in the existing Media Inbox.
5. XDM navigates to Media.

No yt-dlp command, queue item, transfer backend, worker, or download is started by this action. The existing media workbench remains responsible for metadata probing, format selection, and explicit queue actions.

## Preserved behavior

Phase 3 preserves:

- native and aria2 transfer backends
- scheduler and worker runtime
- queue and telemetry behavior
- Termux and yt-dlp adapters
- media resolver and track selection
- offline library and Media3 playback
- external browser/share/download-manager intent filters
- the built-in browser runtime until the dedicated removal phase
- Room schema 14 and Android version `0.20.0-rc08` / code 21

## Validation boundary

Phase 3 adds pure classifier and media-review planner tests, an application source contract, and a static validator. Historical architecture assertions now accept the browser-removal overlay family as a valid successor to browser-era overlays.

The next phase may remove browser UI/navigation only after Phase 3 passes in the target Android environment.
