# XDM Android UIX R4 Overlay

UIX R4 installs consumer-first Media and playable-first Library workflows on top of the validated UIX R3 Downloads and Add baseline.

## Included

- Media page header with Paste page URL and plain privacy guidance.
- Reviewable capture cards with common quality chips, full video/audio/subtitle selection, estimated size, and explicit Download.
- Compact Recently queued progress rows.
- Library filters for All, Video, Audio, and Recently added.
- Compact list on phones and adaptive grid on medium/expanded screens.
- Play, Resume download, Retry, More, open-file, and safe record-removal actions.
- Media3 support details shown only after an actual playback error.
- Pure planner tests, UI source contracts, static validator, architecture documentation, CI, and final-gate integration.

## User/developer boundary

Resolver history, raw media/session values, Termux and yt-dlp controls, dispatch plans, queue telemetry, worker bridges, privacy audits, validation decks, sidecar JSON, and routine player diagnostics are not rendered in normal Media or Library workflows. The underlying redacted developer and support tools remain preserved.

## Preserved

Room remains schema 14 and the app remains `versionName 0.20.0-rc08` / `versionCode 21`. Internal routes, native, aria2, Termux, yt-dlp, Media3, queue intelligence, schedules, recovery, external handoff, resolver state, and track-selection persistence are unchanged.

## Dependency

Apply after `xdm_android_uix_r3_downloads_add_workspace_overlay.zip`. UIX R5 will redesign Activity, Settings, and the Developer options boundary.
