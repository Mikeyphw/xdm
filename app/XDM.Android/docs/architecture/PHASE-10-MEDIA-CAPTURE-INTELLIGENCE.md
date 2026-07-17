# Phase 10: Media Capture and Metadata Intelligence

Phase 10 turns the existing Media route into a real inbox for browser/share media captures without adding a new top-level navigation item.

## Capture inputs

- `ACTION_SEND` text, audio, and video shares are scanned for HTTP(S) media URLs.
- `ACTION_VIEW` HTTP(S) intents are scanned with the same detector.
- Detection is idempotent: the capture ID is derived from the normalized URL, so repeated shares update the same record instead of creating confetti duplicates.

## Metadata contract

A media capture stores title, source URL, optional page URL, status, source kind, MIME type, container, codec summary, duration, thumbnail URL, inferred filename, variant count, and the created download ID when queued. Missing remote metadata is explicit and never blocks the inbox.

## Playlist and variants

The media module exposes an HLS variant parser that can classify variant playlists without network I/O. DASH and HLS manifest URLs are routed through the native media-safe pipeline because native capture preserves Android diagnostics, replay details, and future metadata refresh hooks.

## Backend strategy

Media downloads set `isMediaRequest=true` on the download request. The backend policy already prefers XDM Native for media workflows and exposes media capability in Settings. aria2 remains available for non-media multi-source workloads.

## Recovery safety

Captures are persisted in Room schema v10. A capture can survive process death, be removed by the user, or be linked to the download created from it. Startup recovery does not need a new route because the Media inbox remains an existing primary route.

## Topography

No new top-level route was added. Mobile bottom navigation still uses Downloads, Queues, Scheduler, and Media; overflow remains Add, Recovery, Diagnostics, and Settings.
