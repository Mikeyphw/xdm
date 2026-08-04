# Bug Hunt Remediation Phase 2: Download Execution Correctness

This phase implements the execution-safety slice of the Android bug-hunt roadmap on top of Phase 1 r5.

## Covered contracts

- One execution job may own a download at a time; job registration is serialized per download.
- Pause, Resume, Cancel, Retry and Start/launch commands write a monotonic desired-control generation before backend lookup.
- Pause and Cancel cannot be lost during backend preparation: when no backend task exists yet, the runtime cancels the preparation job and stores the requested state.
- Failed backend tasks are retired before Retry creates a new attempt.
- WorkManager, JobScheduler and the foreground service no longer assume detached transfer ownership: stop and destroy paths request a durable pause.
- Active transfer summaries keep foreground ownership through Verifying.
- aria2 mappings, ownership, output, control files and metadata are released only after the RPC state change and session save have succeeded.
- Native checkpoints are flushed on explicit Pause where a flusher is available.
- Native resume fails closed when validators disappear, when redirect identity changes, or when range support required by a checkpoint disappears.
- Native normalized complete-but-unmarked checkpoint segments before resuming.
- Native honors expected length even when the server omits `Content-Length`.
- Native applies one request builder to HEAD, range-probe and payload GET requests.
- Native strips engine-owned external headers such as `Range`, `If-Range`, `Host`, `Connection`, and `Content-Length`.
- Native sends `If-Range` for resumed range requests when an ETag or Last-Modified validator is available.
- Native rejects compressed range payloads and HTML/XML/JSON error pages for binary/media targets.
- Native respects `Retry-After`, adds jitter, and coordinates host-level retry timing to avoid synchronized segment retry storms.
- Native removes OkHttp calls from the active-call registry after execution and cancels sibling calls on segment failure.
- Native reports current-attempt speed instead of counting all checkpoint-restored bytes as freshly transferred.

## Notes

This phase intentionally does not implement Phase 3's transactional destination publication or checksum-before-publication changes. It makes the transfer state machine and HTTP execution safer so Phase 3 can build a real finalization transaction on top.
