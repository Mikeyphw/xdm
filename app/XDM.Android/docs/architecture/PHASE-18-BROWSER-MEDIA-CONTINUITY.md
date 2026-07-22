# Phase 18B: Browser Media Continuity

This pass keeps the built-in browser inside the existing Media route while adding continuity and richer media intelligence.

## Browser continuity

- Browser tabs persist in app-private preferences and never add a top-level route.
- Recent page history is available from the Browser workspace.
- Cookie profiles are explicit: Standard, Private, and Desktop.
- Private mode rejects cookies, disables persistent DOM storage, and clears session cookies.
- Desktop mode keeps cookies while switching the WebView user agent and viewport settings for sites that hide media behind mobile pages.

## Media intelligence

- HLS parsing now includes `EXT-X-MEDIA` audio/subtitle groups.
- HLS inspection classifies live streams, protected playlists, audio tracks, and subtitle tracks.
- DASH parsing now reads `AdaptationSet` context, inherited MIME/content type/language, BaseURL inheritance, audio tracks, text/subtitle tracks, and ContentProtection markers.
- Protected media is surfaced as review-first metadata. The app does not bypass DRM.

## yt-dlp and offline playback groundwork

- yt-dlp metadata/download actions prefer the captured page URL when present, because page context often carries the cookies and extractor hints needed for real sites.
- The Media card exposes the probe URL and notes when session context matters.
- The Offline library card summarizes playable, adaptive, audio-only, and subtitle-ready captures.
- Adaptive/protected playback remains review-first until the Media3/offline asset pipeline prepares safe local assets.

## Guardrails

- No new top-level route is introduced.
- WebView references remain per-composition and weakly held.
- Deprecated browser settings are not used.
- Auto-mirrored navigation icons are used for back/forward controls.
