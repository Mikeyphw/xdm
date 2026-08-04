# Bug Hunt Remediation Phase 4 r2: Queue, Scheduling, And State Machines

Phase 4 r2 converts the original Phase 4 scaffold into runtime-wired behavior.

## Runtime wiring delivered

- `FileBackedQueueSchedulingRecoveryStore` writes app-private, fsynced evidence for Phase 4 queue, scheduling, recovery, and notification decisions.
- `XdmApplication` exposes `QueueSchedulingRecoveryProvider` and installs the persistent store root at startup.
- `QueueIntelligenceCoordinator` records queue-slot reservations through `QueueStateMachinePlanner.reserveSlotAtomically(...)` before claiming eligible downloads.
- `QueueIntelligenceCoordinator.pauseAllDurably()` writes the global durable hold before callers invoke `TransferExecutionRuntime.pauseAll()`.
- Pause All callers now use that hold-first path from the ViewModel, notification receiver, foreground service, foreground-service timeout/destruction, and WorkManager stop path.
- Queue deletion now goes through `deleteQueueSafely(...)`, which records either a safe delete, reject-dangling-references, or reassign-then-delete plan.
- `TransferNotifications.terminalIfFirst(...)` records a `TerminalNotificationRecord` and suppresses duplicate terminal notification dispatch using the persisted idempotency key.
- Active notifications inspect `NotificationPermissionState` and include an in-app-control warning when Android notification drawer permission is denied.
- WorkManager and user-initiated job stop reasons are written through the durable coordinator and mirrored through `TransferExecutionStopReasonRecorder`.

## Prevention rule

A Phase 4 artifact is not complete when it only adds models and tests. The production app must wire those models into the paths that start, pause, delete, recover, notify, and record system stops.
