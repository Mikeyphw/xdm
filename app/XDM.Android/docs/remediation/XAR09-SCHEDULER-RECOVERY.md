# XDM Android XAR09 — Scheduler, Foreground Work & Retry-owner Reconciliation

**Overlay:** 9 of 17  
**Name:** XAR09 — Scheduler, Foreground Work & Retry-owner Reconciliation  
**Target:** `xdm_android`  
**Validation mode:** intermediate `--no-validate` overlay with focused source/behavior contract  
**Commit message:** `XAR09: fence scheduler recovery ownership`

## Scope

XAR09 owns the S09 scheduler/background execution findings from the final deduped audit ledger. It closes **21/21** canonical roots by converting startup/boot/package recovery, foreground execution, UIDT notification setup, queue retry identity, terminal-notification replay, immediate reevaluation, scheduler evidence retention, Android system IDs, and Termux media cleanup into generation/owner-fenced behavior.

## Canonical findings closed

- `S09-01` — Startup recovery can detach durable/UI state from a still-active backend.
- `S09-02` — WorkManager foreground setup failure can strand newly claimed queue rows.
- `S09-03` — Scheduler retry and XDM stop-state transition can invalidate each other.
- `S09-04` — Boot/package restore can run the full recovery pipeline twice concurrently.
- `S09-05` — UIDT can remain logically unfinished after unexpected coroutine failure.
- `S09-06` — Foreground-service exception can skip service shutdown after claim release.
- `S09-07` — Immediate reevaluation events can be dropped despite being labeled durable.
- `S09-08` — Schedule-window execution relies on an inexact 15-minute periodic sweep.
- `S09-09` — Short-window precision is detected but unused.
- `S09-10` — Pending terminal-notification replay is not fenced to the current attempt.
- `S09-11` — Scheduler recovery/audit logs grow without compaction.
- `S09-12` — Per-download Android system IDs are never retired.
- `S09-13` — Real scheduler lifecycle behavior lacks Android instrumentation coverage.
- `S09-14` — Green validators encode implementation markers rather than behavior.
- `S09-15` — Some queue-condition threshold crossings have no immediate observer.
- `DS5-S09-01` — Process-local pause/cancel intent is never cleared for later start/resume/retry.
- `DS5-S09-02` — Queue-condition-monitor startup failure keeps durable startup-recovery hold installed after transfer recovery is safe.
- `DS5-S09-03` — Retry-ledger secure-context requirement is sticky and failure identity omits URL/backend/source/request identity.
- `DS5-S09-04` — Boot/package restore posts restored-download notification before knowing recovery is admission-safe.
- `DS5-S09-05` — UIDT initial notification setup happens before claim authorization and has no guarded cleanup path.
- `RERUN56-S09-01` — Clear completed deletes retryable Termux media owner jobs while leaving retryable media library output rows.

## Implementation summary

- Added `SchedulerRecoveryLeaseCoordinator`, a process-independent shared-preferences lease with TTL, owner token, and explicit release. App startup and restore workers acquire this lease before running ownership recovery so app startup, boot restore, and package restore do not race the same durable recovery pipeline.
- Changed `TransferRestoreWorker` so restored-download notifications are posted only after recovery reports `admissionSafe`. Unsafe restore leaves the queue hold in place and retries without announcing restored downloads.
- Changed `XdmApplication` startup ordering so migration/runtime/native-HLS recovery are the critical admission gates. Queue-condition monitor failure is reported, but does not keep the startup-recovery hold installed once transfer recovery is safe.
- Added `releaseFailedExecutionOwner` to `QueueIntelligenceCoordinator` and wired WorkManager, UIDT, FGS, and scheduler fallback paths through it. Foreground/notification setup failure now releases the durable claim, drops the process-local owner, and schedules a durable immediate reevaluation.
- Made durable immediate reevaluation events consumable with explicit `reevaluate-consumed` tombstones. The worker consumes them when it observes them instead of treating them as volatile hints. Recovery/audit log files are compacted to bounded retained evidence.
- Added `schedulePrecisionWakeup` / `PRECISION_WAKEUP_TAG` so retry/schedule wakeups use one-time precise WorkManager jobs rather than relying only on the 15-minute periodic sweep.
- Changed UIDT startup so `setNotification` happens only after `authorizeClaimedExecution` succeeds. Unexpected UIDT coroutine failure has a guarded cleanup path and reschedules safely.
- Changed foreground service startup so `startForeground()` failure is detected. Execution failures release the durable claim and schedule recovery instead of leaving logical owners behind.
- Added `resetStopIntentForNewExecution` and `clearForNewExecution` in `TransferExecutionRuntime`, preventing stale process-local pause/cancel intents from immediately self-pausing or self-cancelling later executions.
- Expanded retry ledger identity to include attempt generation, source URL, destination URI, backend, requested backend, MIME type, and failure message. Secure-context requirement is now scoped to that identity instead of sticky across unrelated retries.
- Extended `TerminalNotificationKey` / `TransferTerminalEvent` / `TransferNotifications` with `requestIdentity`, and persisted it in the scheduler recovery log so terminal notification replay is fenced to the exact attempt/request/backend/source identity.
- Added `TransferSystemIdRegistry.retire()` and called it after confirmed graph deletion, so per-download Android system IDs are retired when durable rows are gone.
- Changed `TermuxMediaPipelineManager.prepareDownloadGraphDeletion` to hide Termux-owned media-output rows while cleaning terminal job bridge URIs, preventing Clear Completed from deleting retryable owners while leaving library rows.
- Added focused source contract `Xar09SchedulerRecoveryContractTest` and `tools/validate-xar09-scheduler-recovery.py`; wired it into Gradle final static gates, shell release gate, and `PROJECT_MANIFEST.json`.

## Focused validation performed

- `tools/validate-xar01-build-provenance.py`
- `tools/validate-xar02-concurrency-cas.py`
- `tools/validate-xar03-persistence-generation-integrity.py`
- `tools/validate-xar04-navigation-session-ownership.py`
- `tools/validate-xar05-external-intake-admission.py`
- `tools/validate-xar06-storage-publication.py`
- `tools/validate-xar07-native-http-protocol.py`
- `tools/validate-xar08-aria2-ownership.py`
- `tools/validate-xar09-scheduler-recovery.py`
- Python syntax checks for validation tooling.
- JSON syntax check for `PROJECT_MANIFEST.json`.
- Shell syntax check for `tools/run-final-release-gate.sh`.

A full Gradle run was not performed in the artifact build container because the offline Gradle 9.7.1 environment is not available there. This remains an intermediate overlay and should be applied with `--no-validate`.

## Exit criterion

XAR09 is complete when startup/restore recovery has a single durable lease owner, foreground/UIDT/FGS setup failures release claims, retry and terminal-notification replay are request/attempt fenced, scheduler evidence is bounded and consumable, system IDs are retired on graph deletion, and every S09 canonical root has a focused contract that fails on the audited defect.
