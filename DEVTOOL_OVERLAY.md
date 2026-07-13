# XDM Overlay — Media detection inbox and workflow overhaul

This overlay applies on top of commit `98133bd`.

## Detection inbox

- replaces disruptive browser-media navigation with a session detection inbox;
- groups each detected video/page with its complete stream catalog and source metadata;
- deduplicates repeated detections and keeps the newest catalog at the top;
- caps the inbox at 100 entries;
- supports refresh, removal, and clearing without affecting downloads;
- retains browser request headers, cookies, referer, and user agent for the selected item.

## Stream planning

- adds Best, bounded-resolution, smallest-video, and audio-only quality policies;
- adds preferred audio and subtitle language selection;
- keeps exact video/audio overrides and individual subtitle toggles;
- displays stream type, quality, bitrate, language, container, codec, encryption, protocol, and live/VOD status;
- estimates output size when duration and bitrate metadata are available;
- reads duration metadata from yt-dlp catalogs.

## Live capture safety

- adds independent duration and maximum-size limits;
- enforces the byte limit while streaming direct files, HLS fragments, DASH segments, and yt-dlp fragments;
- preserves existing fragment checkpoints for cancellation and recovery;
- removes bounded temporary files after failed direct transfers;
- keeps the existing seven-day duration and 10 TiB safety ceilings.

## FFmpeg and post-processing

- probes FFmpeg encoder capabilities for H.264, H.265, AV1, AAC, MP3, and Opus;
- reports toolchain capabilities beside yt-dlp health;
- preserves all existing remux, transcode, audio extraction, and device presets;
- keeps queued post-processing separate from the media download lifecycle.

## Quality

- fixes the existing `CA1859` warning by returning `Dictionary<string, string>` from `BuildAria2Headers`;
- exposes accessible names and stable automation IDs for every Media form control;
- keeps media quality options instance-backed to satisfy `CA1822` without suppressions;
- adds selection-policy, size-estimation, duration, byte-limit, FFmpeg-capability, headless, and UI-architecture tests;
- preserves native/aria2 ownership, resume integrity, browser security, smart queues, and rollback-safe updates;
- does not modify `docs/parity/features.json`.

## Validation

Devtool must restore, build, and test only `app/XDM/XDM.Modern.sln`. The build must have zero warnings and zero errors.
