# XDM 8 functional parity ledger

This ledger is executable through `XDM.Parity.Tests`. The source of truth is
`docs/parity/features.json`; this document explains how to maintain it.

## Status rules

- `complete`: implementation paths and automated tests are mandatory.
- `partial`: a useful slice exists, but legacy behavior is still missing.
- `missing`: no modern implementation is available.
- `intentionallyReplaced`: modern behavior supersedes the legacy feature.
- `notApplicable`: the legacy behavior is no longer relevant.

## Current baseline

The manifest inventories 66 feature contracts across the download engine,
browser integration, media, conversion, queues, settings, desktop integration,
diagnostics, localization, packaging and migration.

Overlay 13 completed segmented transfer, Overlay 14 completed browser takeover,
Overlay 15 completed streaming media, Overlay 16 completed conversion, Overlay 17 completed queue scheduling and completion actions, and Overlay 18 completes settings and workflow parity. Later parity overlays must
update the status, implementation paths and tests in the same commit that
supplies the behavior.

## Gate policy

The test project enforces:

1. Unique stable feature IDs.
2. A named legacy/public-contract source for every feature.
3. An assigned target overlay for all work.
4. Implementation and test references for every feature marked complete.

The final parity gate will additionally require every critical and high feature
to be complete, intentionally replaced, or explicitly not applicable.

## Overlay 13 result

`download.segmented-transfer` is now complete. Fresh eligible downloads probe
range support, split the remote object into bounded non-overlapping ranges,
resume each segment from its own durable file, merge in deterministic order,
and fall back to the established single-stream path when the server ignores the
probe range.

## Overlay 14 result

Browser takeover parity is complete. The Firefox and Chromium-family extensions
now use the versioned native-message protocol, forward bounded request metadata,
apply shared capture rules, expose manual and download-all context commands, and
cancel the browser transfer only after the modern app queues it. Native-host
installation now supports compatibility inspection, repair and uninstall.
Security and deterministic fixture coverage are documented in
`docs/parity/BROWSER-TAKEOVER.md`.


## Overlay 15 result

Streaming-media parity is complete for the planned native and provider scope.
The modern application now acquires HLS and DASH streams, refreshes live
manifests, resumes fragment checkpoints, decrypts supported AES-128 HLS, selects
audio/video/subtitle representations, discovers yt-dlp and FFmpeg safely, and
finalizes streams through no-shell FFmpeg argument lists. Design and safety
details are documented in `docs/parity/STREAMING-MEDIA.md`.

## Overlay 16 result

Conversion parity is complete for the planned modern scope. Fixed capability
presets now provide validated MP4 remux/transcode and MP3 extraction. FFprobe
checks streams and codec/container compatibility before a no-shell FFmpeg
process starts. Conversion jobs run sequentially with structured progress,
cancellation and atomic output publication. Completed downloads and media
captures can be sent directly to the conversion queue. Design and safety
details are documented in `docs/parity/CONVERSION.md`.

## Overlay 17 result

Queue scheduling and completion-action parity is complete for the planned
modern scope. XDM now stores multiple independent queue schedules, detects
persisted missed windows, preserves overnight behavior, and supports per-download
priority. Completed scheduled runs can scan output files and then, after a
visible cancellable countdown, exit XDM, invoke a supported platform power
action, or launch one configured executable without a shell. Platform support,
timeouts and safety constraints are documented in
`docs/parity/QUEUE-COMPLETION.md`.



## Overlay 18 result

Settings and workflow parity is complete for the planned modern scope. XDM now
persists bounded timeout, retry, connection, segmentation, proxy, authentication,
and download-behavior defaults. The shared HTTP stack applies system, direct, or
manual authenticated proxy settings after restart. A versioned modern settings
export redacts secrets by default, while the importer accepts modern JSON and
legacy JSON, properties, or XML fixtures without executing source content.
Migration behavior and supported keys are documented in
`docs/parity/SETTINGS-WORKFLOW.md`.
