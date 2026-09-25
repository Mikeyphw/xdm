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

## Native transfer resource arbitration (XGO-32)

Native HTTP resource use is coordinated by one central `transfer/arbitration.Arbiter`.
Callers bind a stable transfer key containing Download identity, host, queue and
profile and pass the resulting handle into the HTTP executor. The same handle
owns both connection permits and byte-rate reservations; executors do not build
independent semaphores or token buckets.

Connection admission composes global, per-host and per-download limits. Waiting
requests are scanned for the first currently admissible key so a saturated host
does not head-of-line block an unrelated host. A connection lease is idempotent
on release, cancelled waiters are removed, and live limit changes wake queued
requests. The per-download connection scope is also the authoritative segment
concurrency cap for segmented HTTP.

Bandwidth accounting composes global and optional queue/profile/download byte
rates. Reservation deadlines are derived from injected monotonic time. At most
one future byte reservation per Download is active at a time, so a many-segment
Download cannot reserve an arbitrarily long run of global bandwidth ahead of an
already-active peer. Queue/profile/download rates are policy hooks for the later
scheduler/profile work; XGO-32 does not make scheduler policy itself authoritative.
Live changes apply to subsequent reservations without rewriting already-promised
grant times.

Diagnostics snapshot active/peak connections, acquisition/wait/cancellation
counts, total bandwidth requests/bytes/wait time, per-download accounted bytes
and the current limits revision. Snapshot maps are detached from internal state.

## Native transfer verification, selective repair and finalization (XGO-33..35)

Transport byte completion is not artifact completion. Native HTTP now ends in the
nonterminal `transport_complete` attempt state. That state is durable across
process death and is the only state eligible for final verification. Selective
repair may move `transport_complete -> running -> transport_complete`; only a
successful verified-artifact transaction may move the attempt to
`produced_artifact`.

`transfer/checksum` owns bounded streaming whole-artifact hashing. Supported
algorithms are explicitly enumerated (`md5`, `sha1`, `sha256`, `sha512`) and
expected values are normalized from typed sources such as user input and
Metalink metadata. Verification is cancellation-aware and records canonical
lowercase hex plus the verifier version. A checksum mismatch creates an
append-only failed verification record but no ArtifactGeneration.

`transfer/repair` derives repair ranges from checkpoint read-back evidence, not
from file length or segment-worker memory. Representation compatibility is
checked before any durable invalidation. Only invalid committed checkpoint rows
are revoked; exact damaged byte ranges are then re-fetched through the normal
HTTP range/If-Range path, recommitted through the write/sync/read-back/hash
checkpoint contract and followed by full-artifact verification. A changed
representation aborts selective repair and leaves restart policy to the normal
representation/resume layer.

`transfer/staging.File` is the write-freeze boundary used during finalization.
Authoritative WriteAt/Sync/Truncate operations share a read lock; finalization
acquires the exclusive freeze and waits for in-flight writes to leave before it
reads any verification bytes. Verification reads remain possible while frozen.
The freeze remains held until the verified-artifact transaction and publication
preparation decision complete.

`transfer/finalize` verifies the current `transport_complete` generation, then
uses SQLite to atomically append the successful verification record, create the
next ArtifactGeneration, advance the current Download artifact pointer and
promote the source attempt to `produced_artifact`. Publication preparation is a
separate durable saga step. A crash after ArtifactGeneration creation but before
publication therefore leaves a verified `unpublished` artifact that recovery can
resume. `PreparePublication` additionally requires the source attempt to be
`produced_artifact`, so a transport-complete or failed-verification attempt can
never become publishable through a parallel caller.

The native-Termux Gate-04 workflow deliberately does not invoke Go's race
detector because `-race` is unsupported on Android/arm64. Deterministic transfer,
checkpoint, arbitration, repair and finalization stress remains part of the
native gate. A supplemental supported-host race qualification covers
`engine/transfer/...` and `engine/store/checkpoint`; it is evidence, not a false
Android capability claim.

## Replayable POST/body backend semantics (XGO-36)

