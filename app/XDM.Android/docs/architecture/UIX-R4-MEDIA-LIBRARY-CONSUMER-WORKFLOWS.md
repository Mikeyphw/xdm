# UIX R4: Media and Library Consumer Workflows

## Purpose

UIX R4 turns the Media and Library destinations into quiet, consumer-facing workflows. It preserves every resolver, queue, playback, Termux, yt-dlp, native, aria2, recovery, and support capability, but removes engineering dashboards from ordinary use.

## consumer-first Media

Media leads with captured items that still need review. Users can paste a page URL, see a privacy notice, choose a common video quality directly, open a complete video/audio/subtitle picker, review estimated size, and explicitly add the selected media to Downloads. Recently queued media appears as a compact progress summary beneath pending captures.

Resolver stages, raw media URLs, session values, cookies, authorization values, runtime launch plans, worker bridges, telemetry, privacy audits, phase labels, and validation decks are forbidden on the normal Media surface. Protected media remains diagnostic-only and XDM never claims to bypass DRM.

## playable-first Library

Library prioritizes completed media and playback readiness. All, Video, Audio, and Recently added filters are always available. Compact screens use a scan-friendly list; medium and expanded screens use an adaptive grid. Each item offers one primary Play, Resume download, or Retry action plus a More sheet for safe file management.

Sidecar JSON and routine Media3 diagnostics never appear during normal browsing or playback. Redacted support details become visible only after a real playback error. Removing a library record does not delete the downloaded file.

## Preserved contracts

- Six internal routes and five visible navigation destinations remain unchanged.
- No Room schema bump. Room remains schema 14.
- `versionName` remains `0.20.0-rc08`; `versionCode` remains `21`.
- Download engines, queue behavior, recovery, external handoff, resolver state, track selection persistence, Media3 playback, and developer diagnostics remain intact.
- UIX R4 depends on the validated UIX R3 Downloads and Add workspace.
