# Phase 8D: Media Resolver and Format Selection Polish

## Purpose

Phase 8D makes the existing browser-free Media destination a first-class resolver workspace. External browsers and applications still provide URLs through explicit handoff. XDM probes, compares, reviews, and queues media without embedding a browsing engine or starting a transfer silently.

## Resolver flow

```text
External handoff or Add Download
  -> Media source
  -> yt-dlp / HLS / DASH probe
  -> Resolved streams
  -> Video, audio, and subtitle selection
  -> Redacted request-context review
  -> Existing queue and engine planner
```

The workspace exposes source, probe, streams, selection, review, and ready states. Failed, expired, and protected sources remain explicit diagnostic states.

## Rich format comparison

Resolved video rows show the metadata available from the existing `MediaVariant` contract:

- resolution
- codec
- MIME/container
- bitrate
- duration-based estimated transfer size
- HDR markers when present in resolver metadata
- compatibility, efficiency, quality, and compactness guidance

The UI never invents FPS, HDR, size, or codec values when the resolver does not provide enough evidence.

## Audio and subtitles

Audio and subtitle rows expose language, codec, bitrate, MIME type, forced status, and auto-generated status when those markers are present. Selections are carried into the existing `MediaTrackSelection`, queue specification, yt-dlp format selector, and execution planner.

Track selections are persisted in `MediaResolverSelectionStore`, outside Room schema 14. Only opaque local variant IDs are stored. URLs, cookies, authorization values, headers, and page content are never written to this preference store.

## Probe and session review

The probe card exposes:

- extractor or manifest type
- readiness
- number of resolved formats
- whether redacted session context exists
- refresh, failure, adaptive-stream, and protected-media warnings

Request context remains value-safe. Referrer hosts may be displayed, while cookie, authorization, token, signature, and signed-query values stay redacted.

## Protected media

Protected content is diagnostic-only. XDM may show metadata, manifest diagnostics, and the detected protection marker, but it does not bypass DRM and does not queue protected media.

## Recent resolutions

Recent resolver entries are derived from downloader media captures already stored by XDM. This is not browser history. Removing a media capture removes its persisted track selection, and no browsing session, tab, bookmark, page archive, or credential is introduced.

## Preserved contracts

Phase 8D does not change:

- six stable routes
- external browser handoff
- native, aria2, Termux, or yt-dlp execution ownership
- Phase 8C queue policy
- offline library or Media3 playback
- Android manifest intent ownership
- Room schema 14
- `versionCode 21`
- `versionName 0.20.0-rc08`
