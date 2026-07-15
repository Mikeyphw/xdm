# Phases 2 and 3 — Backend ownership and native HTTP foundation

## Phase 2

- Backend-neutral task lifecycle, query, remove and shutdown contract.
- Capability-based automatic backend recommendation.
- Transactional destination claims stored in Room schema v3.
- Ownership generations reject stale release or adoption operations.
- Native and aria2 backends use distinct partial identities.
- Coordinator releases claims when backend creation fails.

## Phase 3

The native engine now supports:

- HTTP and HTTPS through one shared OkHttp client.
- Metadata discovery with HEAD and byte-range fallback.
- Redirects, custom headers and OkHttp authentication/proxy hooks.
- Single-stream and segmented range downloads.
- `.xdm.part` files and atomically replaced JSON checkpoints.
- Resume validation using length, ETag, Last-Modified and range behavior.
- Changed-remote detection before writing resumed bytes.
- Strict Content-Range validation.
- Exponential retry with jitter for I/O, 429 and 5xx failures.
- Periodic durable checkpoint and file flushes.
- Atomic final rename with a safe fallback.
- Pause, resume, cancel, remove, query and shutdown operations.

Phase 3 intentionally writes only regular file destinations. MediaStore and SAF destination writers are Phase 5, while Android foreground/UIDT ownership is Phase 4.
