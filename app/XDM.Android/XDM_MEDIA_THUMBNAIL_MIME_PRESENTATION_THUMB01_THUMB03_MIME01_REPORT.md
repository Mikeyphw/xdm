# XDM Android THUMB01–THUMB03 + MIME01 report

Date: 2026-09-10  
Overlay: `xdm_android_media_thumbnail_mime_presentation_v1.zip`  
Depends on: `xdm_android_observability_problem_reporting_v1.zip`

## Delivered

This overlay adds a single artwork presentation path instead of separate thumbnail behavior per screen. `XdmMediaArtwork` is now used by captured-media cards, recently queued media, download rows, and Media Library list/grid items.

Artwork preference is intentionally conservative and deterministic:

1. capture/resolver/variant thumbnail URL when available;
2. direct source URL only when the MIME/extension resolver classifies the item as an image;
3. completed local image decoding;
4. completed local video frame extraction;
5. MIME-backed icon fallback.

Remote artwork is loaded off the main thread, bounded to 6 MiB per response, downsampled before display, held in a 12 MiB memory cache, and persisted into a bounded 32 MiB app-cache directory. Cache keys are SHA-256 fingerprints rather than raw URLs. Failures are best-effort Trace events in the OBS01 recorder and never interrupt a download or capture flow.

## MIME presentation

`MimePresentationResolver` replaces the previous handful of hard-coded extension checks. MIME wins where authoritative, while extensions fill missing or generic MIME metadata. HLS (`.m3u8`) and DASH (`.mpd`) are explicitly classified as adaptive media so they do not appear as generic document files.

Dedicated presentation families include video, audio, image, PDF, archives, word-processing documents, spreadsheets, presentations, subtitles, playlists, Android/application packages, torrents, fonts, calendar/contact files, code/text, binary files, adaptive media, downloads, and generic files.

## Media linkage

A media capture's own `thumbnailUrl` remains first choice. If it is missing, the first persisted `MediaVariantKind.Thumbnail` is used. Downloads now receive thumbnail hints for their linked `MediaCaptureRecord.downloadId`, so the artwork shown during media review remains visible after queue admission. Completed Downloads can additionally render local image content or a local video frame.

## Scope boundaries

- No Room schema migration.
- No Coil/Glide/Picasso or other image-loading dependency.
- No automatic network fetch for arbitrary non-image download URLs.
- No remote video frame extraction.
- No cookies/Authorization headers are persisted or injected into thumbnail requests.
- WebView poster/OpenGraph/JSON-LD thumbnail discovery belongs to the next Live Locator overlay.
- Full Gradle/lint validation is intentionally deferred to the final roadmap seal; this intermediate overlay is intended for `--no-validate`.

## Local verification

The MIME resolver compiles independently with `kotlinc` and smoke checks cover adaptive media, PDF, APK/package, and accessibility labels. The overlay validator asserts cross-surface artwork wiring, bounded cache behavior, media-variant fallback, completed-local thumbnail generation, and MIME resolver coverage. The artifact is also apply-simulated against the OBS01-updated repository snapshot with source preimage hashes.
