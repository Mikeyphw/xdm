# Phase 18: Built-in Browser Media Downloader

Phase 18 turns XDM Android into its own review-first media discovery surface. The app now has a Browser workspace inside the existing Media route with a WebView, URL/search input, back/forward/reload controls, and a media tray that captures likely media requests before anything is queued.

## Implemented surface

- Browser workspace inside Media for direct site navigation without adding a top-level route.
- WebView request sniffing through `shouldInterceptRequest`, page navigation callbacks, and `DownloadListener`.
- URL and MIME based media classification for HLS, DASH, progressive video, and audio streams.
- Media tray that shows detected streams and routes the user to the Media inbox for review.
- Add-page fallback for the current browser page URL.
- Media Download Planner that explains whether a capture should use native transfer, aria2, yt-dlp, or a live recording workflow.

## Review-first rule

The browser never starts downloads by surprise. Captured HLS, DASH, video, and audio URLs are indexed in the existing Media route. The user still chooses the stream or opens Add Download before queueing.

## Clean-room boundary

Super Video Downloader is used only as a product reference for feature shape: embedded browser, video/audio discovery, HLS/DASH handling, yt-dlp workflows, and offline media affordances. This implementation does not copy source code.

## Next slices

1. Persist browser history, tabs, cookies, and per-site desktop/mobile mode.
2. Expand HLS parsing to audio/subtitle renditions and live/VOD labeling.
3. Expand DASH parsing to grouped adaptation sets, SegmentTemplate support, subtitles, and ContentProtection detection.
4. Add explicit live stream stop-and-save jobs with bounded logs.
5. Add optional Media3 playback for completed files and previews.
