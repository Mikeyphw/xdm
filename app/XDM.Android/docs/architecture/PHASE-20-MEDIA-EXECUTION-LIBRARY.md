# Phase 20: Media Download Execution and Offline Library

Phase 20 turns the Phase 19 resolver cockpit into an execution surface. It keeps Browser, Resolver, Player, and Library inside the existing Media route and adds no new top-level navigation.

## Goals

- Carry the selected video, audio, and subtitle tracks from the resolver into the download action.
- Queue direct/progressive media through XDM native or aria2 backends.
- Queue adaptive/page media through the typed Termux yt-dlp pipeline.
- Hand short-lived referer/header session context into native and aria2 `DownloadRequest` planning without persisting cookies or authorization values in Room.
- Expose media job states: Probing, Queued, Downloading, Completed, Failed, and Blocked.
- Derive an offline library from media captures plus completed downloads.
- Persist only redacted sidecar metadata: title, filename, duration, thumbnail URL, source host, page host, selected track IDs, and redacted source URL.
- Open completed direct media through the existing Media3 player card.
- Explain failures without bypass behavior: unsupported DRM/protected media, live streams, expired manifests/sessions, missing yt-dlp extractors, and aria2 failures.

## Non-goals

- No DRM bypass.
- No raw shell commands in the UI.
- No long-lived cookie/header persistence.
- No new top-level Library, Player, Browser, or Resolver routes.

## Safety model

Raw headers are carried only through `MediaRequestHandoffStore`, a process-local handoff keyed by download ID and consumed when the runtime builds a `DownloadRequest`. User-visible diagnostics and sidecar metadata use redacted summaries. Tokenized URLs are redacted before they appear in sidecar JSON or media job summaries.

## Acceptance checks

- `Download selected` invokes the selected `MediaTrackSelection` rather than only the previously persisted primary variant.
- Direct media queues through XDM with media-aware backend selection.
- yt-dlp-required media queues as a typed Termux job and updates the capture's job association.
- Offline library rows show title, filename, source host, duration, thumbnail availability, state, retry/resume affordances, and player access for completed direct media.
- Tests assert sidecars and job summaries do not leak token/session/cookie strings.
