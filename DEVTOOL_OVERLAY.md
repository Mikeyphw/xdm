# XDM Overlay 16 — conversion parity

Base: confirmed successful commit `2f03229`

Target: `xdm_modern`

Active solution: `app/XDM/XDM.Modern.sln`

## Included

- Fixed capability presets for MP4 remux, H.264/AAC MP4 transcode and MP3
  extraction.
- FFprobe stream inspection and codec/container compatibility validation.
- Safe FFmpeg process execution through `ProcessStartInfo.ArgumentList` with
  shell execution disabled.
- Structured FFmpeg progress parsing, bounded diagnostics, cancellation and
  process-tree termination.
- Atomic output publication that preserves an existing destination on failure.
- Sequential conversion queue with progress, cancellation and removable
  terminal history.
- Avalonia conversion page, completed-download handoff and optional
  post-streaming conversion.
- Deterministic tests and executable parity-ledger updates.

## Safety properties

Conversion requests select only application-owned preset IDs. No user command,
executable or FFmpeg argument fragment is accepted. Source and destination paths
must differ, output extensions must match the preset, FFprobe validates required
streams, and stream-copy codecs are allowlisted. FFmpeg writes to a private
same-directory temporary file; XDM publishes it only after a successful,
non-empty result.

## Validation scope

Only the modern solution is allowed:

```bash
dotnet restore app/XDM/XDM.Modern.sln
dotnet build app/XDM/XDM.Modern.sln --configuration Release --no-restore
dotnet test app/XDM/XDM.Modern.sln --configuration Release --no-build
```
