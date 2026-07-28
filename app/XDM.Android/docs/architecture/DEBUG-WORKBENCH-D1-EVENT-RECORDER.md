# Debug Workbench D1: Event Recorder Foundation

D1 adds the backend-only foundation for the future XDM Debug Workbench. It is intentionally small and privacy-first: runtime code can emit structured debug events, but no UI is introduced yet and nothing is uploaded automatically. No automatic upload is performed.

## Delivered pieces

- `DebugEvent`, `DebugArea`, and `DebugSeverity` define the stable event envelope.
- `DebugEventRecorder` gives app, media, scheduler, and future UI code an injectable sink.
- `NoOpDebugEventRecorder` is the default so normal runtime paths keep zero storage side effects.
- `RollingJsonlDebugEventRecorder` writes bounded private rolling JSONL `current.jsonl` timelines and keeps only the last five rotated sessions.
- `DebugRedactor` sanitizes URLs, headers, bearer/basic credentials, cookies, token/signature/session/key query values, and free text before a line is written.
- `exportSupportBundle()` creates a local ZIP skeleton with `debug-session.jsonl`, `debug-metadata.txt`, and `redaction-report.txt`.

## Safe instrumentation hooks

D1 instruments only review/debug facts, never transfer start operations:

- Add Download intake draft creation/rejection.
- MediaSniffingEngine shared-sniffer summaries.
- Media batch intake summaries.
- External media review planning.
- Completed notification open fallback reasons.

## Privacy contract

The debug recorder must not write raw `Authorization`, `Cookie`, `Set-Cookie`, token, session, signature, password, key, or auth-like values. URL debug output keeps scheme, host, path, and non-sensitive query values while redacting sensitive query values.

## Storage contract

The D1 recorder is file-backed JSONL under app-private storage. It deliberately avoids Room, migrations, broadcast exports, and automatic network upload. Users must explicitly copy/share a future support bundle.

## Next phases

D2 can add the Debug Workbench shell and wire a real `RollingJsonlDebugEventRecorder` into the application. D3 should add the Media Sniffing Lab on top of these events.


## R3 compile hygiene

The D1 contract tests use `createTempDirectory` instead of deprecated `createTempDir`, and legacy DownloadIntake planner tests construct the planner with a named `idFactory` argument so the optional debug recorder parameter never captures trailing lambdas.

## R4 redaction test fix

The redaction tests now assert value redaction while preserving user-provided safe-detail key casing. Runtime support bundles keep useful labels like `Authorization` while replacing the sensitive value with `<redacted>`.

## R5 redaction test fix

The redaction contract explicitly checks both `Authorization` and `Cookie` detail keys. The recorder preserves safe diagnostic labels and redacts only sensitive values, so support bundles remain readable without leaking credentials.
