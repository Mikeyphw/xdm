# Phase 9: Termux Media and Conversion Pipeline

Phase 9 ports the desktop media/conversion workflow to Android through the existing Termux `RUN_COMMAND` bridge.

## Rules

- No chroot support is added.
- Root is not required.
- The UI must expose typed media actions only, never a raw shell field.
- `yt-dlp` is used for metadata extraction and media downloads.
- `ffprobe` is used for stream inspection.
- `ffmpeg` is used for remux, fast-start MP4, and audio extraction jobs.
- Jobs are tracked in an Android-visible media pipeline summary so users can copy diagnostics.
- Long-running work is launched through Termux and recorded through the existing Termux result store.

## User surfaces

- Media route: Termux media pipeline summary.
- Media cards: yt-dlp metadata, FFprobe, yt-dlp download, fast-start MP4, and audio extract actions.
- Diagnostics: media job counts and recent Termux run records.

## Safety

The app generates command templates from structured models. User-entered raw shell text is never executed.
