# XDM Android Browser Media Continuity Overlay

This overlay extends the already-landed browser media downloader foundation without adding a new top-level route.

## Included

- Fixes browser deprecation warnings:
  - uses AutoMirrored back/forward icons
  - removes deprecated WebSettings.databaseEnabled usage
- Adds browser continuity inside the Media route:
  - persisted browser tabs
  - recent history
  - explicit Standard / Private / Desktop cookie profiles
  - per-composition weak WebView navigation bridge retained
- Expands media resolution intelligence:
  - HLS audio/subtitle groups from EXT-X-MEDIA
  - HLS live/protected inspection
  - DASH AdaptationSet parsing, inherited MIME/content type/language, subtitles, audio tracks, and ContentProtection detection
- Improves yt-dlp flow:
  - metadata/download actions prefer the captured page URL when available
  - Media cards expose copyable probe URLs and session-context hints
- Adds offline library/player groundwork:
  - summary card for playable/direct/adaptive/audio/subtitle captures
  - review-first messaging for adaptive/protected streams
- Adds tests and a browser media continuity validator.

## Guardrails

- No AppRoute changes.
- No new Room schema version.
- No static WebView or Android Context storage.
- JavaScript enablement is explicitly lint-reviewed and scoped to the embedded browser settings helper.
- No DRM bypass. Protected streams are flagged and blocked from direct queueing.