The backend layer consumes the canonical `domain/request.NetworkIntent`; it does
not define a second request model. Request bodies are persisted only as an opaque
reference plus an explicit source kind: `immutable_bytes`, `immutable_file`,
`secret_reference`, or `one_shot`. The first three kinds are replayable runtime
sources. `one_shot` is represented so intake can reject it deterministically,
but durable download execution does not accept it.

Replayability and byte-range resumability are separate properties. A replayable
POST may be retried because its body can be opened again, but retry starts the
response from byte zero. Only a bodyless GET may carry a non-zero checkpoint
offset into the HTTP executor and receive engine-owned `Range`/`If-Range`
headers. A POST plan with a non-zero start offset fails before network I/O.

`backends/http.Factory` resolves body and credential references only at runtime,
applies admitted non-secret headers, and supplies a request factory to the shared
checkpointed HTTP transfer executor. Replayable requests receive a `GetBody`
re-opener so preserving redirects or retry execution can obtain fresh material.
The canonical redirect state machine remains authoritative: 301/302/303 may
rewrite POST to GET and drop its body, while 307/308 preserve method/body only
when the body is replayable and re-run credential/route/cleartext policy.

Safe backend diagnostics contain the query-stripped URL, method, admitted header
metadata, redacted credential markers, body kind/replayability/content type and
optional length. Body references, body bytes, query secrets, credential
references and resolved credential material are excluded by default.

## FTP/FTPS backend adapter (XGO-37)

FTP/FTPS execution consumes the same canonical `domain/request.NetworkIntent`
used by HTTP. The transport admits bodyless `GET` semantics only; it does not
introduce an FTP-specific request aggregate. Authenticated FTP adds a canonical
`ftp_password` credential reference with a non-secret principal. Password bytes
remain runtime-only secret material. Anonymous login is explicit and
deterministic when no matching FTP credential reference exists.

`backends/ftp` provides a production passive-mode client and a protocol-neutral
adapter. Plain FTP uses the canonical normalized `ftp` origin. `ftps` denotes
implicit TLS (default port 990); after login the control channel negotiates
`PBSZ 0` / `PROT P`, and the passive data channel is TLS-protected before
payload bytes are exposed to the transfer loop. EPSV is preferred and PASV is a
fallback; any advertised PASV host is ignored in favor of the already-approved
control host so a server cannot redirect the data channel to a different host.
Unsupported SIZE/REST/passive/TLS capabilities are represented by typed adapter
errors and mapped into the engine failure taxonomy.

FTP transfer bytes use the existing durable checkpoint contract: the adapter
acquires the same central connection/byte limiter interface, writes only through
`checkpoint.Committer`, and moves the shared attempt lifecycle to
`transport_complete` only after the server size and committed byte count agree.
Resume uses `REST` from the canonical committed offset. Disconnects become the
existing retryable network failure category; representation-size disagreement
becomes `representation_changed`.

The SQLite attempt lifecycle is now physically owned by
`transfer/lifecycle` rather than `transfer/http`. HTTP keeps a compatibility
alias, while FTP consumes the same protocol-neutral implementation. Transport
completion remains nonterminal: whole-file checksum verification,
ArtifactGeneration creation, and publication preparation still run through the
existing `transfer/checksum` and `transfer/finalize` path. No FTP-specific
verification or publication state exists.

## Metalink canonical request expansion (XGO-38)

Metalink is an input-metadata format, not a downloader. `transfer/metalink`
parses a Metalink document and expands each described file into the existing
canonical `domain/request.NetworkIntent`. No Metalink attempt, checkpoint,
retry, verification, backend-ownership or publication lifecycle exists in
parallel with the engine.

Whole-file expected size and checksums are integrity inputs. Hash admission is
shared with `transfer/checksum`, so Metalink cannot claim support for an
algorithm the verifier cannot execute. Conflicting expected size or a differing
digest for the same algorithm fails before execution rather than choosing one
source silently. Existing request integrity evidence and Metalink evidence are
merged only when compatible.

