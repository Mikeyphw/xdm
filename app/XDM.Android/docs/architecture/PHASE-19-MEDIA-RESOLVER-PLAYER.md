# Phase 19: media resolver and player

Phase 19 turns the existing Media route into a review-first resolver/player workspace without adding a new top-level route or changing Room schema v14.

## Scope

- HLS and DASH captures expose grouped pickers for video quality, audio tracks, and subtitle tracks.
- yt-dlp metadata probing is shown before the download action so the user sees title, duration, thumbnail availability, extractor, and format count.
- The planner produces a selected yt-dlp format expression from the chosen video/audio tracks.
- Referer, Origin, cookie-jar availability, and selected source URL are carried as a session handoff object for typed yt-dlp, aria2, or native planning paths.
- Diagnostics only print redacted session summaries. Raw cookies, Authorization headers, CSRF tokens, and signed query values must not appear in the app diagnostics summary.
- Direct completed media can be reviewed with a Media3 player card.
- Adaptive streams remain resolver-first before offline playback.
- Protected media detection is diagnostic-only. XDM does not bypass DRM and does not queue protected media.

## Clean-room note

The feature map was informed by the user-supplied Super Video Downloader baseline, but this overlay does not copy its code, assets, UI, or implementation. XDM keeps its own Kotlin/Compose planner, typed Termux command bridge, and Media route topography.

## Route topography

Browser, resolver, player, and library functions stay under the existing `Media` route. No `Browser`, `Resolver`, `Player`, `Tools`, or `Termux` top-level route is introduced.

## Acceptance gates

- `MediaDownloadPlanner` contains resolver models for track selection, picker groups, yt-dlp metadata preview, session handoff, and protected-media diagnostics.
- `Screens.kt` exposes the resolver UI, redacted session card, protected diagnostics panel, and picker groups.
- Termux yt-dlp commands accept extra arguments through typed model fields only.
- `TermuxMediaPipelineStatus.diagnosticsSummary()` includes only redacted session summaries.
- `Media3DirectPlayerCard` uses Media3 `ExoPlayer` and `PlayerView` for direct media.
- Tests verify track planning, metadata parsing, and no cookie/token leak in diagnostics.
