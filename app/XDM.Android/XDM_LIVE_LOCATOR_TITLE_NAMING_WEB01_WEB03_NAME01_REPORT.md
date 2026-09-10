# XDM Android WEB01–WEB03 + NAME01 — Live Locator, capture metadata, and title naming

Date: 2026-09-10
Overlay: `xdm_android_live_locator_title_naming_v1.zip`
Status: implemented intermediate overlay
Depends on: `xdm_android_media_thumbnail_mime_presentation_v1.zip`

## Scope delivered

### WEB01 — Embedded media browser usability and compatibility

- Live Locator now exposes a live page summary with page title, host, HTTP/HTTPS transport, and loading/ready state.
- Back/forward/stop controls reflect the current WebView state instead of remaining blindly actionable.
- The address field supports the keyboard Go action.
- The media WebView enables image loading, wide viewport/overview behavior, built-in pinch zoom without on-screen zoom controls, normal cookie acceptance, and third-party cookies needed by embedded/CDN media sessions.
- Web contents debugging is enabled only for debug builds.
- Captured candidates are presented behind an expandable/collapsible candidate header so the page retains usable vertical space.

### WEB02 — Proper page failure and retry surfaces

- Main-frame WebView network failures now show an explicit in-page error surface with Retry rather than only changing a status string.
- Main-frame HTTP 4xx/5xx responses are surfaced separately from connection failures.
- SSL failures fail closed with `SslErrorHandler.cancel()`, show an actionable explanation, enter OBS01 Debug Center/Problems, and may raise the deduplicated XDM problem notification.
- Ordinary network/HTTP page failures enter the durable Problems ledger without notification spam.
- Renderer termination/crash now uses the same visible recovery surface and keeps the recreate-safe retry path.
- Page load, error, and renderer incidents remain correlated with the WebView operation id introduced by OBS01.

### WEB03 — Page artwork and playback metadata capture

- Live Locator DOM capture records video duration and poster artwork.
- When a video poster is unavailable, artwork discovery falls back to Open Graph, Twitter image metadata, `rel=image_src`, and bounded JSON-LD `thumbnailUrl`/`thumbnail`/`image` metadata.
- Artwork URLs are restricted to HTTP(S); blob/data/javascript artwork is ignored.
- Duration and thumbnail metadata flow through `MediaSniffingInput` into `MediaCaptureRecord` and survive process-local Live Locator state restoration.
- The Firefox/IronFox extension now carries poster/page artwork and playback duration from frame playback into the privileged candidate merge, candidate store, bounded capture-session handoff, Android envelope decoder, and Android media sniffing path.
- Page-provided playback metadata only enriches a candidate after privileged `webRequest` correlation; it does not gain request-header authority.

### NAME01 — Page-title-first captured-media filenames

- Captured media now uses its page title as the default filename identity instead of CDN/request basenames such as `videoplayback`, `master`, or opaque session tokens.
- The rule applies to both embedded Live Locator/WebView captures and Firefox/IronFox extension captures.
- The real media extension is derived from HLS/DASH kind, authoritative MIME type, then a recognized media URL suffix; common video/audio MIME types retain appropriate extensions.
- When no page title exists, the capture service's existing URL-derived title fallback still produces a useful filename.
- Ordinary direct-file Add Download intake is not redirected through this naming policy, so its existing authoritative/server filename behavior is preserved.

## Validation performed here

- All browser-extension Node test files pass, including new candidate-store and capture-session metadata assertions.
- A standalone Kotlin compile of the affected pure `core-model`/`core-utils`/`media` sources passes.
- A Kotlin smoke test verifies page-title-first MP4/HLS naming, MIME-backed MP3 extension fallback, duration propagation, and thumbnail propagation.
- XML parsing of `strings.xml` passes.
- The overlay-specific static validator checks the Live Locator recovery surface, extension metadata chain, title naming policy, tests, report, and manifest promises.

Full Android Gradle/lint validation is intentionally deferred to the final list-actions/release-seal overlay. This overlay is intended to be applied with `--no-validate`.

## Next overlay

`xdm_android_list_quick_actions_final_seal_v1.zip` — ACT01/ACT02 list/card action ergonomics, roadmap promise audit, and the explicit final validation gate.
