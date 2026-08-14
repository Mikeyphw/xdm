# Master remediation Overlay 04+05 — request security + queue/runtime ownership

Base: applied Overlay 02+03 commit `9d1608ec`. Corrected artifact revision: v2; supersedes the v1 Phase 04+05 artifact.

This intermediate campaign overlay combines the next two dependency-ordered phases. Validation is deliberately deferred; apply it with devtool `apply-overlay ... --no-validate` and run the declared Gradle tasks only at the final campaign gate.

## Phase 04 — request security envelope

Master items: M-005, M-016, M-019, app-side M-029, M-031 approval-default portion, and app-side M-034.

Implemented contract:

- `DownloadRequest` represents direct, torrent, magnet, and metalink requests explicitly. Protocol kind is persisted inside the encrypted handoff and is inferred only from the request target; caller-controlled filename/MIME metadata cannot reclassify the protocol after review or restart.
- Explicit local/private-network and cleartext-credential approval is bound to the exact reviewed scheme/host/port/path/query digest. Replacing a URL never transfers approval to the replacement.
- The transfer guard validates the primary request and every mirror, rejects user-info/fragment injection, enforces a bounded header surface, resolves DNS before backend handoff, and respects Android cleartext policy. The native engine additionally binds OkHttp route selection to a request-specific DNS policy and checks the actual selected route address before exchange, preventing an unapproved private/special DNS result from becoming a transport route.
- Native HTTP rechecks every actual request/redirect target immediately before exchange, blocks HTTPS-to-HTTP downgrade, and requires the same exact approval scopes.
- Backend migration reconstructs its request from the durable encrypted handoff rather than the redacted Room URL, preserving headers, mirrors, request kind, exact target, and exact approval scope without widening it.
- App-side media page/manifest probing reuses the transfer request-security guard, follows redirects manually with a bounded hop count, and strips sensitive headers on cross-origin redirects.
- A reviewed media capture cannot mint approval for a different selected variant URL; only an already-approved exact scope is inherited.
- Legacy sensitive migration writes recognizable exact URLs/headers into encrypted request envelopes before JSON redaction, resets historical approvals, redacts by key and value, commits sidecars and the completion marker through Android `AtomicFile`, rewrites legacy `Download` rows only with a strictly newer same-generation timestamp, and refuses to write its completion marker if any scrub fails.
- Legacy v1 plaintext browser capture-media handoffs are rejected. Full browser-extension encrypted-runtime closure remains in Overlay 08 as planned.

## Phase 05 — queue/runtime ownership

Master items: M-008, M-009, M-018, M-025, M-026, M-043.

Implemented contract:

- App startup and boot/package restore both use `TransferExecutionRuntime.recoverForStartup()`, whose recovery phases are isolated and ownership-first. Queue admission stays durably closed until migration, ownership recovery, interrupted-transfer restoration, and condition-monitor startup succeed.
- Queue capacity is claimed in a Room transaction. `Connecting` is the durable reservation state; default `null` and `"default"` queue IDs share one capacity domain; compare-and-swap binds the claim to attempt generation and prior update time. Failed owner launch can release only the exact `updatedAt` queue-claim token it scheduled, and the release uses a strictly newer same-generation timestamp, preserving Overlay 02's monotonic-write invariant without allowing an old failed launch to release a replacement claim.
- Explicit Pause All installs a committed process-independent admission gate before backend pause. Resume All clears only that user hold and re-enters normal queue policy.
- Pause/resume/cancel after restart reconcile durable backend ownership. XDM controls a verified live backend task, handles a safe resumable artifact, or moves the row to `RecoveryRequired` instead of pretending a Room-only control succeeded.
- The old public runtime `resume()`/`resumeAll()` bypass path is removed; resume requests re-enter durable queue admission, while existing owned backend tasks resume only inside the claimed execution path after ownership reconciliation.
- Android 14+ user-visible starts prefer UIDT. Older user-visible starts use the foreground data-sync service. Background/deferrable work and rejected/disallowed direct owners move to WorkManager ownership instead of blindly retrying a foreground-service start. Every UIDT/FGS/WorkManager owner re-proves the exact durable `Connecting`-row claim token and admission gate immediately before execution; backend attempt generation is established separately when runtime ownership is claimed; delayed owners cannot bypass Pause All or startup recovery. Claimed WorkManager ownership is keyed by download plus durable queue-claim token and uses unique-work `KEEP` semantics, so a duplicate delivery for the same claim cannot replace an already-running owner while a later claim is not suppressed by stale work. Runtime, queue-hold, startup-recovery, and backend-migration Download transitions all advance the same-generation timestamp causally, preserving Overlay 02 stale-write protection.
- Worker and UIDT terminal paths use persisted idempotency and durable collision-free system IDs for terminal notifications.
- Retry identity binds to attempt generation and failure content, not mutable timestamps. Retry deadlines schedule one-time WorkManager wakeups rather than relying only on the periodic sweep.
- Queue policy fails closed when required battery/free-space facts are unknown, validates schedule time/day structure, checks the actual destination storage when the URI contract exposes it, and applies unbounded aging so low-priority work cannot starve forever.
- Notification Resume/Retry actions re-enter queue policy; the broadcast receiver never starts a foreground service directly.
- System-owned worker/service/job teardown requests backend pause without installing the user's persistent Pause All hold. A process-local per-download mutex serializes stale stop callbacks against replacement queue-claim installation, while the durable Room token remains authorization authority; WorkManager, FGS, and UIDT teardown can pause only their exact queue claim rather than a newer owner of the same download or transfers owned by another mechanism.

## Deferred work boundaries

This overlay does not consume later roadmap phases. Backend resume/migration artifact reconstruction beyond the request-security envelope remains Overlay 06; storage/finalization/publication remains Overlay 07; full browser-extension secure runtime remains Overlay 08; full media execution/destination/dispatch work remains Overlay 10.

## Final validation contract retained in artifact

- `:app:compileDebugKotlin`
- `:app:testDebugUnitTest`
- `:app:lintDebug`

Those tasks are intentionally not run for this intermediate overlay.
