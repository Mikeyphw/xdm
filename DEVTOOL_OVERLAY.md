# XDM Overlay 15 — streaming media parity

Base: confirmed successful commit `e2d5bc3`

Target: `xdm_modern`

Active solution: `app/XDM/XDM.Modern.sln`

## Included

- Native HLS master/media parsing and acquisition with alternate audio,
  subtitles, byte ranges, initialization maps and discontinuities.
- Native DASH MPD parsing with BaseURL inheritance, SegmentTemplate timelines,
  SegmentList and static/dynamic representation acquisition.
- Live HLS/DASH manifest refresh with a bounded capture duration.
- Atomic fragment checkpoints, deterministic resume, bounded retries and
  cancellation-aware backoff.
- Identity-key AES-128 HLS decryption with explicit or media-sequence IVs.
- Structured yt-dlp format discovery using a private temporary metadata config.
- FFmpeg discovery, compatibility health and stream-copy finalization.
- Avalonia format, audio, subtitle and live-duration workflow with progress and
  cancellation.
- Recorded deterministic fixtures and executable parity-ledger updates.

## Safety properties

Only HTTP and HTTPS media URLs are accepted. XML DTD processing is prohibited.
Manifest and key responses have explicit limits. Unsupported HLS encryption and
DRM are rejected. External tools use `ProcessStartInfo.ArgumentList` with shell
execution disabled. Sensitive headers do not appear in command-line arguments.

## Validation scope

Only the modern solution is allowed:

```bash
dotnet restore app/XDM/XDM.Modern.sln
dotnet build app/XDM/XDM.Modern.sln --configuration Release --no-restore
dotnet test app/XDM/XDM.Modern.sln --configuration Release --no-build
```
