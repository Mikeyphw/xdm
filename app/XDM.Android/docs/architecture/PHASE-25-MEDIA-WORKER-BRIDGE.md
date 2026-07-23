# Phase 25: Media Worker Bridge

Phase 25 converts safe queue-action plans into worker bridge requests without starting work yet. It prepares the contract that later Android workers, UIDT jobs, WorkManager foreground workers, native request executors, aria2 adapters, and Termux yt-dlp adapters can consume.

## Bridge lanes

- Android UIDT worker for user-initiated Android 14+ transfers.
- WorkManager foreground worker fallback.
- Foreground dataSync service fallback for legacy execution.
- Native direct request adapter.
- aria2 launch adapter with transient input/session files.
- Termux yt-dlp adapter with typed arguments and cleanup-owned Netscape cookies.
- Blocked diagnostic requests for protected media or redaction failures.

## Guardrails

- No worker is enqueued in this phase.
- No raw shell string is rendered or stored.
- Durable job IDs are derived from capture ID and lane only.
- Notifications contain redacted title/policy/action text only.
- Sidecars stay redacted.
- Transient inputs are cleanup-owned after terminal state.
- No new top-level route and no Room schema migration.
- Validation remains deferred until the final media validation phase; apply with `--no-validate`.
