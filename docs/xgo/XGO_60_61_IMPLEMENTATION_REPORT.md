# XGO-60+61 Implementation Report — FFmpeg planning and external media tool execution

## Scope

This overlay implements XGO-60 and XGO-61 as one post-processing boundary. XGO-60 owns typed FFmpeg/FFprobe planning, while XGO-61 owns external media-tool job state, verification, and publication handoff.

## Delivered behavior

- Typed `FFmpegPlan` model with version, stable plan identity, input artifact roles, stream mappings, output expectations, verification policy, and persisted post-process state.
- Structured external tool requests with binary and `argv` arrays; no shell command string is produced by the media domain.
- Golden planning paths for remux, separate audio/video muxing, transcode, and subtitles.
- Injection-safe argument transport: hostile filenames are preserved as a single argv element while control characters are rejected.
- Plan serialization and restart parsing preserve plan identity.
- External media tool lifecycle covers progress, success, cancel, nonzero exit, timeout, crash, bounded diagnostics, and restart reconciliation.
- FFprobe-style verification enforces container, stream presence, duration tolerance, and malformed probe handling before publication.
- Verified results create a domain `Artifact` and publication `CommitRequest` handoff with a stable idempotency key.
- Crash after tool success before Go commit and crash during publication handoff retain safe recovery states.

## Validation evidence

- `go test ./engine/media`
- `go test ./engine/...`
- `go vet ./engine/...`
- `go run ./engine/cmd/xgo-media-audit --mode ffmpeg_plan_goldens`
- `go run ./engine/cmd/xgo-media-audit --mode ffmpeg_execution`
- Full media audit sequence through DASH plus FFmpeg nodes
- fixture lint
- fixture secret scan
- capability ledger audit
- `xgo_media` topology audit
- exact ZIP replay

## Gate status

XGO-60 and XGO-61 are complete. GATE-08 remains a separate checkpoint so the full media execution target can be audited independently.
