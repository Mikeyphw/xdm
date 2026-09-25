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

## Verification, publication and recovery authority (XGO-17..19)

Transfer completion is not artifact completion. A successful verification is the
only operation allowed to allocate a new `ArtifactGeneration`, and the passed
verification record, verified artifact row, Download current-artifact pointer
and Download revision advance commit in one SQLite transaction. Failed
verification is append-only diagnostic evidence and never creates or advances an
artifact. Verification and publication mutations are fenced to the current
`AttemptGeneration`.

Publication is a durable saga because platform storage commits can occur outside
SQLite. The authoritative sequence is:

1. `PREPARED`;
2. `PLATFORM_COMMIT_REQUESTED`;
3. `PLATFORM_COMMITTED`;
4. `ENGINE_COMMITTED`;
5. `CLEANED`.

Every publication owns a durable idempotency key. A key cannot be claimed by a
second Download. Repeating the exact platform receipt is idempotent; a conflicting
receipt is rejected. A crash after requesting a platform commit is ambiguous, so
recovery asks the host to inspect the receipt instead of blindly publishing a
second copy. The Download reaches `completed` only when a verified current
artifact with a durable platform receipt is committed by the engine.

Recovery is storage-driven and deterministic. It classifies durable state as
`active_owned_backend`, `backend_missing`, `stale_ownership`,
`current_checkpoint_recoverable`, `corrupt_checkpoint`,
`verified_artifact_unpublished`, `platform_committed_engine_uncommitted`,
`completed`, or `unrecoverable`. Checkpoint byte inspection crosses a
`CheckpointResolver` boundary so the engine does not assume Android SAF or
Desktop host storage identities are direct filesystem paths. Late completion
from a stale backend generation is diagnostic-only and can never regain
execution authority. Recovery diagnostics are deterministic and idempotent, and
repeated recovery after terminal cleanup does not mutate authoritative state.

## Canonical request and route security (XGO-20..22)

Network execution begins from a canonical `request.NetworkIntent`, not from host-specific HTTP objects. The intent keeps the active transport URL separate from opaque logical `resource.Identity`, preserves mirrors/validators/checksums/source metadata/backend preference, and stores body/credential material only as opaque references. Runtime body and secret bytes intentionally cannot be JSON serialized.

Persisted non-secret headers pass an engine-owned admission policy. CR/LF injection, transport-owned fields (`Host`, `Content-Length`, connection framing), and raw sensitive credential headers are rejected. Trusted engine requests may preserve safe custom headers; external handoffs use a conservative allowlist. Authorization, cookie and proxy authorization are distinct credential-reference kinds with explicit origin/resource/path scope, and destination forwarding recomputes scope instead of copying credential state through redirects.

Resolved addresses are classified as public, private, loopback, link-local, reserved, multicast or unspecified after IPv4-mapped IPv6 normalization. Any non-public candidate requires a durable scoped approval bound to the exact RequestID, logical resource, target URL scope and address class. Approval is never a global `allow private network` boolean and cannot be reused for a mirror, redirect, another request or another logical resource. XGO-23 will bind this approved candidate set to the actual dial path.

## Bound dial, redirect and platform transport security (XGO-23..25)

DNS approval is inseparable from the actual socket endpoint. `security/dial`
resolves a hostname once, passes that exact candidate set through route policy,
and gives the connector literal IP:port endpoints only. The transport is never
allowed to re-resolve the hostname after approval. The original hostname is
retained separately as HTTP Host/TLS server name/certificate identity. Candidate
fallback is limited to the approved set; resolver host mismatch, mixed
unapproved routes, or rebinding attempts fail before any socket is opened.

Redirects are an engine-owned security state machine. Every hop canonicalizes
the target, re-runs route evaluation, recomputes scoped credential forwarding,
re-checks cleartext policy, applies explicit HTTP method/body replay semantics,
and enforces bounded loop/count state. Cross-origin credentials are stripped by
scope rather than copied and then removed heuristically. HTTPS-to-HTTP and any
other cleartext hop require an explicit platform policy result; sensitive query
material or credential references additionally require exact-target cleartext
credential approval. Redirect diagnostics remove query strings and fragments.

Platform networking policy is explicit rather than inferred from the Go host.
`security/transportpolicy` requests cleartext and system/PAC proxy decisions via
the typed platform broker. Missing or malformed replies fail closed. The proxy
model distinguishes direct, HTTP, SOCKS, system and host-resolved PAC decisions;
proxy credential references are a separate class and are never part of origin
credential forwarding. TLS planning preserves the original hostname, requires
TLS 1.2 or newer, and makes the root-store strategy explicit (`platform_roots`,
`bundled_roots`, or `custom_roots`) without weakening engine-level policy.

## Segmented transfer, sparse resume and retry authority (XGO-29..31)

Conventional segmented HTTP uses one preallocated random-access staging artifact.
The segment planner partitions the known representation into non-overlapping
checkpoint-aligned ranges and persists that partition under the current
`AttemptGeneration`. Workers may finish out of order, but worker exit is not
completion evidence: a segment reaches `completed` only when SQLite verifies
that committed checkpoint blocks cover the segment interval exactly without
holes. Range support is checked before segmented evidence is created; a server
that ignores Range may fall back to the single-stream executor only before any
segmented checkpoint is authoritative.

Resume is block-evidence driven rather than file-length driven. The planner
read-backs and hashes every persisted committed checkpoint, durably invalidates
truncated/corrupt/incompatible rows under the current-generation fence, and
builds missing byte ranges from the complement of still-valid sparse evidence.
Later valid blocks remain reusable when an earlier block is corrupt. A changed
representation invalidates the old committed evidence; a `200` response to an
If-Range resume request is treated as a clean-restart signal before response
bytes are written. Invalidated checkpoint identities may be replaced only after
new bytes pass the normal write/sync/read-back/hash/DB-commit sequence.

Retry policy is canonical engine policy, not an HTTP-loop concern. It consumes
typed failure category/disposition, attempt count, Retry-After, queue policy,
network availability, replayability and explicit user override and produces one
of `retry_now`, `retry_at`, `hold`, or `terminal`. Backoff uses injected clock
and jitter sources. The chosen decision, including an absolute retry deadline,
is serialized into the failed attempt's durable failure payload through the
attempt revision CAS/current-generation fence; restart reloads the recorded
deadline instead of recomputing it from the new process clock.
