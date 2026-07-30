# Phase 53 - Extension Detection Quality Gate

Phase 53 hardens the Firefox media detector after field reports that generic page/API resources were being offered as videos.

## Behavior

Strong media is offered by default. Strong signals include media MIME types, HLS/DASH manifests, real media file extensions, media filenames from `Content-Disposition`, and browser media requests with range or meaningful size context.

Possible media stays behind an explicit advanced toggle. Possible signals include extensionless playback endpoints, octet-stream responses with stream-shaped URLs, or player-response URLs found near strong media keys. These are useful on some sites, but they are not allowed to auto-surface unless the user enables possible-media detection from the extension popup.

Rejected signals include ads, media segments, posters, thumbnails, generic API metadata, and JSON fields that merely happen to be named `url` or `src`.

## Privacy and safety

No cookies, Authorization values, bearer tokens, or full URLs are added to normal UI. Diagnostic URLs remain sanitized by the existing network observer, and browser request headers continue through the existing transient handoff path only when the browser actually captured them.

## Boundaries

- No D8 or Debug Workbench reopening.
- No Room migration; schema remains 14.
- No top-level Android route.
- No all-files permission.
- No automatic upload.
- Phase52 nullable contract-test warning is folded into this phase so diagnostics can return to zero.
