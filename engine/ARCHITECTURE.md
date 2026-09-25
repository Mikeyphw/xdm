# XDM Go engine package rules

The Go engine is the portable XDM authority. These rules are intentionally
established before the first domain aggregates so later overlays cannot
smuggle frontend/platform ownership back into the engine.

## Dependency direction

- `foundation/*` contains platform-neutral primitives and MUST NOT import
  XDM domain/runtime/store/backend/media packages.
- `domain/*` may depend on `foundation/*`, but MUST NOT depend on runtime,
  persistence, backend implementations, Android, .NET, or UI code.
- `runtime/*` may orchestrate domain objects but platform operations cross
  the explicit platform protocol introduced later in the roadmap.
- `cmd/*` is composition/smoke tooling and may import public engine packages.
- Android/JVM/Room/Compose/Avalonia/.NET dependencies are forbidden below
  `engine/`.

## Identity and time rules

- Stable entity identifiers are typed values; unrelated identifiers are not
  represented by interchangeable strings.
- Attempt/artifact generations and mutable-row revisions are independent,
  positive monotonic counters.
- Wall time and monotonic elapsed time are obtained through an injected
  `clock.Source`; timeout/backoff code must not infer elapsed time from wall
  clock timestamps.
- Random identifier entropy is supplied through `idgen.Generator`; tests use
  deterministic generators instead of global randomness.
- Canonical resource identity is opaque and non-secret. Raw/sensitive values
  use explicit wrappers whose ordinary string/JSON representation is redacted.

## Toolchain

The minimum supported Go language/toolchain baseline for this workspace is
Go 1.23. Newer compatible Go releases are expected to work. Devtool's native
Go runner owns restore/build/test/vet execution for `xgo_foundation`.

## Runtime and ABI boundary (XGO-08..10)

The Go runtime is headless. Managed hosts submit versioned byte commands and
consume ordered event frames. They do not receive Go object pointers and Go does
not synchronously callback into Kotlin/C#.

Platform-only work enters the same event stream as `platform.request` frames.
Hosts answer with `platform.reply` byte messages carrying request/session
identity. Platform broker session fencing prevents a reply from an older host
connection from satisfying a request owned by a newer connection.

The C ABI is intentionally small and handle-based. Output memory is C-owned and
must be released through `xdm_buffer_free`; tokenized allocation ownership makes
repeated frees of the same returned buffer harmless.

## Persistence authority (XGO-11..13)

`store/sqlite` is the canonical persistence boundary. It owns SQLite connection
configuration, schema migration, repository mutation semantics and revision CAS.
Domain packages never import the store; the store may import typed domain
identities but not runtime/backend/media/platform implementations.

SQLite connections use foreign keys, WAL journaling, NORMAL synchronous mode,
a finite busy timeout and extended result codes. The Go binding targets the
stable SQLite C ABI and intentionally has no third-party Go module dependency.
Host packaging supplies `libsqlite3`; `CGO_ENABLED=0` remains compilable but
returns `ErrUnavailable` rather than choosing a second persistence engine.

Schema v1 is normalized around Download/Request/Attempt/Artifact ownership and
contains future durable records for backend ownership/tasks, publication,
segments/checkpoints, verification, queues/schedules, media and diagnostics.
Mutable authoritative tables carry a positive integer `revision`. Repository
updates require the exact expected revision and increment it once; a stale write
returns `ErrStaleWrite` and is never implicitly retried.

## Durable execution authority (XGO-14..16)

Execution authority is represented by a monotonically increasing `AttemptGeneration` stored on the canonical Download row. Reserving a generation and creating the matching attempt row occur under one `BEGIN IMMEDIATE` transaction; a caller using a stale Download revision cannot reserve a second current generation.

Every attempt-owned mutation is fenced in the same transaction by `(download_id, attempt_generation, expected_revision)`. A preflight generation check outside the transaction is not authoritative and must not be used as a substitute for the write fence.

Backend byte authority follows this durable sequence:

1. reserve attempt generation;
2. create the staging identity;
3. persist an ownership claim;
4. create and persist a prepared backend task;
5. mark ownership ready;
6. atomically activate both ownership and task.

`AssertAuthoritativeWriter` succeeds only when the supplied generation is still current and both ownership and backend task are active. Superseded generations remain historical but lose write authority immediately.

Checkpoint blocks are integrity evidence, not progress counters. `checkpoint.Committer` enforces `WriteAt -> Sync -> read-back SHA-256 -> DB commit`. Failures before the DB commit leave no resumable checkpoint row even if bytes exist in staging. Exact idempotent re-commit is allowed; overlapping or conflicting committed evidence is rejected. Recovery inspection re-reads committed ranges and detects truncation or hash mismatch.
