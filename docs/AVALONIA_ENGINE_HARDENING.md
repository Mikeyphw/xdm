# Avalonia download-engine hardening

This phase hardens the modern .NET 10 download path without restoring or building the legacy WPF, GTK, WinForms, or MSIX projects.

## Included

- Strong ETag and Last-Modified resume validators persisted with download history.
- `If-Range` requests for safe continuation of partial files.
- Strict `Content-Range` start validation.
- Safe restart from byte zero when a server ignores Range or rejects the validator.
- Exponential retry with jitter for transient HTTP, timeout, and truncated-stream failures.
- Content-length verification before finalization.
- Disk-space preflight with a bounded safety margin.
- Write-through partial-file flush before finalization.
- Atomic `.finalizing` marker and startup recovery after interrupted moves.
- Last-known-good backup for atomic download-history checkpoints.
- Fault tests for ignored ranges, changed validators, truncated responses, disk exhaustion, finalization recovery, and corrupt primary history.

## Deliberate limits

- This remains a single-stream downloader. Multi-segment acceleration is a later phase.
- Unknown remote sizes cannot be disk-preflighted before transfer.
- Request secrets are still intentionally excluded from persisted history.
