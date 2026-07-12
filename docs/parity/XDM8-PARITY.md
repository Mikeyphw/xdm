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

The manifest inventories 78 feature contracts across the download engine,
browser integration, media, conversion, queues, settings, desktop integration,
diagnostics, localization, packaging, migration, quality and cutover.

Overlay 13 completed segmented transfer, Overlay 14 completed browser takeover,
Overlay 15 completed streaming media, Overlay 16 completed conversion, Overlay 17 completed queue scheduling and completion actions, Overlay 18 completed settings and workflow parity, Overlay 19 completed history and file management, and Overlay 20 completes localization and accessibility. Later parity overlays must
update the status, implementation paths and tests in the same commit that
supplies the behavior.

## Gate policy

The test project enforces:

1. Unique stable feature IDs.
2. A named legacy/public-contract source for every feature.
3. An assigned target overlay for all work.
4. Implementation and test references for every feature marked complete.

Overlay 21 enforces that every critical and high feature is complete,
intentionally replaced, or explicitly not applicable. It also resolves every
qualified implementation path and automated-test symbol against the repository,
checks the modern solution allowlist, and rejects known legacy application paths.

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

## Overlay 19 result

History and file-management parity is complete for the planned modern scope.
History-only removal is separated from destructive file deletion; completed
files can be moved, renamed, opened, revealed, or queued again; failed entries
can replace expired URLs without discarding partial data; source pages are
persisted; and bounded JSON/plain-text download lists can be imported or
exported without secrets. Optional retention prunes terminal history only, and
large startup restores publish the history in one indexed batch. Design and
safety details are documented in `docs/parity/HISTORY-FILE-MANAGEMENT.md`.
## Overlay 20 result

Localization and accessibility parity is complete for the planned modern scope.
The active Avalonia shell now consumes bounded retained language packs through
stable modern resource keys and English fallback, changes language and culture
at runtime, applies RTL flow, formats user-facing values with the selected
culture, and persists high-contrast, scaling, and screen-reader announcement
preferences. Primary workflows expose accessible names, live operation status,
tab navigation, and keyboard shortcuts. Design and safety details are documented
in `docs/parity/LOCALIZATION-ACCESSIBILITY.md`.


## Overlay 21 result

The final gate qualifies 100% of critical and high-priority contracts. Recorded
fixtures exercise legacy XML/JSON settings, history, scheduler state and
credential-redacted export. Unknown-length HTTP responses are covered through
the segmented-probe fallback. Linux and Windows CI now build, test, bootstrap
and smoke self-contained packages. Known legacy WPF, GTK, WinForms, CoreFx,
compatibility and test application paths are prohibited. FTP/FTPS transport and
in-process self-update are explicit modern replacements rather than silent gaps.
Details are documented in `docs/parity/FINAL-GATE.md`.