Metalink source URLs become one canonical transport URL plus the ordinary
mirror list. The parser retains source order and priority/location metadata;
expansion orders lower RFC-style priority first, uses higher legacy preference
when present, and then delegates URL normalization and duplicate suppression to
`NetworkIntent`. Generated requests are bodyless `GET` operations. Backend
compatibility/selection remains a later, separate policy boundary.

File metadata includes the suggested relative name, identity, description,
version, languages and operating systems. Suggested names are not filesystem
authority: absolute paths, traversal components, backslashes and Windows-drive
forms are rejected at parse time. Legal relative subdirectories remain metadata
for later host publication policy. Multiple files in one document receive
deterministic distinct child resource identities so one logical resource cannot
accidentally represent several artifacts.

## Exact backend compatibility and deterministic selection (XGO-39..40)

Backend routing consumes the existing canonical `domain/request.NetworkIntent`.
`backends/router.Operation` supplements that request only with execution
requirements that are not request identity: destination kind, resolved proxy
mode, media shape, resume requirement and selective-repair requirement. It does
not duplicate URL, method, headers, credentials, body, mirrors or integrity
metadata.

Every executable backend implements one inspection surface: `CanExecute` returns
an exact `CompatibilityResult`, while `Preflight` enforces the same result at the
execution boundary. Hard rejects use typed dimensions for protocol,
method/body, destination, credential mode, proxy, media shape, resume and mirror
semantics. Soft hints may express a preference, but cannot make an incompatible
operation executable. The current aria2 compatibility contract is deliberately
narrow: bodyless HTTP/HTTPS/FTP GET to staging with direct networking and no
captured credential/header semantics. Capability expands only when later aria2
RPC/ownership work proves the corresponding execution path. Native currently
owns canonical HTTP GET/POST and FTP/FTPS execution, including request forms
that need the existing native credential and platform-stream semantics.

`backends/router.Select` is the sole backend-choice policy. Its inputs are the
compatibility results plus canonical user preference/fallback policy, runtime
availability, health, operation shape and migration cost. It produces a stable
selected backend, a typed reason, an explanation and the complete candidate
rejection evidence. Identical inputs therefore produce identical decisions;
frontend surfaces do not carry their own backend heuristics.

Before execution starts, the chosen decision is persisted as an attempt-scoped
safe diagnostic event in the existing SQLite `diagnostic_events` store. The
write is state-fenced to the `reserved` attempt and cannot change the durable
`BackendKind`. Once an attempt has started, selection keeps it bound to the
existing backend and marks every alternative as requiring explicit migration.
Backend migration therefore remains a separate lifecycle operation rather than
an implicit side effect of routing.

## Generic aria2 JSON-RPC client (XGO-41)

`backends/aria2` owns aria2 JSON-RPC protocol mechanics only. It does not start,
restart, discover or otherwise own an aria2 process. Runtime identity and durable
task ownership remain separate lifecycle concerns for XGO-42.

Each call emits JSON-RPC 2.0 with a monotonically increasing string request ID
and requires the response ID to correlate exactly before either result or error
is accepted. A bounded per-call context timeout is mandatory. HTTP failures,
timeouts, malformed envelopes/results, correlation mismatches and remote RPC
errors are typed separately. Raw malformed/HTTP response bodies are never copied
into returned diagnostics, and a configured RPC secret is redacted from remote
error messages. The exact RPC endpoint refuses redirects so `token:<secret>`
parameters cannot be forwarded to another origin.

The client exposes add-URI, status, active/waiting/stopped lists, pause/force
pause, unpause, remove/force-remove, per-task option changes, global option
read/change, session save, graceful/forced shutdown, version and health probes.
Status parsing normalizes numeric string fields into non-negative integers and
retains canonical task/file/URI evidence needed by later reconciliation.

Aria2 options use a restricted textual representation: scalar strings or string
arrays such as repeated headers. This prevents Android/Desktop JSON-type drift
and makes the wire contract deterministic. Process launch is deliberately absent
from the package; the target-local `aria2_rpc_lab` may launch an installed
`aria2c` only as optional validation evidence, never as engine ownership.
